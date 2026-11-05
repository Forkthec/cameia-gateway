package tech.cameia.gateway.filter;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fuente real de tokens OIDC: los pide a Google con las credenciales por defecto del entorno
 * (REQ-OIDC-05). En Cloud Run salen del metadata server, con la service account del servicio: no
 * hay archivo de clave.
 *
 * <p>Se cachea <b>el objeto de credenciales por audience, nunca la cadena del token</b>
 * (REQ-OIDC-06, {@code AGENTS.md} §6.3): el objeto sabe cuándo venció su token y lo renueva. El
 * mapa no se purga porque tiene una entrada por ruta.
 */
public class GoogleIdTokenSource implements OidcTokenSource {

    private final IdTokenProvider provider;

    private final Map<String, IdTokenCredentials> credentialsByAudience = new ConcurrentHashMap<>();

    /**
     * @param credentials credenciales por defecto del entorno
     * @throws IllegalStateException si esas credenciales no pueden emitir tokens OIDC, como pasa con
     *                               las de usuario de {@code gcloud}; así falla el arranque y no la
     *                               primera petición
     */
    public GoogleIdTokenSource(GoogleCredentials credentials) {
        if (!(credentials instanceof IdTokenProvider idTokenProvider)) {
            throw new IllegalStateException("Las credenciales por defecto no pueden emitir tokens OIDC ("
                    + credentials.getClass().getSimpleName() + "): se necesita una service account");
        }
        this.provider = idTokenProvider;
    }

    /**
     * La obtención del token es bloqueante (red hacia Google), así que corre en
     * {@link Schedulers#boundedElastic()} (REQ-NF-OIDC-02).
     */
    @Override
    public Mono<String> tokenFor(String audience) {
        return Mono.fromCallable(() -> tokenValue(audience))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String tokenValue(String audience) throws IOException {
        IdTokenCredentials credentials = credentialsByAudience.computeIfAbsent(audience, this::credentialsFor);
        credentials.refreshIfExpired(); // la librería decide cuándo renovar, no este código
        return credentials.getIdToken().getTokenValue();
    }

    private IdTokenCredentials credentialsFor(String audience) {
        return IdTokenCredentials.newBuilder()
                .setIdTokenProvider(provider)
                .setTargetAudience(audience)
                .build();
    }
}
