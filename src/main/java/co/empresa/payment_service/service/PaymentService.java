package co.empresa.payment_service.service;

import co.empresa.payment_service.config.OrderServiceClient;
import co.empresa.payment_service.config.OrderServiceClient.CartSummaryInternal;
import co.empresa.payment_service.config.RabbitMQConfig;
import co.empresa.payment_service.dto.AuditLogResponse;
import co.empresa.payment_service.dto.InitiatePaymentRequest;
import co.empresa.payment_service.dto.OrderCreatedEvent;
import co.empresa.payment_service.dto.PaymentResponse;
import co.empresa.payment_service.dto.PaymentResultEvent;
import co.empresa.payment_service.dto.RefundRequest;
import co.empresa.payment_service.dto.WebhookNotification;
import co.empresa.payment_service.model.Payment;
import co.empresa.payment_service.model.PaymentAuditLog;
import co.empresa.payment_service.model.PaymentStatus;
import co.empresa.payment_service.repository.PaymentAuditLogRepository;
import co.empresa.payment_service.repository.PaymentRepository;
import com.mercadopago.client.payment.PaymentClient;
import com.mercadopago.client.preference.PreferenceBackUrlsRequest;
import com.mercadopago.client.preference.PreferenceClient;
import com.mercadopago.client.preference.PreferenceItemRequest;
import com.mercadopago.client.preference.PreferenceRequest;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.preference.Preference;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository        paymentRepo;
    private final PaymentAuditLogRepository auditRepo;
    private final OrderServiceClient       orderClient;
    private final RabbitTemplate           rabbitTemplate; // inyectado con JSON converter desde RabbitMQConfig

    @Value("${mercadopago.sandbox:true}")
    private boolean sandbox;

    @Value("${mercadopago.notification-url}")
    private String notificationUrl;

    @Value("${mercadopago.back-url.success}")
    private String backUrlSuccess;

    @Value("${mercadopago.back-url.failure}")
    private String backUrlFailure;

    @Value("${mercadopago.back-url.pending}")
    private String backUrlPending;

    // ================================================================
    //  INICIAR PAGO — flujo HTTP (desde PaymentController)
    // ================================================================

    /**
     * Punto de entrada HTTP: el comprador solicita pagar su carrito directamente.
     *
     * Flujo:
     *  1. Verificar idempotencia — si ya existe un Payment para este cartId, devolver el existente.
     *  2. Obtener resumen del carrito desde el order-service (con JWT).
     *  3. Crear preferencia en MercadoPago (con Circuit Breaker + Retry).
     *  4. Persistir el Payment en estado PENDING.
     *  5. Notificar al order-service que el carrito entró en CHECKED_OUT.
     *  6. Devolver la URL de pago al frontend.
     */
    @Transactional
    public PaymentResponse initiatePayment(InitiatePaymentRequest req, String buyerId, String jwtToken) {

        String cartId         = req.getCartId();
        String idempotencyKey = cartId;

        // 1. IDEMPOTENCIA
        return paymentRepo.findByIdempotencyKey(idempotencyKey)
                .map(existing -> {
                    log.info("[HTTP] Pago ya existente para cartId={} — devolviendo id={}",
                            cartId, existing.getId());
                    return toResponse(existing);
                })
                .orElseGet(() -> createNewPayment(cartId, buyerId, jwtToken, idempotencyKey));
    }

    private PaymentResponse createNewPayment(String cartId, String buyerId,
                                             String jwtToken, String idempotencyKey) {
        // 2. OBTENER RESUMEN DEL CARRITO vía HTTP (flujo directo con JWT)
        CartSummaryInternal cart = orderClient.getCartSummary(cartId, jwtToken);

        if (!cart.buyerId().equals(buyerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Este carrito no te pertenece");
        }
        if (cart.total().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El total del carrito debe ser mayor a cero");
        }

        // 3. CREAR PREFERENCIA EN MERCADOPAGO
        Preference preference = createMercadoPagoPreference(cart, idempotencyKey);

        // 4. PERSISTIR PAYMENT
        String paymentUrl = sandbox
                ? preference.getSandboxInitPoint()
                : preference.getInitPoint();

        Payment payment;
        try {
            payment = paymentRepo.save(Payment.builder()
                    .cartId(cartId)
                    .buyerId(buyerId)
                    .gatewayPreferenceId(preference.getId())
                    .idempotencyKey(idempotencyKey)
                    .amount(cart.total())
                    .currency(cart.currency() != null ? cart.currency() : "COP")
                    .status(PaymentStatus.PENDING)
                    .paymentUrl(paymentUrl)
                    .build());
        } catch (DataIntegrityViolationException e) {
            log.warn("[HTTP] Race condition para cartId={}, buscando existente", cartId);
            return paymentRepo.findByIdempotencyKey(idempotencyKey)
                    .map(this::toResponse)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "Pago en conflicto, intenta de nuevo"));
        }

        saveAuditLog(payment.getId(), null, PaymentStatus.PENDING,
                "BUYER:" + buyerId,
                "Preferencia creada en MercadoPago — ID: " + preference.getId());

        // 5. NOTIFICAR CHECKOUT al order-service (vía HTTP, el JWT sigue siendo válido)
        orderClient.notifyCheckout(cartId, jwtToken);

        log.info("[HTTP] Pago PENDING creado id={} para cartId={}", payment.getId(), cartId);
        return toResponse(payment);
    }

    // ================================================================
    //  INICIAR PAGO — flujo RabbitMQ (desde PaymentEventListener)
    // ================================================================

    /**
     * Punto de entrada asíncrono: order-service publicó un OrderCreatedEvent.
     *
     * No necesita JWT ni llama a orderClient — todos los datos del carrito
     * vienen dentro del mensaje. El resultado se publica de vuelta
     * como PaymentResultEvent en payment.exchange.
     */
    @Transactional
    public void initiatePaymentFromEvent(OrderCreatedEvent event) {
        String cartId = event.getCartId();

        // IDEMPOTENCIA: evitar procesar el mismo carrito dos veces
        if (paymentRepo.findByIdempotencyKey(cartId).isPresent()) {
            log.warn("[RabbitMQ] Pago ya existe para cartId={} — ignorando duplicado", cartId);
            return;
        }

        // CREAR PREFERENCIA EN MERCADOPAGO con los datos del evento
        Preference preference = createPreferenceFromEvent(event);

        String paymentUrl = sandbox
                ? preference.getSandboxInitPoint()
                : preference.getInitPoint();

        Payment payment;
        try {
            payment = paymentRepo.save(Payment.builder()
                    .cartId(cartId)
                    .buyerId(event.getBuyerId())
                    .gatewayPreferenceId(preference.getId())
                    .idempotencyKey(cartId)
                    .amount(event.getTotal())
                    .currency("COP")
                    .status(PaymentStatus.PENDING)
                    .paymentUrl(paymentUrl)
                    .build());
        } catch (DataIntegrityViolationException e) {
            log.warn("[RabbitMQ] Race condition para cartId={} — ignorando", cartId);
            return;
        }

        saveAuditLog(payment.getId(), null, PaymentStatus.PENDING,
                "RABBITMQ:order-service",
                "Preferencia MP creada desde evento — ID: " + preference.getId());

        // Notificar a order-service que el pago está PENDING (incluye paymentUrl)
        publishPaymentResult(payment, null);

        log.info("[RabbitMQ] Pago PENDING creado id={} para cartId={}", payment.getId(), cartId);
    }

    /**
     * Crea la preferencia de MercadoPago desde los datos del OrderCreatedEvent.
     * No llama a orderClient — usa directamente los datos del mensaje.
     */
    @CircuitBreaker(name = "mercadopago", fallbackMethod = "mercadoPagoEventFallback")
    @Retry(name = "mercadopago")
    private Preference createPreferenceFromEvent(OrderCreatedEvent event) {
        try {
            String description = event.getItems() != null && !event.getItems().isEmpty()
                    ? event.getItems().get(0).getTicketTypeName()
                    + (event.getItems().size() > 1
                    ? " y " + (event.getItems().size() - 1) + " más" : "")
                    : "Boletas VivaEventos";

            PreferenceItemRequest item = PreferenceItemRequest.builder()
                    .title(description)
                    .quantity(1)
                    .unitPrice(event.getTotal())
                    .currencyId("COP")
                    .build();

            PreferenceBackUrlsRequest backUrls = PreferenceBackUrlsRequest.builder()
                    .success(backUrlSuccess)
                    .failure(backUrlFailure)
                    .pending(backUrlPending)
                    .build();

            PreferenceRequest preferenceReq = PreferenceRequest.builder()
                    .items(List.of(item))
                    .backUrls(backUrls)
                    .autoReturn("approved")
                    .notificationUrl(notificationUrl)
                    .externalReference(event.getCartId()) // correlaciona el webhook con el carrito
                    .build();

            return new PreferenceClient().create(preferenceReq);

        } catch (MPApiException e) {
            log.error("[RabbitMQ][MP] API error {}: {}", e.getStatusCode(), e.getApiResponse().getContent());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Error MP API: " + e.getMessage());
        } catch (MPException e) {
            log.error("[RabbitMQ][MP] SDK error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "MP no disponible: " + e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private Preference mercadoPagoEventFallback(OrderCreatedEvent event, Throwable t) {
        log.error("[CircuitBreaker] MP no disponible para cartId={}: {}",
                event.getCartId(), t.getMessage());
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "La pasarela de pagos no está disponible. Por favor intenta en unos minutos.");
    }

    // ================================================================
    //  PREFERENCIA MERCADOPAGO — flujo HTTP (CartSummaryInternal)
    // ================================================================

    @CircuitBreaker(name = "mercadopago", fallbackMethod = "mercadoPagoFallback")
    @Retry(name = "mercadopago")
    @TimeLimiter(name = "mercadopago")
    public CompletableFuture<Preference> createMercadoPagoPreferenceAsync(
            CartSummaryInternal cart, String idempotencyKey) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return buildAndSendPreference(cart, idempotencyKey);
            } catch (MPException | MPApiException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @CircuitBreaker(name = "mercadopago", fallbackMethod = "mercadoPagoPreferenceFallback")
    @Retry(name = "mercadopago")
    private Preference createMercadoPagoPreference(CartSummaryInternal cart, String idempotencyKey) {
        try {
            return buildAndSendPreference(cart, idempotencyKey);
        } catch (MPApiException e) {
            log.error("[HTTP][MP] API error {}: {}", e.getStatusCode(), e.getApiResponse().getContent());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Error al crear la preferencia de pago: " + e.getMessage());
        } catch (MPException e) {
            log.error("[HTTP][MP] SDK error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo conectar con la pasarela de pagos. Intenta de nuevo.");
        }
    }

    private Preference buildAndSendPreference(CartSummaryInternal cart, String idempotencyKey)
            throws MPException, MPApiException {

        String description = cart.items() != null && !cart.items().isEmpty()
                ? cart.items().get(0).ticketTypeName()
                + (cart.items().size() > 1 ? " y " + (cart.items().size() - 1) + " más" : "")
                : "Boletas VivaEventos";

        PreferenceItemRequest item = PreferenceItemRequest.builder()
                .title(description)
                .quantity(1)
                .unitPrice(cart.total())
                .currencyId("COP")
                .build();

        PreferenceBackUrlsRequest backUrls = PreferenceBackUrlsRequest.builder()
                .success(backUrlSuccess)
                .failure(backUrlFailure)
                .pending(backUrlPending)
                .build();

        PreferenceRequest preferenceReq = PreferenceRequest.builder()
                .items(List.of(item))
                .backUrls(backUrls)
                .autoReturn("approved")
                .notificationUrl(notificationUrl)
                .externalReference(cart.cartId())
                .build();

        return new PreferenceClient().create(preferenceReq);
    }

    @SuppressWarnings("unused")
    private Preference mercadoPagoPreferenceFallback(CartSummaryInternal cart,
                                                     String idempotencyKey, Throwable t) {
        log.error("[CircuitBreaker] MercadoPago no disponible — {}", t.getMessage());
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "La pasarela de pagos no está disponible. Por favor intenta en unos minutos.");
    }

    @SuppressWarnings("unused")
    private CompletableFuture<Preference> mercadoPagoFallback(CartSummaryInternal cart,
                                                              String idempotencyKey, Throwable t) {
        log.error("[CircuitBreaker][Async] MercadoPago no disponible — {}", t.getMessage());
        return CompletableFuture.failedFuture(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "La pasarela de pagos no está disponible."));
    }

    // ================================================================
    //  PROCESAR WEBHOOK DE MERCADOPAGO
    // ================================================================

    /**
     * Procesa la notificación asíncrona de MercadoPago.
     *
     * Flujo:
     *  1. Consulta el pago real a la API de MP usando el gatewayPaymentId.
     *  2. Busca el Payment local usando la referencia externa (cartId).
     *  3. Actualiza el estado y registra en auditoría.
     *  4. Publica PaymentResultEvent → payment.exchange (order-service lo consume).
     */
    @Transactional
    public void processWebhook(WebhookNotification notification) {
        if (!"payment".equals(notification.getType())) {
            log.debug("[Webhook] Tipo ignorado: {}", notification.getType());
            return;
        }

        String gatewayPaymentId = notification.getData().getId();
        log.info("[Webhook] Procesando notificación — gatewayPaymentId={}", gatewayPaymentId);

        com.mercadopago.resources.payment.Payment mpPayment = fetchPaymentFromGateway(gatewayPaymentId);
        if (mpPayment == null) {
            log.warn("[Webhook] No se pudo obtener el pago {} de MercadoPago", gatewayPaymentId);
            return;
        }

        String cartId         = mpPayment.getExternalReference();
        String mpStatus       = mpPayment.getStatus();
        String mpStatusDetail = mpPayment.getStatusDetail();

        log.info("[Webhook] gatewayPaymentId={} cartId={} status={} detail={}",
                gatewayPaymentId, cartId, mpStatus, mpStatusDetail);

        Payment payment = paymentRepo.findByCartId(cartId)
                .orElseGet(() -> paymentRepo.findByGatewayPaymentId(gatewayPaymentId).orElse(null));

        if (payment == null) {
            log.warn("[Webhook] No se encontró Payment para cartId={} — ignorando", cartId);
            return;
        }

        // IDEMPOTENCIA: evitar procesar el mismo estado dos veces
        PaymentStatus newStatus = mapMercadoPagoStatus(mpStatus);
        if (payment.getStatus() == newStatus) {
            log.info("[Webhook] Estado ya actualizado a {} para payment={} — ignorando duplicado",
                    newStatus, payment.getId());
            return;
        }

        PaymentStatus previousStatus = payment.getStatus();

        payment.setGatewayPaymentId(gatewayPaymentId);
        payment.setStatus(newStatus);

        if (newStatus == PaymentStatus.APPROVED) {
            payment.setPaidAt(LocalDateTime.now());
        }
        if (newStatus == PaymentStatus.REJECTED || newStatus == PaymentStatus.FAILED) {
            payment.setFailureReason(mpStatusDetail);
        }

        paymentRepo.save(payment);

        saveAuditLog(payment.getId(), previousStatus, newStatus,
                "WEBHOOK:mercadopago",
                "MP status=" + mpStatus + " detail=" + mpStatusDetail + " mpId=" + gatewayPaymentId);

        // Notificar a order-service vía RabbitMQ (reemplaza las llamadas HTTP anteriores)
        publishPaymentResult(payment, mpStatusDetail);

        log.info("[Webhook] Payment id={} actualizado: {} → {}", payment.getId(), previousStatus, newStatus);
    }

    @CircuitBreaker(name = "mercadopago")
    @Retry(name = "mercadopago")
    private com.mercadopago.resources.payment.Payment fetchPaymentFromGateway(String gatewayPaymentId) {
        try {
            return new PaymentClient().get(Long.parseLong(gatewayPaymentId));
        } catch (MPApiException | MPException e) {
            log.error("[Webhook] Error al consultar pago {} en MP: {}", gatewayPaymentId, e.getMessage());
            return null;
        }
    }

    private PaymentStatus mapMercadoPagoStatus(String mpStatus) {
        return switch (mpStatus) {
            case "approved"               -> PaymentStatus.APPROVED;
            case "rejected"               -> PaymentStatus.REJECTED;
            case "cancelled"              -> PaymentStatus.FAILED;
            case "refunded", "charged_back" -> PaymentStatus.REFUNDED;
            default                       -> PaymentStatus.PENDING;
        };
    }

    // ================================================================
    //  REEMBOLSO
    // ================================================================

    /**
     * Procesa un reembolso de un pago aprobado.
     * Solo puede ejecutarlo un ORGANIZER (validado por @PreAuthorize en el controller).
     */
    @Transactional
    public PaymentResponse refundPayment(String paymentId, RefundRequest req, String actorId) {
        Payment payment = paymentRepo.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Pago no encontrado: " + paymentId));

        if (payment.getStatus() != PaymentStatus.APPROVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se pueden reembolsar pagos APPROVED. Estado actual: " + payment.getStatus());
        }
        if (payment.getGatewayPaymentId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este pago no tiene ID de pasarela registrado; no se puede reembolsar automáticamente.");
        }

        processRefundInGateway(Long.parseLong(payment.getGatewayPaymentId()));

        PaymentStatus previousStatus = payment.getStatus();
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setRefundedAt(LocalDateTime.now());
        paymentRepo.save(payment);

        saveAuditLog(paymentId, previousStatus, PaymentStatus.REFUNDED,
                "SYSTEM:refund:organizer=" + actorId,
                "Razón: " + req.getReason());

        // Notificar a order-service del reembolso vía RabbitMQ
        publishPaymentResult(payment, req.getReason());

        log.info("[Refund] Reembolso procesado — paymentId={} cartId={}", paymentId, payment.getCartId());
        return toResponse(payment);
    }

    @CircuitBreaker(name = "mercadopago")
    @Retry(name = "mercadopago")
    private void processRefundInGateway(Long gatewayPaymentId) {
        try {
            new PaymentClient().refund(gatewayPaymentId);
        } catch (MPApiException e) {
            log.error("[Refund] API MP error {}: {}", e.getStatusCode(), e.getApiResponse().getContent());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Error al procesar el reembolso en la pasarela: " + e.getMessage());
        } catch (MPException e) {
            log.error("[Refund] SDK MP error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo conectar con la pasarela para el reembolso. Intenta de nuevo.");
        }
    }

    // ================================================================
    //  PUBLICACIÓN RABBITMQ — payment.exchange → order-service
    // ================================================================

    /**
     * Publica el resultado del pago hacia order-service.
     * Usado por: processWebhook, initiatePaymentFromEvent (PENDING), refundPayment.
     */
    private void publishPaymentResult(Payment payment, String statusDetail) {
        PaymentResultEvent event = PaymentResultEvent.builder()
                .cartId(payment.getCartId())
                .paymentId(payment.getId())
                .buyerId(payment.getBuyerId())
                .status(payment.getStatus().name())
                .amount(payment.getAmount())
                .statusDetail(statusDetail)
                .processedAt(LocalDateTime.now())
                .build();

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.PAYMENT_EXCHANGE,   // "payment.exchange"
                RabbitMQConfig.PAYMENT_RESULT_KEY, // "payment.result"
                event
        );
        log.info("[RabbitMQ] → PaymentResultEvent publicado — cartId={} status={}",
                payment.getCartId(), payment.getStatus());
    }

    /**
     * Publica un FAILED cuando el listener falla antes de crear el Payment en BD.
     * (MercadoPago rechazó, circuit breaker abierto, etc.)
     * Llamado desde PaymentEventListener en el bloque catch.
     */
    public void publishFailedResult(OrderCreatedEvent event, String errorMsg) {
        PaymentResultEvent result = PaymentResultEvent.builder()
                .cartId(event.getCartId())
                .buyerId(event.getBuyerId())
                .status("FAILED")
                .statusDetail("Error interno al procesar el pago: " + errorMsg)
                .processedAt(LocalDateTime.now())
                .build();

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.PAYMENT_EXCHANGE,
                RabbitMQConfig.PAYMENT_RESULT_KEY,
                result
        );
        log.warn("[RabbitMQ] → PaymentResultEvent FAILED publicado — cartId={}", event.getCartId());
    }

    // ================================================================
    //  CONSULTAS
    // ================================================================

    public PaymentResponse getPaymentById(String paymentId, String requestingUserId) {
        Payment payment = paymentRepo.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pago no encontrado"));

        if (!payment.getBuyerId().equals(requestingUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes acceso a este pago");
        }
        return toResponse(payment);
    }

    public PaymentResponse getPaymentByCartId(String cartId, String requestingUserId) {
        Payment payment = paymentRepo.findByCartId(cartId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No hay pago registrado para este carrito"));

        if (!payment.getBuyerId().equals(requestingUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes acceso a este pago");
        }
        return toResponse(payment);
    }

    public List<PaymentResponse> getMyPayments(String buyerId) {
        return paymentRepo.findByBuyerIdOrderByCreatedAtDesc(buyerId).stream()
                .map(this::toResponse)
                .toList();
    }

    public List<AuditLogResponse> getAuditLog(String paymentId, String requestingUserId) {
        Payment payment = paymentRepo.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pago no encontrado"));

        if (!payment.getBuyerId().equals(requestingUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes acceso a este pago");
        }

        return auditRepo.findByPaymentIdOrderByCreatedAtAsc(paymentId).stream()
                .map(AuditLogResponse::from)
                .toList();
    }

    // ================================================================
    //  HELPERS
    // ================================================================

    private void saveAuditLog(String paymentId, PaymentStatus previous,
                              PaymentStatus next, String actor, String detail) {
        auditRepo.save(PaymentAuditLog.builder()
                .paymentId(paymentId)
                .previousStatus(previous)
                .newStatus(next)
                .actor(actor)
                .detail(detail)
                .build());
    }

    private PaymentResponse toResponse(Payment p) {
        return PaymentResponse.builder()
                .paymentId(p.getId())
                .cartId(p.getCartId())
                .buyerId(p.getBuyerId())
                .amount(p.getAmount())
                .currency(p.getCurrency())
                .status(p.getStatus())
                .paymentUrl(p.getPaymentUrl())
                .failureReason(p.getFailureReason())
                .createdAt(p.getCreatedAt())
                .paidAt(p.getPaidAt())
                .refundedAt(p.getRefundedAt())
                .build();
    }
}