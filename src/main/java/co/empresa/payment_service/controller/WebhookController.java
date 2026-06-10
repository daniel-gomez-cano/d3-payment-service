package co.empresa.payment_service.controller;

import co.empresa.payment_service.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Recibe las notificaciones asíncronas (webhooks) de Stripe.
 *
 * ──────────────────────────────────────────────────────────────────────────
 *  SEGURIDAD
 * ──────────────────────────────────────────────────────────────────────────
 * Este endpoint NO requiere JWT de Keycloak — Stripe no envía tokens.
 * La autenticidad se garantiza mediante la firma HMAC-SHA256 que Stripe
 * incluye en el header "Stripe-Signature". La verificación se realiza en
 * PaymentService.processWebhook() mediante Webhook.constructEvent(), que
 * lanza SignatureVerificationException si la firma no coincide.
 *
 * Asegúrate de excluir este path en tu SecurityFilterChain:
 *   .requestMatchers("/api/payments/webhook/**").permitAll()
 *
 * ──────────────────────────────────────────────────────────────────────────
 *  BODY RAW — MUY IMPORTANTE
 * ──────────────────────────────────────────────────────────────────────────
 * El cuerpo de la petición debe llegar a Stripe.Webhook.constructEvent()
 * exactamente como Stripe lo envió (bytes sin modificar).
 * Por eso el parámetro es @RequestBody String payload y NO un DTO.
 * Si Spring deserializa el JSON antes de que llegue aquí, la firma
 * SIEMPRE fallará aunque la clave sea correcta.
 *
 * ──────────────────────────────────────────────────────────────────────────
 *  POLÍTICA DE RESPUESTA
 * ──────────────────────────────────────────────────────────────────────────
 * · Firma inválida       → 400 Bad Request  (Stripe no reintenta errores 4xx)
 * · Error de negocio     → 200 OK           (evita reintentos innecesarios de Stripe)
 * · Error inesperado     → 200 OK           (ídem; el error queda en los logs)
 *
 * Stripe reintenta las notificaciones que reciben 5xx durante hasta 3 días,
 * por eso los errores internos se absorben y logean sin propagar al caller.
 *
 * Endpoint: POST /api/payments/webhook/stripe
 */
@RestController
@RequestMapping("/api/payments/webhook")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final PaymentService paymentService;

    @PostMapping(value = "/stripe", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> handleStripeWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String stripeSignature) {

        log.info("[Webhook] Notificación recibida de Stripe ({} bytes)", payload.length());

        try {
            paymentService.processWebhook(payload, stripeSignature);

        } catch (ResponseStatusException e) {
            // Re-lanzar errores 4xx (firma inválida, payload malformado).
            // Stripe interpreta el 4xx como "no reintentar" — es el comportamiento correcto.
            if (e.getStatusCode().is4xxClientError()) {
                log.warn("[Webhook] Petición rechazada ({}) — {}", e.getStatusCode(), e.getReason());
                throw e;
            }
            // Errores 5xx de negocio: loguear y devolver 200 para evitar reintentos.
            log.error("[Webhook] Error de servicio al procesar notificación: {}", e.getMessage(), e);

        } catch (Exception e) {
            // Error inesperado: loguear y devolver 200 para evitar reintentos.
            log.error("[Webhook] Error inesperado al procesar notificación: {}", e.getMessage(), e);
        }

        return ResponseEntity.ok().build();
    }
}