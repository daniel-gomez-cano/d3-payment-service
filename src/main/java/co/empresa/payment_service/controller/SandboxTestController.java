package co.empresa.payment_service.controller;

import com.mercadopago.client.preference.PreferenceClient;
import com.mercadopago.client.preference.PreferenceItemRequest;
import com.mercadopago.client.preference.PreferenceRequest;
import com.mercadopago.resources.preference.Preference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ⚠️ CONTROLADOR TEMPORAL DE PRUEBA — SOLO PARA DESARROLLO/SANDBOX
 *
 * Permite verificar que la integración con MercadoPago funciona correctamente
 * sin necesitar el order-service, event-service ni ticket-service corriendo.
 *
 * Endpoint: GET /sandbox/test-mercadopago
 * No requiere JWT — accesible directo desde el navegador o curl.
 *
 * ELIMINAR o comentar antes de entregar/desplegar en producción.
 */
@RestController
@RequestMapping("/sandbox")
@Slf4j
public class SandboxTestController {

    @Value("${mercadopago.sandbox:true}")
    private boolean sandbox;

    @Value("${mercadopago.notification-url}")
    private String notificationUrl;

    @Value("${mercadopago.back-url.success}")
    private String backUrlSuccess;

    @Value("${mercadopago.back-url.failure}")
    private String backUrlFailure;

    @Value("${mercadopago.back-url.pending}")
    private String backUrlPending;

    @GetMapping("/check-config")
        public Map<String, Object> checkConfig(
                @Value("${mercadopago.access-token}") String token) {
        return Map.of(
                "tokenLength", token.length(),
                "tokenStart", token.substring(0, Math.min(20, token.length())),
                "tokenEnd", token.substring(Math.max(0, token.length() - 10)),
                "sandbox", sandbox
        );
        }

    /**
     * Crea una preferencia de pago real en MercadoPago con datos de prueba
     * y devuelve la URL del checkout sandbox.
     *
     * Cómo usarlo:
     *   1. Abre http://localhost:8084/sandbox/test-mercadopago en el navegador
     *   2. Verás un JSON con paymentUrl — ábrelo
     *   3. Usa las tarjetas de prueba de MercadoPago para simular el pago
     */
    @GetMapping("/test-mercadopago")
    public Map<String, Object> testMercadoPago() {
        try {
            log.info("[SandboxTest] Creando preferencia de prueba en MercadoPago...");

            // Ítem de prueba simulando una boleta de VivaEventos
            PreferenceItemRequest item = PreferenceItemRequest.builder()
                    .id("BOLETA-TEST-001")
                    .title("Boleta Prueba — Concierto VivaEventos")
                    .description("Boleta de prueba para verificar integración MercadoPago")
                    .quantity(2)
                    .unitPrice(new BigDecimal("15000"))
                    .currencyId("COP")
                    .build();

            // NOTA: En esta prueba NO incluimos notificationUrl (webhook) ni backUrls
            // para aislar la prueba y evitar que una URL de ngrok expirada cause el fallo.
            // La integración completa con webhook se prueba cuando el flujo end-to-end esté listo.
            PreferenceRequest preferenceReq = PreferenceRequest.builder()
                    .items(List.of(item))
                    .externalReference("SANDBOX-TEST-" + System.currentTimeMillis())
                    .build();

            PreferenceClient client = new PreferenceClient();
            Preference preference = client.create(preferenceReq);

            String paymentUrl = sandbox
                    ? preference.getSandboxInitPoint()
                    : preference.getInitPoint();

            log.info("[SandboxTest] Preferencia creada exitosamente — ID: {}", preference.getId());

            return Map.of(
                    "status", "OK",
                    "mensaje", "Preferencia creada exitosamente en MercadoPago",
                    "sandboxMode", sandbox,
                    "preferenceId", preference.getId(),
                    "paymentUrl", paymentUrl,
                    "totalCOP", "30000",
                    "instrucciones", Map.of(
                            "paso1", "Copia el paymentUrl y ábrelo en el navegador",
                            "paso2", "Inicia sesión con tu cuenta de prueba COMPRADOR de MercadoPago",
                            "paso3", "Usa una tarjeta de prueba para simular el pago",
                            "tarjetaAprobada", "4509 9535 6623 3704 — CVV: 123 — Venc: 11/25 — Nombre: APRO",
                            "tarjetaRechazada", "4000 0000 0000 0002 — CVV: 123 — Venc: 11/25 — Nombre: OTHE",
                            "referencia", "https://www.mercadopago.com.co/developers/es/docs/your-integrations/test/cards"
                    ),
                    "timestamp", LocalDateTime.now().toString()
            );

        } catch (com.mercadopago.exceptions.MPApiException e) {
            // Loguear y devolver la respuesta REAL de MercadoPago (status HTTP + body)
            String mpResponseBody = e.getApiResponse() != null
                    ? e.getApiResponse().getContent()
                    : "sin respuesta";
            int mpStatusCode = e.getApiResponse() != null
                    ? e.getApiResponse().getStatusCode()
                    : -1;

            log.error("[SandboxTest] Error de API MercadoPago — HTTP {}: {}", mpStatusCode, mpResponseBody);

            return Map.of(
                    "status", "ERROR_MP_API",
                    "httpStatus", mpStatusCode,
                    "mensajeMP", mpResponseBody,
                    "diagnostico", mpStatusCode == 401
                            ? "Token inválido — verifica MP_ACCESS_TOKEN en tu .env"
                            : mpStatusCode == 400
                            ? "Solicitud inválida — revisa los datos enviados a MercadoPago"
                            : "Error de MercadoPago — revisa mensajeMP para el detalle",
                    "timestamp", LocalDateTime.now().toString()
            );

        } catch (Exception e) {
            log.error("[SandboxTest] Error inesperado: {}", e.getMessage(), e);
            return Map.of(
                    "status", "ERROR",
                    "mensaje", e.getMessage(),
                    "timestamp", LocalDateTime.now().toString()
            );
        }
    }
}
