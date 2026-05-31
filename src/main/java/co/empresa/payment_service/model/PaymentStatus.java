package co.empresa.payment_service.model;

/**
 * Estados del ciclo de vida de un pago en VivaEventos.
 *
 * Flujo típico:
 *   PENDING → APPROVED  (pago exitoso)
 *   PENDING → REJECTED  (rechazado por el banco o MP)
 *   PENDING → FAILED    (error técnico o timeout)
 *   APPROVED → REFUNDED (reembolso por cancelación del evento)
 */
public enum PaymentStatus {

    /** Preferencia creada en MercadoPago; esperando que el comprador complete el pago. */
    PENDING,

    /** MercadoPago confirmó el cobro exitosamente. */
    APPROVED,

    /** El banco o MercadoPago rechazó el pago (fondos insuficientes, tarjeta bloqueada, etc.). */
    REJECTED,

    /** Error técnico al comunicarse con la pasarela o timeout superado. */
    FAILED,

    /** El pago fue revertido porque el evento fue cancelado por el organizador. */
    REFUNDED
}
