package co.empresa.payment_service.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Mensaje que el payment-service publica en RabbitMQ con el resultado
 * de un pago. El order-service lo consume para actualizar el carrito.
 *
 * Campos de trazabilidad (buyerId, amount, processedAt) permiten al
 * order-service actuar sin consultar al payment-service.
 * mercadoPagoPaymentId permite correlacionar con la pasarela.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentResultEvent {

    /** ID del carrito asociado al pago */
    private String cartId;

    /** ID interno del pago en el payment-service */
    private String paymentId;

    /** ID del pago en MercadoPago — disponible tras aprobación */
    private String mercadoPagoPaymentId;

    /** sub de Keycloak del comprador */
    private String buyerId;

    private String buyerEmail;

    /**
     * Estado del pago.
     * Valores posibles: APPROVED, REJECTED, FAILED, PENDING, REFUNDED
     */
    private String status;

    /** Monto cobrado */
    private BigDecimal amount;

    /** Detalle del resultado (cc_rejected_insufficient_amount, razón de fallo, etc.) */
    private String statusDetail;

    /** Momento en que el pago fue procesado */
    private LocalDateTime processedAt;
}