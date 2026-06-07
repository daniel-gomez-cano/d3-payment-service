package co.empresa.payment_service.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Health check público
                .requestMatchers("/actuator/health").permitAll()
                // Métricas solo internas (en producción esto estaría detrás de red privada)
                .requestMatchers("/actuator/**").permitAll()
                // H2 console solo en desarrollo
                .requestMatchers("/h2-console/**").permitAll()
                // Webhook de MercadoPago: no lleva JWT de Keycloak.
                // La seguridad del webhook se valida con la firma HMAC (x-signature) en WebhookController.
                .requestMatchers("/api/payments/webhook/**").permitAll()
                // Endpoint temporal de prueba — sin JWT para abrir directo en navegador
                // ELIMINAR antes de desplegar en producción
                .requestMatchers("/sandbox/**").permitAll()
                // Todo lo demás requiere JWT de Keycloak
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            )
            // Necesario para que H2 console funcione en dev (frames)
            .headers(headers -> headers.frameOptions(fo -> fo.disable()));

        return http.build();
    }

    /**
     * Extrae los roles de Keycloak desde el claim realm_access.roles del JWT.
     * Mismo patrón que el order-service.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess == null || !realmAccess.containsKey("roles")) return List.of();

            @SuppressWarnings("unchecked")
            Collection<String> roles = (Collection<String>) realmAccess.get("roles");
            return roles.stream()
                    .map(role -> new SimpleGrantedAuthority(
                            role.startsWith("ROLE_") ? role : "ROLE_" + role)) // ← fix para que Spring Security reconozca los roles correctamente
                    .collect(Collectors.toList());
        });
        return converter;
    }
}
