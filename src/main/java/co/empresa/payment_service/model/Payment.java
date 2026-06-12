package co.empresa.payment_service.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entidad principal que representa un intento/transacción de pago.
 *
 * Cada vez que un comprador inicia el pago de un carrito se crea UN registro.
 * El campo idempotencyKey (= cartId) evita cobros duplicados.
 */
@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payments_cart_id", columnList = "cart_id"),
        @Index(name = "idx_payments_buyer_id", columnList = "buyer_id"),
        @Index(name = "idx_payments_idempotency", columnList = "idempotency_key", unique = true),
        @Index(name = "idx_payments_payment_intent", columnList = "payment_intent_id"),
        @Index(name = "idx_payments_session", columnList = "stripe_session_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    // -------- Referencias externas --------

    /** ID del carrito en order-service. */
    @Column(name = "cart_id", nullable = false)
    private String cartId;

    /** sub de Keycloak del comprador. */
    @Column(name = "buyer_id", nullable = false)
    private String buyerId;

    @Column(name = "buyer_email")
    private String buyerEmail;  // ← agregar esto

    // -------- Stripe --------

    /**
     * ID de la Checkout Session creada en Stripe.
     * Ejemplo: cs_test_xxxxxxxxx
     */
    @Column(name = "stripe_session_id")
    private String stripeSessionId;

    /**
     * ID del PaymentIntent generado por Stripe.
     * Ejemplo: pi_xxxxxxxxx
     */
    @Column(name = "payment_intent_id")
    private String paymentIntentId;

    /**
     * ID del Charge asociado al pago.
     * Ejemplo: ch_xxxxxxxxx
     */
    @Column(name = "stripe_charge_id")
    private String stripeChargeId;

    // -------- Idempotencia --------

    @Column(
            name = "idempotency_key",
            nullable = false,
            unique = true,
            length = 255
    )
    private String idempotencyKey;

    // -------- Financiero --------

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /**
     * Código ISO 4217.
     * Stripe espera minúsculas (cop, usd, eur...)
     */
    @Column(nullable = false, length = 3)
    private String currency;

    // -------- Estado --------

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    /**
     * URL del Checkout de Stripe.
     */
    @Column(name = "payment_url", length = 2048)
    private String paymentUrl;

    /**
     * Mensaje de error devuelto por Stripe.
     */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /**
     * Número de reintentos realizados.
     */
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    // -------- Auditoría --------

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Fecha confirmada por Stripe mediante webhook.
     */
    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /**
     * Fecha de reembolso.
     */
    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();

        if (status == null) {
            status = PaymentStatus.PENDING;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}