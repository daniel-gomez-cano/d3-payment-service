package co.empresa.payment_service.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entidad principal que representa un intento/transacción de pago.
 *
 * Cada vez que un comprador inicia el pago de un carrito se crea UN registro aquí.
 * El campo idempotencyKey (= cartId) garantiza que nunca se cobre dos veces
 * el mismo carrito: si llega una segunda solicitud con el mismo cartId,
 * se devuelve el Payment existente en lugar de crear uno nuevo.
 */
@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payments_cart_id",       columnList = "cart_id"),
        @Index(name = "idx_payments_buyer_id",      columnList = "buyer_id"),
        @Index(name = "idx_payments_idempotency",   columnList = "idempotency_key", unique = true),
        @Index(name = "idx_payments_gateway_id",    columnList = "gateway_payment_id")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    // -------- Referencias externas --------

    /** ID del carrito en el order-service que originó este pago. */
    @Column(name = "cart_id", nullable = false)
    private String cartId;

    /** sub de Keycloak del comprador. */
    @Column(name = "buyer_id", nullable = false)
    private String buyerId;

    // -------- Pasarela (MercadoPago) --------

    /**
     * ID de preferencia de MercadoPago (se genera al llamar a la API).
     * Permite redirigir al usuario al checkout de MP.
     */
    @Column(name = "gateway_preference_id")
    private String gatewayPreferenceId;

    /**
     * ID numérico del pago en MercadoPago (llega por webhook una vez pagado).
     * Es el que se usa para consultar el estado real y hacer reembolsos.
     */
    @Column(name = "gateway_payment_id")
    private String gatewayPaymentId;

    // -------- Idempotencia --------

    /**
     * Clave única que evita cobros duplicados.
     * Se construye como el cartId; si dos hilos concurrentes llegan con el
     * mismo cartId, la restricción UNIQUE de la BD garantiza que solo uno triunfa.
     */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    // -------- Financiero --------

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** Código ISO 4217. Para Colombia: "COP". */
    @Column(nullable = false, length = 3)
    private String currency;

    // -------- Estado --------

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    /**
     * URL de pago generada por MercadoPago.
     * En sandbox: getSandboxInitPoint(); en producción: getInitPoint().
     */
    @Column(name = "payment_url", length = 2048)
    private String paymentUrl;

    /** Razón del rechazo/fallo (del webhook de MP o de la excepción capturada). */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /** Veces que se reintentó la llamada a la pasarela antes de crear/actualizar. */
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    // -------- Auditoría de fechas --------

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Fecha exacta en que MercadoPago confirmó el pago. */
    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /** Fecha en que se procesó el reembolso. */
    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt  = LocalDateTime.now();
        if (status == null) status = PaymentStatus.PENDING;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
