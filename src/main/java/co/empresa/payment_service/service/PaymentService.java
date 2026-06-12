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
import co.empresa.payment_service.model.Payment;
import co.empresa.payment_service.model.PaymentAuditLog;
import co.empresa.payment_service.model.PaymentStatus;
import co.empresa.payment_service.repository.PaymentAuditLogRepository;
import co.empresa.payment_service.repository.PaymentRepository;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository         paymentRepo;
    private final PaymentAuditLogRepository auditRepo;
    private final OrderServiceClient        orderClient;
    private final RabbitTemplate            rabbitTemplate; // inyectado con JSON converter desde RabbitMQConfig

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    @Value("${stripe.success-url}")
    private String successUrl;

    @Value("${stripe.cancel-url}")
    private String cancelUrl;

    // ================================================================
    //  INICIAR PAGO — flujo HTTP (desde PaymentController)
    // ================================================================

    /**
     * Punto de entrada HTTP: el comprador solicita pagar su carrito directamente.
     *
     * Flujo:
     *  1. Verificar idempotencia — si ya existe un Payment para este cartId, devolver el existente.
     *  2. Obtener resumen del carrito desde el order-service (con JWT).
     *  3. Crear sesión de Stripe Checkout (con Circuit Breaker + Retry).
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

        // 3. CREAR SESIÓN DE STRIPE CHECKOUT
        Session session   = createStripeCheckoutSession(cart, idempotencyKey);
        String paymentUrl = session.getUrl();

        // 4. PERSISTIR PAYMENT
        Payment payment;
        try {
            payment = paymentRepo.save(Payment.builder()
                    .cartId(cartId)
                    .buyerId(buyerId)
                    .stripeSessionId(session.getId())
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
                "Sesión Stripe creada — sessionId: " + session.getId());

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

        // CREAR SESIÓN STRIPE con los datos del evento
        Session session   = createStripeSessionFromEvent(event);
        String paymentUrl = session.getUrl();

        Payment payment;
        try {
            payment = paymentRepo.save(Payment.builder()
                    .cartId(cartId)
                    .buyerId(event.getBuyerId())
                    .stripeSessionId(session.getId())
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
                "Sesión Stripe creada desde evento — sessionId: " + session.getId());

        // Notificar a order-service que el pago está PENDING (incluye paymentUrl)
        publishPaymentResult(payment, null);

        log.info("[RabbitMQ] Pago PENDING creado id={} para cartId={}", payment.getId(), cartId);
    }

    // ================================================================
    //  STRIPE — creación de sesiones de Checkout
    // ================================================================

    /**
     * Crea la sesión de Stripe Checkout para el flujo HTTP (CartSummaryInternal).
     * Protegido con Circuit Breaker + Retry apuntando a la instancia "stripe".
     */
    @CircuitBreaker(name = "stripe", fallbackMethod = "stripeCheckoutFallback")
    @Retry(name = "stripe")
    private Session createStripeCheckoutSession(CartSummaryInternal cart, String idempotencyKey) {
        try {
            return buildStripeSession(
                    cart.cartId(),
                    cart.total(),
                    buildDescription(cart.items() != null
                            ? cart.items().stream().map(i -> i.ticketTypeName()).toList()
                            : List.of())
            );
        } catch (StripeException e) {
            log.error("[HTTP][Stripe] Error al crear sesión: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Error al crear la sesión de pago: " + e.getMessage());
        }
    }

    /**
     * Crea la sesión de Stripe Checkout para el flujo RabbitMQ (OrderCreatedEvent).
     * No llama a orderClient — usa directamente los datos del mensaje.
     */
    @CircuitBreaker(name = "stripe", fallbackMethod = "stripeEventFallback")
    @Retry(name = "stripe")
    private Session createStripeSessionFromEvent(OrderCreatedEvent event) {
        try {
            List<String> itemNames = event.getItems() != null
                    ? event.getItems().stream().map(i -> i.getTicketTypeName()).toList()
                    : List.of();
            return buildStripeSession(event.getCartId(), event.getTotal(),
                    buildDescription(itemNames));
        } catch (StripeException e) {
            log.error("[RabbitMQ][Stripe] Error al crear sesión: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Error al crear la sesión de Stripe: " + e.getMessage());
        }
    }

    /**
     * Construye y envía el SessionCreateParams a la API de Stripe.
     *
     * La successUrl se construye reemplazando CART_ID_PLACEHOLDER con el cartId real,
     * de modo que Stripe redirija al frontend con ?cart_id=<cartId> ya incluido.
     *
     * Ejemplo de stripe.success-url en application.yml / k8s:
     *   http://localhost:8084/payment-success?cart_id=CART_ID_PLACEHOLDER&session_id={CHECKOUT_SESSION_ID}
     *
     * Stripe reemplaza {CHECKOUT_SESSION_ID} automáticamente al redirigir.
     * Este método reemplaza CART_ID_PLACEHOLDER con el cartId real antes de enviarlo.
     */
    private Session buildStripeSession(String cartId, BigDecimal total,
                                       String description) throws StripeException {

        long unitAmountCentavos = total.multiply(BigDecimal.valueOf(100)).longValue();

        // ── Inyectar el cartId real en la success URL ──────────────
        String resolvedSuccessUrl = successUrl.replace("CART_ID_PLACEHOLDER", cartId);
        // ────────────────────────────────────────────────────────────────

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .addLineItem(
                        SessionCreateParams.LineItem.builder()
                                .setQuantity(1L)
                                .setPriceData(
                                        SessionCreateParams.LineItem.PriceData.builder()
                                                .setCurrency("cop")
                                                .setUnitAmount(unitAmountCentavos)
                                                .setProductData(
                                                        SessionCreateParams.LineItem.PriceData
                                                                .ProductData.builder()
                                                                .setName(description)
                                                                .build())
                                                .build())
                                .build())
                .setSuccessUrl(resolvedSuccessUrl)   // ← URL con cart_id ya resuelto
                .setCancelUrl(cancelUrl)
                .putMetadata("cartId", cartId)       // correlación para el webhook
                .build();

        return Session.create(params);
    }

    private String buildDescription(List<String> ticketTypeNames) {
        if (ticketTypeNames == null || ticketTypeNames.isEmpty()) {
            return "Boletas VivaEventos";
        }
        return ticketTypeNames.get(0)
                + (ticketTypeNames.size() > 1
                ? " y " + (ticketTypeNames.size() - 1) + " más"
                : "");
    }

    // Fallbacks del Circuit Breaker

    @SuppressWarnings("unused")
    private Session stripeCheckoutFallback(CartSummaryInternal cart,
                                           String idempotencyKey, Throwable t) {
        log.error("[CircuitBreaker] Stripe no disponible — {}", t.getMessage());
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "La pasarela de pagos no está disponible. Por favor intenta en unos minutos.");
    }

    @SuppressWarnings("unused")
    private Session stripeEventFallback(OrderCreatedEvent event, Throwable t) {
        log.error("[CircuitBreaker] Stripe no disponible para cartId={}: {}",
                event.getCartId(), t.getMessage());
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "La pasarela de pagos no está disponible. Por favor intenta en unos minutos.");
    }

    // ================================================================
    //  PROCESAR WEBHOOK DE STRIPE
    // ================================================================

    /**
     * Procesa la notificación asíncrona de Stripe.
     *
     * Flujo:
     *  1. Verificar la firma HMAC-SHA256 del payload con Webhook.constructEvent().
     *  2. Dispatch al handler según el tipo de evento.
     *  3. Cada handler actualiza el Payment, registra auditoría y publica a RabbitMQ.
     *
     * Eventos manejados:
     *  · checkout.session.completed    → APPROVED  (pago exitoso)
     *  · checkout.session.expired      → FAILED    (sesión venció sin pago)
     *  · payment_intent.payment_failed → REJECTED  (el intento de cobro fue rechazado)
     */
    @Transactional
    public void processWebhook(String payload, String sigHeader) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("[Webhook] Firma inválida: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Firma de webhook inválida");
        }

        log.info("[Webhook] Evento recibido: {}", event.getType());

        switch (event.getType()) {
            case "checkout.session.completed"    -> handleSessionCompleted(event);
            case "checkout.session.expired"      -> handleSessionExpired(event);
            case "payment_intent.payment_failed" -> handlePaymentFailed(event);
            default -> log.debug("[Webhook] Evento ignorado: {}", event.getType());
        }
    }

    private void handleSessionCompleted(Event event) {
        Session session;
        try {
            session = (Session) event.getDataObjectDeserializer().deserializeUnsafe();
        } catch (Exception e) {
            log.error("[Webhook] No se pudo deserializar checkout.session.completed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se pudo deserializar el evento checkout.session.completed");
        }

        String cartId          = session.getMetadata().get("cartId");
        String paymentIntentId = session.getPaymentIntent();

        Payment payment = paymentRepo.findByCartId(cartId).orElse(null);
        if (payment == null) {
            log.warn("[Webhook] No se encontró Payment para cartId={}", cartId);
            return;
        }
        if (payment.getStatus() == PaymentStatus.APPROVED) {
            log.info("[Webhook] Pago ya APPROVED para cartId={} — ignorando duplicado", cartId);
            return;
        }

        PaymentStatus previousStatus = payment.getStatus();
        payment.setStripeSessionId(session.getId());
        payment.setPaymentIntentId(paymentIntentId);
        payment.setStatus(PaymentStatus.APPROVED);
        payment.setPaidAt(LocalDateTime.now());
        paymentRepo.save(payment);

        saveAuditLog(payment.getId(), previousStatus, PaymentStatus.APPROVED,
                "WEBHOOK:stripe",
                "checkout.session.completed — sessionId=" + session.getId()
                        + " paymentIntentId=" + paymentIntentId);

        publishPaymentResult(payment, null);
        log.info("[Webhook] Payment id={} → APPROVED", payment.getId());
    }

    private void handleSessionExpired(Event event) {
        Session session;
        try {
            session = (Session) event.getDataObjectDeserializer().deserializeUnsafe();
        } catch (Exception e) {
            log.error("[Webhook] No se pudo deserializar checkout.session.expired: {}", e.getMessage());
            return;
        }

        String cartId = session.getMetadata().get("cartId");
        updateToFailedStatus(cartId, "La sesión de pago expiró sin completarse");
    }

    private void handlePaymentFailed(Event event) {
        PaymentIntent pi;
        try {
            pi = (PaymentIntent) event.getDataObjectDeserializer().deserializeUnsafe();
        } catch (Exception e) {
            log.error("[Webhook] No se pudo deserializar payment_intent.payment_failed: {}", e.getMessage());
            return;
        }

        paymentRepo.findByPaymentIntentId(pi.getId()).ifPresent(payment ->
                updatePaymentStatus(payment, PaymentStatus.REJECTED,
                        pi.getLastPaymentError() != null
                                ? pi.getLastPaymentError().getMessage()
                                : "payment_intent.payment_failed")
        );
    }

    private void updateToFailedStatus(String cartId, String detail) {
        paymentRepo.findByCartId(cartId).ifPresent(payment -> {
            if (payment.getStatus() == PaymentStatus.PENDING) {
                updatePaymentStatus(payment, PaymentStatus.FAILED, detail);
            }
        });
    }

    private void updatePaymentStatus(Payment payment, PaymentStatus newStatus, String detail) {
        PaymentStatus previousStatus = payment.getStatus();
        payment.setStatus(newStatus);
        if (newStatus == PaymentStatus.REJECTED || newStatus == PaymentStatus.FAILED) {
            payment.setFailureReason(detail);
        }
        paymentRepo.save(payment);

        saveAuditLog(payment.getId(), previousStatus, newStatus, "WEBHOOK:stripe", detail);
        publishPaymentResult(payment, detail);
        log.info("[Webhook] Payment id={} → {}", payment.getId(), newStatus);
    }

    // ================================================================
    //  REEMBOLSO
    // ================================================================

    /**
     * Procesa un reembolso de un pago aprobado.
     * Solo puede ejecutarlo un ORGANIZER (validado por @PreAuthorize en el controller).
     *
     * Stripe requiere el PaymentIntent ID (pi_...) para emitir el reembolso.
     * Este ID se persiste en Payment.paymentIntentId cuando llega el webhook
     * checkout.session.completed.
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
        if (payment.getPaymentIntentId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este pago no tiene PaymentIntent registrado; no se puede reembolsar automáticamente.");
        }

        processRefundInGateway(payment.getPaymentIntentId());

        PaymentStatus previousStatus = payment.getStatus();
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setRefundedAt(LocalDateTime.now());
        paymentRepo.save(payment);

        saveAuditLog(paymentId, previousStatus, PaymentStatus.REFUNDED,
                "SYSTEM:refund:organizer=" + actorId,
                "Razón: " + req.getReason());

        publishPaymentResult(payment, req.getReason());

        log.info("[Refund] Reembolso procesado — paymentId={} cartId={}", paymentId, payment.getCartId());
        return toResponse(payment);
    }

    @CircuitBreaker(name = "stripe")
    @Retry(name = "stripe")
    private void processRefundInGateway(String paymentIntentId) {
        try {
            Refund.create(RefundCreateParams.builder()
                    .setPaymentIntent(paymentIntentId)
                    .build());
        } catch (StripeException e) {
            log.error("[Refund] Stripe error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Error al procesar el reembolso en la pasarela: " + e.getMessage());
        }
    }

    // ================================================================
    //  PUBLICACIÓN RABBITMQ — payment.exchange → order-service
    // ================================================================

    /**
     * Publica el resultado del pago hacia order-service.
     * Usado por: processWebhook (todos los estados), initiatePaymentFromEvent (PENDING),
     * refundPayment.
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
                RabbitMQConfig.PAYMENT_EXCHANGE,
                RabbitMQConfig.PAYMENT_RESULT_KEY,
                event);

        log.info("[RabbitMQ] → PaymentResultEvent publicado — cartId={} status={}",
                payment.getCartId(), payment.getStatus());
    }

    /**
     * Publica un FAILED cuando el listener falla antes de crear el Payment en BD.
     * (Stripe rechazó, circuit breaker abierto, etc.)
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
                result);

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
