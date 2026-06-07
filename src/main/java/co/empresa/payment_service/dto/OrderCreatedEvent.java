package co.empresa.payment_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Mensaje que el order-service publica en RabbitMQ cuando el cliente
 * hace checkout del carrito. El payment-service lo consume para
 * iniciar el proceso de cobro con MercadoPago.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderCreatedEvent {

    /** ID del carrito que se está procesando */
    private String cartId;

    /** ID del comprador (sub de Keycloak) */
    private String buyerId;

    /** Email del comprador — MercadoPago lo necesita para la preferencia */
    private String buyerEmail;

    /** Ítems del carrito */
    private List<OrderItem> items;

    /** Total a cobrar (ya con descuento aplicado) */
    private BigDecimal total;

    /** Código de descuento aplicado (puede ser null) */
    private String discountCode;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class OrderItem {
        private String ticketTypeId;
        private String ticketTypeName;
        private int quantity;
        private BigDecimal unitPrice;
        private BigDecimal subtotal;
    }
}
