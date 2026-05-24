package co.empresa.payment_service.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

/**
 * Configura el WebClient apuntando al order-service.
 */
@Configuration
class OrderWebClientConfig {

    @Bean
    public WebClient orderServiceWebClient(
            @Value("${order.service.url:http://localhost:8083}") String baseUrl) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }
}

/**
 * Cliente HTTP para comunicarse con el order-service.
 *
 * El payment-service llama al order-service para:
 *  1. Obtener el resumen del carrito (monto total, ítems) antes de crear la preferencia en MP.
 *  2. Notificar que el carrito pasó a estado CHECKED_OUT al iniciar el pago.
 *  3. Notificar el resultado final (PAID o PAYMENT_FAILED) una vez que MP responde.
 *
 * NOTA PARA EL EQUIPO:
 *   El order-service debe exponer estos endpoints internos:
 *     GET  /internal/carts/{cartId}/summary   → devuelve CartSummaryInternal
 *     POST /internal/carts/{cartId}/checkout  → marca el carrito CHECKED_OUT
 *     POST /internal/carts/{cartId}/paid      → marca el carrito como pagado
 *     POST /internal/carts/{cartId}/payment-failed → libera el carrito para reintento
 */
@Service
@Slf4j
public class OrderServiceClient {

    private final WebClient webClient;

    public OrderServiceClient(WebClient orderServiceWebClient) {
        this.webClient = orderServiceWebClient;
    }

    /**
     * Obtiene el resumen del carrito: total, ítems y buyerId.
     * Lanza 404 si el carrito no existe, 503 si el order-service no responde.
     */
    public CartSummaryInternal getCartSummary(String cartId, String jwtToken) {
        try {
            CartSummaryInternal summary = webClient.get()
                    .uri("/internal/carts/{cartId}/summary", cartId)
                    .header("Authorization", "Bearer " + jwtToken)
                    .retrieve()
                    .onStatus(status -> status.value() == 404,
                            resp -> { throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                                    "Carrito no encontrado: " + cartId); })
                    .onStatus(HttpStatusCode::is4xxClientError,
                            resp -> { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "Error al consultar el carrito"); })
                    .bodyToMono(CartSummaryInternal.class)
                    .block();

            if (summary == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Carrito no encontrado: " + cartId);

            return summary;

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("[OrderServiceClient] Error al obtener carrito {}: {}", cartId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo contactar el order-service. Intenta de nuevo.");
        }
    }

    /**
     * Notifica al order-service que el carrito entró en proceso de pago (CHECKED_OUT).
     * Si falla, se loguea pero NO se lanza excepción — el pago puede continuar.
     */
    public void notifyCheckout(String cartId, String jwtToken) {
        try {
            webClient.post()
                    .uri("/internal/carts/{cartId}/checkout", cartId)
                    .header("Authorization", "Bearer " + jwtToken)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
            log.info("[OrderServiceClient] Carrito {} marcado como CHECKED_OUT", cartId);
        } catch (Exception e) {
            // No bloqueamos el flujo: el carrito se marcará en el siguiente intento o por timeout
            log.warn("[OrderServiceClient] No se pudo notificar checkout del carrito {}: {}", cartId, e.getMessage());
        }
    }

    /**
     * Notifica al order-service que el pago fue aprobado.
     * Se llama desde el handler del webhook de MercadoPago.
     */
    public void notifyPaymentApproved(String cartId, String paymentId) {
        try {
            webClient.post()
                    .uri("/internal/carts/{cartId}/paid", cartId)
                    .bodyValue(new PaymentResultNotification(paymentId, "APPROVED"))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
            log.info("[OrderServiceClient] Pago aprobado notificado para carrito {}", cartId);
        } catch (Exception e) {
            log.error("[OrderServiceClient] Error al notificar pago aprobado para carrito {}: {}",
                    cartId, e.getMessage());
        }
    }

    /**
     * Notifica al order-service que el pago falló o fue rechazado.
     * El order-service puede liberar el carrito para que el usuario reintente.
     */
    public void notifyPaymentFailed(String cartId, String paymentId, String reason) {
        try {
            webClient.post()
                    .uri("/internal/carts/{cartId}/payment-failed", cartId)
                    .bodyValue(new PaymentResultNotification(paymentId, reason))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
            log.info("[OrderServiceClient] Fallo de pago notificado para carrito {}", cartId);
        } catch (Exception e) {
            log.warn("[OrderServiceClient] Error al notificar fallo de pago para carrito {}: {}",
                    cartId, e.getMessage());
        }
    }

    // -------- DTOs internos --------

    /** Resumen mínimo del carrito que necesita el payment-service. */
    public record CartSummaryInternal(
            String cartId,
            String buyerId,
            BigDecimal total,
            String currency,
            List<ItemSummary> items
    ) {}

    public record ItemSummary(
            String ticketTypeName,
            int quantity,
            BigDecimal unitPrice
    ) {}

    /** Notificación que se envía al order-service con el resultado del pago. */
    public record PaymentResultNotification(
            String paymentId,
            String result
    ) {}
}
