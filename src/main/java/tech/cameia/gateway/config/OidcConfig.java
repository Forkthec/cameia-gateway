package tech.cameia.gateway.config;

import com.google.auth.oauth2.GoogleCredentials;

import tech.cameia.gateway.filter.GoogleIdTokenSource;
import tech.cameia.gateway.filter.OidcSigningGlobalFilter;
import tech.cameia.gateway.filter.OidcTokenSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Beans de la firma OIDC saliente ({@code AGENTS.md} §6.3). Con
 * {@code gateway.oidc.signing-enabled=false} no se crea ninguno: sin filtro y sin credenciales de
 * Google, que es lo que permite arrancar en local.
 *
 * <p>La propiedad no se llama {@code gateway.oidc.enabled} a propósito: con ese nombre la variable
 * {@code GATEWAY_OIDC_ENABLED} se enlazaría directamente a ella y podría apagar la firma en el
 * perfil {@code prod} (REQ-OIDC-07, plan §3.7).
 */
@Configuration
@ConditionalOnProperty(name = "gateway.oidc.signing-enabled", havingValue = "true")
public class OidcConfig {

    /**
     * Credenciales reales. Tienen su propio interruptor para apagarlas en pruebas, igual que
     * {@code FirebaseConfig} (REQ-NF-OIDC-03).
     *
     * @return la fuente que pide los tokens a Google
     * @throws IOException si el entorno no tiene credenciales por defecto
     */
    @Bean
    @ConditionalOnProperty(name = "gateway.oidc.google-credentials.enabled", havingValue = "true",
            matchIfMissing = true)
    public OidcTokenSource googleIdTokenSource() throws IOException {
        // Único sitio que llama a getApplicationDefault(): se revisa en el PR (plan §4)
        return new GoogleIdTokenSource(GoogleCredentials.getApplicationDefault());
    }

    /**
     * @param tokenSource fuente de tokens: la de Google, o la falsa en pruebas
     * @return el filtro que firma todas las rutas
     */
    @Bean
    public OidcSigningGlobalFilter oidcSigningGlobalFilter(OidcTokenSource tokenSource) {
        return new OidcSigningGlobalFilter(tokenSource);
    }
}
