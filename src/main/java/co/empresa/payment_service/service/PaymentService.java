package co.empresa.payment_service.service;

import co.empresa.payment_service.config.OrderServiceClient;
import co.empresa.payment_service.config.OrderServiceClient.CartSummaryInternal;
import co.empresa.payment_service.dto.AuditLogResponse;
import co.empresa.payment_service.dto.InitiatePaymentRequest;
import co.empresa.payment_service.dto.PaymentResponse;
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

    private final PaymentRepository paymentRepo;
    private final PaymentAuditLogRepository auditRepo;
    private final OrderServiceClient orderClient;

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
    //  INICIAR PAGO
    // ================================================================

    /**
     * Punto de entrada principal: el comprador solicita pagar su carrito.
     *
     * Flujo:
     *  1. Verificar idempotencia — si ya existe un Payment para este cartId, devolver el existente.
     *  2. Obtener resumen del carrito desde el order-service.
     *  3. Crear preferencia en MercadoPago (con Circuit Breaker + Retry + TimeLimiter).
     *  4. Persistir el Payment en estado PENDING.
     *  5. Notificar al order-service que el carrito entró en CHECKED_OUT.
     *  6. Devolver la URL de pago al frontend.
     */
    @Transactional
    public PaymentResponse initiatePayment(InitiatePaymentRequest req, String buyerId, String jwtToken) {

        String cartId = req.getCartId();
        String idempotencyKey = cartId; // cartId como clave de idempotencia

        // --- 1. IDEMPOTENCIA ---
        // Si ya existe un pago para este carrito, devolvemos el existente sin crear otro
        return paymentRepo.findByIdempotencyKey(idempotencyKey)
                .map(existing -> {
                    log.info("[PaymentService] Pago ya existente para cartId={} — devolviendo existente id={}",
                            cartId, existing.getId());
                    return toResponse(existing);
                })
                .orElseGet(() -> createNewPayment(cartId, buyerId, jwtToken, idempotencyKey));
    }

    private PaymentResponse createNewPayment(String cartId, String buyerId,
                                              String jwtToken, String idempotencyKey) {
        // --- 2. OBTENER RESUMEN DEL CARRITO ---
        CartSummaryInternal cart = orderClient.getCartSummary(cartId, jwtToken);

        if (!cart.buyerId().equals(buyerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Este carrito no te pertenece");
        }

        if (cart.total().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El total del carrito debe ser mayor a cero");
        }

        // --- 3. CREAR PREFERENCIA EN MERCADOPAGO ---
        Preference preference = createMercadoPagoPreference(cart, idempotencyKey);

        // --- 4. PERSISTIR PAYMENT ---
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
            // Condición de carrera: dos hilos llegaron al mismo tiempo con el mismo cartId
            log.warn("[PaymentService] Condición de carrera detectada para cartId={}, buscando existente", cartId);
            return paymentRepo.findByIdempotencyKey(idempotencyKey)
                    .map(this::toResponse)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "Pago en conflicto, intenta de nuevo"));
        }

        // Registro de auditoría
        saveAuditLog(payment.getId(), null, PaymentStatus.PENDING,
                "BUYER:" + buyerId,
                "Preferencia creada en MercadoPago — ID: " + preference.getId());

        // --- 5. NOTIFICAR CHECKOUT AL ORDER-SERVICE (no bloqueante) ---
        orderClient.notifyCheckout(cartId, jwtToken);

        log.info("[PaymentService] Pago PENDING creado id={} para cartId={}", payment.getId(), cartId);
        return toResponse(payment);
    }

    /**
     * Llama a la API de MercadoPago para crear una preferencia de pago.
     * Protegido con Circuit Breaker, Retry y TimeLimiter.
     */
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

    /**
     * Versión síncrona usada internamente (para transacciones simples).
     * CircuitBreaker + Retry la protegen igualmente vía AOP.
     */
    @CircuitBreaker(name = "mercadopago", fallbackMethod = "mercadoPagoPreferenceFallback")
    @Retry(name = "mercadopago")
    private Preference createMercadoPagoPreference(CartSummaryInternal cart, String idempotencyKey) {
        try {
            return buildAndSendPreference(cart, idempotencyKey);
        } catch (MPApiException e) {
            log.error("[MercadoPago] Error de API — status: {}, mensaje: {}",
                    e.getStatusCode(), e.getApiResponse().getContent());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Error al crear la preferencia de pago: " + e.getMessage());
        } catch (MPException e) {
            log.error("[MercadoPago] Error de SDK: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo conectar con la pasarela de pagos. Intenta de nuevo.");
        }
    }

    private Preference buildAndSendPreference(CartSummaryInternal cart, String idempotencyKey)
            throws MPException, MPApiException {

        // Construir la descripción de los ítems del carrito
        String description = cart.items() != null && !cart.items().isEmpty()
                ? cart.items().get(0).ticketTypeName() + (cart.items().size() > 1
                        ? " y " + (cart.items().size() - 1) + " más" : "")
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
                .autoReturn("approved") // redirige automáticamente si el pago es aprobado
                .notificationUrl(notificationUrl)
                .externalReference(cart.cartId()) // referencia para correlacionar el webhook
                .build();

        // Llamada directa — nuestra idempotencia en BD es suficiente para evitar duplicados
        PreferenceClient client = new PreferenceClient();
        return client.create(preferenceReq);
    }

    /**
     * Fallback para el Circuit Breaker cuando MercadoPago no responde.
     * Resilience4j llama este método por reflexión (AOP), por eso el IDE
     * lo marca como "no usado" — es un falso positivo.
     */
    @SuppressWarnings("unused")
    private Preference mercadoPagoPreferenceFallback(CartSummaryInternal cart,
                                                      String idempotencyKey, Throwable t) {
        log.error("[CircuitBreaker] MercadoPago no disponible — {}", t.getMessage());
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "La pasarela de pagos no está disponible. Por favor intenta en unos minutos.");
    }

    // ================================================================
    //  PROCESAR WEBHOOK DE MERCADOPAGO
    // ================================================================

    /**
     * Procesa la notificación asíncrona de MercadoPago.
     *
     * MercadoPago envía un webhook cuando el estado de un pago cambia.
     * Este método:
     *  1. Consulta el pago real a la API de MP usando el gatewayPaymentId.
     *  2. Busca el Payment local usando la referencia externa (cartId).
     *  3. Actualiza el estado y registra en auditoría.
     *  4. Notifica al order-service el resultado final.
     */
    @Transactional
    public void processWebhook(WebhookNotification notification) {
        // Solo procesamos eventos de tipo "payment"
        if (!"payment".equals(notification.getType())) {
            log.debug("[Webhook] Tipo ignorado: {}", notification.getType());
            return;
        }

        String gatewayPaymentId = notification.getData().getId();
        log.info("[Webhook] Procesando notificación — gatewayPaymentId={}", gatewayPaymentId);

        // Consultar el pago real a MercadoPago para obtener estado y referencia
        com.mercadopago.resources.payment.Payment mpPayment = fetchPaymentFromGateway(gatewayPaymentId);
        if (mpPayment == null) {
            log.warn("[Webhook] No se pudo obtener el pago {} de MercadoPago", gatewayPaymentId);
            return;
        }

        String cartId        = mpPayment.getExternalReference(); // el cartId que pusimos en externalReference
        String mpStatus      = mpPayment.getStatus();            // "approved", "rejected", "pending", etc.
        String mpStatusDetail = mpPayment.getStatusDetail();

        log.info("[Webhook] gatewayPaymentId={} cartId={} status={} detail={}",
                gatewayPaymentId, cartId, mpStatus, mpStatusDetail);

        // Buscar el Payment local
        Payment payment = paymentRepo.findByCartId(cartId)
                .orElseGet(() -> paymentRepo.findByGatewayPaymentId(gatewayPaymentId).orElse(null));

        if (payment == null) {
            log.warn("[Webhook] No se encontró Payment para cartId={} — ignorando", cartId);
            return;
        }

        // Evitar procesar el mismo estado dos veces (idempotencia del webhook)
        PaymentStatus newStatus = mapMercadoPagoStatus(mpStatus);
        if (payment.getStatus() == newStatus) {
            log.info("[Webhook] Estado ya actualizado a {} para payment={} — ignorando duplicado",
                    newStatus, payment.getId());
            return;
        }

        PaymentStatus previousStatus = payment.getStatus();

        // Actualizar el Payment
        payment.setGatewayPaymentId(gatewayPaymentId);
        payment.setStatus(newStatus);

        if (newStatus == PaymentStatus.APPROVED) {
            payment.setPaidAt(LocalDateTime.now());
        }
        if (newStatus == PaymentStatus.REJECTED || newStatus == PaymentStatus.FAILED) {
            payment.setFailureReason(mpStatusDetail);
        }

        paymentRepo.save(payment);

        // Registro de auditoría
        saveAuditLog(payment.getId(), previousStatus, newStatus,
                "WEBHOOK:mercadopago",
                "MP status=" + mpStatus + " detail=" + mpStatusDetail + " mpId=" + gatewayPaymentId);

        // Notificar al order-service el resultado
        if (newStatus == PaymentStatus.APPROVED) {
            orderClient.notifyPaymentApproved(cartId, payment.getId());
        } else if (newStatus == PaymentStatus.REJECTED || newStatus == PaymentStatus.FAILED) {
            orderClient.notifyPaymentFailed(cartId, payment.getId(), mpStatusDetail);
        }

        log.info("[Webhook] Payment id={} actualizado: {} → {}", payment.getId(), previousStatus, newStatus);
    }

    @CircuitBreaker(name = "mercadopago")
    @Retry(name = "mercadopago")
    private com.mercadopago.resources.payment.Payment fetchPaymentFromGateway(String gatewayPaymentId) {
        try {
            PaymentClient client = new PaymentClient();
            return client.get(Long.parseLong(gatewayPaymentId));
        } catch (MPApiException | MPException e) {
            log.error("[Webhook] Error al consultar pago {} en MercadoPago: {}", gatewayPaymentId, e.getMessage());
            return null;
        }
    }

    /**
     * Convierte el estado de MercadoPago al enum interno PaymentStatus.
     *
     * Estados de MP: https://www.mercadopago.com.co/developers/es/reference/payments/_payments_id/get
     */
    private PaymentStatus mapMercadoPagoStatus(String mpStatus) {
        return switch (mpStatus) {
            case "approved"      -> PaymentStatus.APPROVED;
            case "rejected"      -> PaymentStatus.REJECTED;
            case "cancelled"     -> PaymentStatus.FAILED;
            case "refunded",
                 "charged_back"  -> PaymentStatus.REFUNDED;
            default              -> PaymentStatus.PENDING; // "pending", "in_process", "authorized"
        };
    }

    // ================================================================
    //  REEMBOLSO
    // ================================================================

    /**
     * Procesa un reembolso de un pago aprobado.
     * Solo puede ejecutarlo un ORGANIZER (validado por @PreAuthorize en el controller).
     *
     * Llama a la API de reembolsos de MercadoPago y actualiza el estado local.
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

        // Llamar a MercadoPago para hacer el reembolso
        processRefundInGateway(Long.parseLong(payment.getGatewayPaymentId()));

        PaymentStatus previousStatus = payment.getStatus();
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setRefundedAt(LocalDateTime.now());
        paymentRepo.save(payment);

        saveAuditLog(paymentId, previousStatus, PaymentStatus.REFUNDED,
                "SYSTEM:refund:organizer=" + actorId,
                "Razón: " + req.getReason());

        log.info("[PaymentService] Reembolso procesado — paymentId={} cartId={}", paymentId, payment.getCartId());
        return toResponse(payment);
    }

    @CircuitBreaker(name = "mercadopago")
    @Retry(name = "mercadopago")
    private void processRefundInGateway(Long gatewayPaymentId) {
        try {
            PaymentClient client = new PaymentClient();
            client.refund(gatewayPaymentId); // reembolso total
        } catch (MPApiException e) {
            log.error("[Refund] Error de API MP: {} — {}", e.getStatusCode(),
                    e.getApiResponse().getContent());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Error al procesar el reembolso en la pasarela: " + e.getMessage());
        } catch (MPException e) {
            log.error("[Refund] Error MP SDK: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo conectar con la pasarela para el reembolso. Intenta de nuevo.");
        }
    }

    // ================================================================
    //  CONSULTAS
    // ================================================================

    public PaymentResponse getPaymentById(String paymentId, String requestingUserId) {
        Payment payment = paymentRepo.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Pago no encontrado"));

        // El comprador solo puede ver sus propios pagos
        if (!payment.getBuyerId().equals(requestingUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No tienes acceso a este pago");
        }
        return toResponse(payment);
    }

    public PaymentResponse getPaymentByCartId(String cartId, String requestingUserId) {
        Payment payment = paymentRepo.findByCartId(cartId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No hay pago registrado para este carrito"));

        if (!payment.getBuyerId().equals(requestingUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No tienes acceso a este pago");
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Pago no encontrado"));

        // Solo el dueño del pago o un organizador puede ver la auditoría
        if (!payment.getBuyerId().equals(requestingUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No tienes acceso a este pago");
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
