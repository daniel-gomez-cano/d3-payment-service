package co.empresa.payment_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Payload que MercadoPago envía al endpoint de webhook cuando ocurre
 * un evento en un pago (creado, actualizado, aprobado, etc.).
 *
 * Documentación oficial:
 * https://www.mercadopago.com.co/developers/es/docs/your-integrations/notifications/webhooks
 *
 * Ejemplo de payload:
 * {
 *   "action": "payment.updated",
 *   "api_version": "v1",
 *   "data": { "id": "1234567890" },
 *   "date_created": "2024-01-15T10:30:00.000-05:00",
 *   "id": 12345,
 *   "live_mode": false,
 *   "type": "payment",
 *   "user_id": "987654321"
 * }
 */
@Data
public class WebhookNotification {

    /** Tipo de acción: "payment.created", "payment.updated". */
    private String action;

    @JsonProperty("api_version")
    private String apiVersion;

    /** Contiene el ID del recurso afectado. */
    private DataPayload data;

    @JsonProperty("date_created")
    private String dateCreated;

    /** ID numérico de la notificación (útil para idempotencia de webhooks). */
    private Long id;

    @JsonProperty("live_mode")
    private boolean liveMode;

    /**
     * Tipo de recurso: "payment", "plan", "subscription", etc.
     * Solo procesamos "payment".
     */
    private String type;

    @JsonProperty("user_id")
    private String userId;

    @Data
    public static class DataPayload {
        /** ID del pago en MercadoPago. */
        private String id;
    }
}
