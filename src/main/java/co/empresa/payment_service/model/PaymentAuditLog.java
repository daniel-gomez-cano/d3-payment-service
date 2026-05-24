package co.empresa.payment_service.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Registro inmutable de auditoría: cada cambio de estado de un Payment
 * genera una fila aquí. Nunca se modifica ni se elimina.
 *
 * Esto garantiza la trazabilidad completa del flujo financiero:
 * quién cambió el estado, cuándo, y por qué.
 */
@Entity
@Table(name = "payment_audit_logs", indexes = {
        @Index(name = "idx_audit_payment_id", columnList = "payment_id")
})
@Getter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Payment al que pertenece este log. */
    @Column(name = "payment_id", nullable = false)
    private String paymentId;

    /** Estado anterior (null si es la primera entrada). */
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 20)
    private PaymentStatus previousStatus;

    /** Estado nuevo. */
    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 20)
    private PaymentStatus newStatus;

    /**
     * Actor que originó el cambio:
     *   "BUYER:{buyerId}"       → el comprador inició el pago
     *   "WEBHOOK:mercadopago"   → confirmación asíncrona de MP
     *   "SYSTEM:refund"         → proceso de reembolso interno
     *   "SYSTEM:retry"          → reintento automático
     */
    @Column(nullable = false, length = 200)
    private String actor;

    /** Detalle adicional: razón del rechazo, ID de gateway, mensaje de error, etc. */
    @Column(length = 1000)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
