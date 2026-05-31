package co.empresa.payment_service.controller;

import co.empresa.payment_service.dto.WebhookNotification;
import co.empresa.payment_service.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Recibe las notificaciones asíncronas (webhooks) de MercadoPago.
 *
 * Este endpoint NO requiere JWT de Keycloak porque MercadoPago no envía tokens JWT.
 * En cambio, validamos la autenticidad de la petición con la firma HMAC que MP incluye
 * en la cabecera "x-signature".
 *
 * Referencia: https://www.mercadopago.com.co/developers/es/docs/your-integrations/notifications/webhooks
 *
 * ¿Cómo funciona la firma HMAC de MercadoPago?
 *   1. MP envía las cabeceras: x-request-id, x-signature
 *   2. x-signature tiene formato: "ts=TIMESTAMP,v1=HMAC_HEX"
 *   3. El mensaje a verificar es: "id:{dataId};request-id:{requestId};ts:{timestamp};"
 *   4. Se calcula HMAC-SHA256 con la clave MP_WEBHOOK_SECRET y se compara con v1.
 */
@RestController
@RequestMapping("/api/payments/webhook")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final PaymentService paymentService;

    @Value("${mercadopago.webhook-secret}")
    private String webhookSecret;

    /**
     * Endpoint de webhook para MercadoPago.
     *
     * MercadoPago espera un 200 OK rápido; si tarda más de 22 segundos reintentará.
     * Por eso el procesamiento real se delega al PaymentService y respondemos de inmediato.
     */
    @PostMapping("/mercadopago")
    public ResponseEntity<Void> handleMercadoPagoWebhook(
            @RequestBody WebhookNotification notification,
            @RequestHeader(value = "x-signature",    required = false) String xSignature,
            @RequestHeader(value = "x-request-id",   required = false) String xRequestId) {

        log.info("[Webhook] Notificación recibida — type={} action={} dataId={}",
                notification.getType(), notification.getAction(),
                notification.getData() != null ? notification.getData().getId() : "null");

        // Validar firma HMAC (desactívala en sandbox local si MP no puede alcanzar tu servidor)
        if (xSignature != null && !isValidSignature(xSignature, xRequestId, notification)) {
            log.warn("[Webhook] Firma inválida — posible llamada no autorizada");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Responder 200 inmediatamente a MercadoPago para que no reintente
        // El procesamiento real puede demorar (consulta a la API de MP, BD, etc.)
        try {
            paymentService.processWebhook(notification);
        } catch (Exception e) {
            // Logueamos el error pero nunca devolvemos 5xx al webhook de MP
            // (si devolvemos error, MP reintentará indefinidamente)
            log.error("[Webhook] Error procesando notificación: {}", e.getMessage(), e);
        }

        return ResponseEntity.ok().build();
    }

    /**
     * Valida la firma HMAC-SHA256 que MercadoPago incluye en x-signature.
     *
     * Formato de x-signature: "ts=1704067200,v1=abc123def456..."
     * Mensaje a firmar:       "id:{dataId};request-id:{xRequestId};ts:{timestamp};"
     */
    private boolean isValidSignature(String xSignature, String xRequestId,
                                     WebhookNotification notification) {
        try {
            // Parsear ts y v1 de la cabecera x-signature
            String timestamp = null;
            String receivedHmac = null;

            for (String part : xSignature.split(",")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2) {
                    if ("ts".equals(kv[0]))   timestamp    = kv[1];
                    if ("v1".equals(kv[0]))   receivedHmac = kv[1];
                }
            }

            if (timestamp == null || receivedHmac == null) {
                log.warn("[Webhook] Cabecera x-signature mal formada: {}", xSignature);
                return false;
            }

            // Construir el mensaje a verificar
            String dataId = notification.getData() != null ? notification.getData().getId() : "";
            String manifest = "id:" + dataId + ";"
                    + "request-id:" + (xRequestId != null ? xRequestId : "") + ";"
                    + "ts:" + timestamp + ";";

            // Calcular HMAC-SHA256
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computedBytes = mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8));
            String computedHmac = HexFormat.of().formatHex(computedBytes);

            boolean valid = computedHmac.equalsIgnoreCase(receivedHmac);
            if (!valid) {
                log.warn("[Webhook] HMAC no coincide — esperado: {} recibido: {}", computedHmac, receivedHmac);
            }
            return valid;

        } catch (Exception e) {
            log.error("[Webhook] Error validando firma: {}", e.getMessage());
            return false;
        }
    }
}
