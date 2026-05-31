package co.empresa.payment_service.dto;

import co.empresa.payment_service.model.PaymentStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Respuesta que el payment-service devuelve al cliente tras cualquier
 * operación sobre un pago.
 */
@Data
@Builder
public class PaymentResponse {

    private String paymentId;
    private String cartId;
    private String buyerId;
    private BigDecimal amount;
    private String currency;
    private PaymentStatus status;

    /**
     * URL a la que el frontend debe redirigir al usuario para completar el pago.
     * Solo presente en estado PENDING.
     */
    private String paymentUrl;

    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
    private LocalDateTime refundedAt;
}
