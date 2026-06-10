package co.empresa.payment_service.controller;

import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Controlador de pruebas para verificar la integración con Stripe en modo test.
 *
 * ⚠️  Solo debe estar activo en entornos de desarrollo y staging.
 *     En producción, protege o elimina estos endpoints (p.ej. con @Profile("!prod")).
 *
 * Base path: /sandbox
 *
 * GET /sandbox/check-config   → verifica que las variables de Stripe estén cargadas
 * GET /sandbox/test-stripe    → crea una Checkout Session de prueba real en Stripe Test Mode
 */
@RestController
@RequestMapping("/sandbox")
@Slf4j
public class SandboxTestController {

    @Value("${stripe.secret-key}")
    private String stripeSecretKey;

    @Value("${stripe.success-url}")
    private String successUrl;

    @Value("${stripe.cancel-url}")
    private String cancelUrl;

    /**
     * Verifica que las variables de entorno de Stripe estén cargadas correctamente.
     * No expone la clave completa — solo muestra el modo y los primeros caracteres.
     */
    @GetMapping("/check-config")
    public Map<String, Object> checkConfig() {
        boolean isTestMode = stripeSecretKey.startsWith("sk_test_");
        boolean isLiveMode = stripeSecretKey.startsWith("sk_live_");

        return Map.of(
                "stripeMode",  isTestMode ? "TEST ✓" : isLiveMode ? "LIVE ⚠️" : "DESCONOCIDO ✗",
                "keyLength",   stripeSecretKey.length(),
                "keyPrefix",   stripeSecretKey.substring(0, Math.min(12, stripeSecretKey.length())),
                "successUrl",  successUrl,
                "cancelUrl",   cancelUrl,
                "advertencia", isLiveMode
                        ? "⚠️  Estás usando claves LIVE — los cargos serán reales"
                        : isTestMode ? "OK — modo test, sin cargos reales" : "Verifica el formato de tu clave",
                "timestamp",   LocalDateTime.now().toString()
        );
    }

    /**
     * Crea una Checkout Session de prueba real en Stripe Test Mode.
     * Requiere que STRIPE_SECRET_KEY sea una clave sk_test_... — no hace cargos reales.
     *
     * Flujo de prueba:
     *  1. Llama a GET /sandbox/test-stripe.
     *  2. Copia el valor de "sessionUrl" de la respuesta y ábrelo en el navegador.
     *  3. Ingresa los datos de una tarjeta de prueba (ver campo "tarjetas_de_prueba").
     *  4. Stripe redirigirá al successUrl configurado en application.yml.
     *  5. Verifica que el webhook (checkout.session.completed) llegue al servicio.
     */
    @GetMapping("/test-stripe")
    public Map<String, Object> testStripe() {
        try {
            log.info("[SandboxTest] Creando sesión de Stripe Checkout de prueba...");

            // Simulamos 2 boletas a $15.000 COP c/u = $30.000 COP total
            // Stripe recibe el monto en centavos: 15000 * 100 = 1.500.000
            long unitAmountCentavos = new BigDecimal("15000")
                    .multiply(BigDecimal.valueOf(100))
                    .longValue();

            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .addLineItem(
                            SessionCreateParams.LineItem.builder()
                                    .setQuantity(2L)
                                    .setPriceData(
                                            SessionCreateParams.LineItem.PriceData.builder()
                                                    .setCurrency("cop")
                                                    .setUnitAmount(unitAmountCentavos)
                                                    .setProductData(
                                                            SessionCreateParams.LineItem.PriceData
                                                                    .ProductData.builder()
                                                                    .setName("Boleta Prueba — Concierto VivaEventos")
                                                                    .setDescription("Boleta de prueba para verificar integración Stripe")
                                                                    .build())
                                                    .build())
                                    .build())
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    .putMetadata("cartId", "SANDBOX-TEST-" + System.currentTimeMillis())
                    .build();

            Session session = Session.create(params);

            log.info("[SandboxTest] Sesión creada exitosamente — sessionId: {}", session.getId());

            return Map.of(
                    "status",     "OK",
                    "mensaje",    "Sesión de Stripe Checkout creada exitosamente en modo TEST",
                    "sessionId",  session.getId(),       // cs_test_...
                    "sessionUrl", session.getUrl(),       // URL a abrir en el navegador
                    "totalCOP",   "30.000 (2 boletas × $15.000)",
                    "expira",     "en 24 horas (comportamiento por defecto de Stripe)",
                    "tarjetas_de_prueba", Map.of(
                            "visa_aprobada",        "4242 4242 4242 4242",
                            "visa_declinada",       "4000 0000 0000 0002  →  charge_failed",
                            "fondos_insuficientes", "4000 0000 0000 9995  →  insufficient_funds",
                            "requiere_3ds",         "4000 0025 0000 3155  →  simula autenticación adicional",
                            "datos_comunes",        "CVV: cualquier 3 dígitos | Fecha: cualquier mes/año futuro | Nombre: cualquier texto",
                            "referencia",           "https://docs.stripe.com/testing#cards"
                    ),
                    "timestamp",  LocalDateTime.now().toString()
            );

        } catch (StripeException e) {
            log.error("[SandboxTest] Error de Stripe — código: {} mensaje: {}",
                    e.getCode(), e.getMessage());

            String diagnostico;
            String code = e.getCode() != null ? e.getCode() : "";
            if (code.contains("authentication") || (e.getMessage() != null && e.getMessage().contains("No API key"))) {
                diagnostico = "Clave inválida — verifica STRIPE_SECRET_KEY en tu .env o en el Secret de K8s";
            } else if (code.equals("api_key_expired")) {
                diagnostico = "La clave de Stripe ha expirado — genera una nueva en el Dashboard";
            } else if (e.getStatusCode() == 401) {
                diagnostico = "Autenticación fallida — asegúrate de usar sk_test_... para pruebas";
            } else {
                diagnostico = "Error de Stripe — revisa el mensaje y el Dashboard en modo Test";
            }

            return Map.of(
                    "status",      "ERROR_STRIPE",
                    "stripeCode",  code.isEmpty() ? "desconocido" : code,
                    "httpStatus",  e.getStatusCode(),
                    "mensaje",     e.getMessage() != null ? e.getMessage() : "sin mensaje",
                    "diagnostico", diagnostico,
                    "timestamp",   LocalDateTime.now().toString()
            );

        } catch (Exception e) {
            log.error("[SandboxTest] Error inesperado: {}", e.getMessage(), e);
            return Map.of(
                    "status",    "ERROR",
                    "mensaje",   e.getMessage() != null ? e.getMessage() : "error desconocido",
                    "timestamp", LocalDateTime.now().toString()
            );
        }
    }
}