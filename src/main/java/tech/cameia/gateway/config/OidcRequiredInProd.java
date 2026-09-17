package tech.cameia.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Guardia de arranque: con el perfil {@code prod} la firma OIDC no puede estar apagada
 * (REQ-OIDC-07, tercera cláusula).
 *
 * <p>Va aparte de {@link OidcConfig} porque tiene que existir justo cuando la firma está apagada,
 * que es cuando {@code OidcConfig} no se crea.
 */
@Component
@Profile("prod")
class OidcRequiredInProd {

    /**
     * @param signingEnabled valor efectivo de {@code gateway.oidc.signing-enabled}
     * @throws IllegalStateException si la firma está apagada; el contexto no arranca
     */
    OidcRequiredInProd(@Value("${gateway.oidc.signing-enabled:false}") boolean signingEnabled) {
        if (!signingEnabled) {
            throw new IllegalStateException(
                    "El perfil prod exige la firma OIDC de las llamadas salientes (AGENTS.md §6.3)");
        }
    }
}
