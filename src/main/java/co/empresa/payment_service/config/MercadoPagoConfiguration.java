package co.empresa.payment_service.config;

import com.mercadopago.MercadoPagoConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Inicializa el SDK de MercadoPago con el Access Token de la variable de entorno.
 *
 * El token NUNCA debe estar en el código fuente.
 * Se lee siempre de MP_ACCESS_TOKEN (ver env.example).
 *
 * Para obtener tu Access Token sandbox:
 *   1. Crea una cuenta en https://www.mercadopago.com.co
 *   2. Ve a https://www.mercadopago.com.co/developers/panel
 *   3. Crea una aplicación y copia el "Access Token de prueba"
 */
@Configuration
@Slf4j
public class MercadoPagoConfiguration {

    @Value("${mercadopago.access-token}")
    private String accessToken;

    @Value("${mercadopago.sandbox:true}")
    private boolean sandbox;

    @PostConstruct
    public void init() {
        MercadoPagoConfig.setAccessToken(accessToken);
        log.info("[MercadoPago] SDK inicializado — modo sandbox: {}", sandbox);
    }
}
