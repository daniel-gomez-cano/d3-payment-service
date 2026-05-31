package co.empresa.payment_service.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Petición para iniciar el pago de un carrito.
 * El buyer envía el ID del carrito que ya tiene en el order-service.
 */
@Data
public class InitiatePaymentRequest {

    @NotBlank(message = "El cartId es obligatorio")
    private String cartId;
}
