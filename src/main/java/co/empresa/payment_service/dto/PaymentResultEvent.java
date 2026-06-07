package co.empresa.payment_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Mensaje que el payment-service publica en RabbitMQ tras procesar
 * el pago con MercadoPago. El order-service lo consume para actualizar
 * el estado del carrito y disparar la generación de boletas si fue aprobado.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentResultEvent {

    /** ID del carrito asociado al pago */
    private String cartId;

    /** ID del pago en MercadoPago */
    private String paymentId;

    /** ID del comprador */
    private String buyerId;

    /**
     * Estado del pago.
     * Valores posibles: APPROVED, REJECTED, FAILED, PENDING, REFUNDED
     */
    private String status;

    /** Monto cobrado */
    private BigDecimal amount;

    /** Mensaje de error o detalle (para REJECTED/FAILED) */
    private String statusDetail;

    /** Momento en que el pago fue procesado */
    private LocalDateTime processedAt;
}
