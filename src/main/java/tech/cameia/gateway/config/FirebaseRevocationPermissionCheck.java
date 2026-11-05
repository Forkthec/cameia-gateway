package tech.cameia.gateway.config;

import com.google.firebase.ErrorCode;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Comprueba al arrancar que la cuenta de servicio del Gateway puede leer usuarios de Firebase Auth.
 *
 * <p>Verificar si un token fue revocado obliga al Admin SDK a leer el usuario en cada petición. Sin el permiso de lectura, esa
 * lectura falla para todas las personas: es lo que dejó a todo el mundo sin acceso cuando se activó por primera vez. Esta
 * comprobación convierte ese defecto de despliegue en un arranque fallido: la revisión nueva no recibe tráfico y Cloud Run sigue
 * sirviendo la anterior.</p>
 *
 * <p>Corre en {@code @PostConstruct}, antes de que el servidor abra el puerto. Una caída pasajera de Firebase no impide arrancar:
 * solo la falta de permiso o un proyecto equivocado, que no se arreglan reintentando.</p>
 */
@Component
@ConditionalOnProperty(name = "gateway.firebase.enabled", havingValue = "true", matchIfMissing = true)
class FirebaseRevocationPermissionCheck {

    private static final Logger log = LoggerFactory.getLogger(FirebaseRevocationPermissionCheck.class);

    /** Identificador que no pertenece a nadie: la respuesta esperada es «usuario no encontrado». */
    static final String PROBE_UID = "cameia-gateway-permission-probe";

    /** Mensaje del fallo de arranque cuando la cuenta de servicio no puede leer usuarios. */
    static final String MISSING_PERMISSION_MESSAGE = "La cuenta de servicio del Gateway no puede leer usuarios de Firebase Auth: "
            + "sin ese permiso cada petición respondería 503. Conceda roles/firebaseauth.viewer";

    private final FirebaseAuth firebaseAuth;

    /**
     * Crea la comprobación.
     *
     * @param firebaseAuth cliente del Admin SDK con el que se lee el usuario de prueba
     */
    FirebaseRevocationPermissionCheck(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    /**
     * Lee un usuario inexistente y decide según la respuesta.
     *
     * @throws IllegalStateException si Firebase niega el permiso, no reconoce la credencial o no encuentra el proyecto
     */
    @PostConstruct
    void check() {
        try {
            firebaseAuth.getUser(PROBE_UID);
            log.info("Permiso de lectura de usuarios de Firebase confirmado");
        } catch (FirebaseAuthException error) {
            if (AuthErrorCode.USER_NOT_FOUND.equals(error.getAuthErrorCode())) {
                log.info("Permiso de lectura de usuarios de Firebase confirmado");
                return;
            }
            if (isMisconfiguration(error)) {
                throw new IllegalStateException(MISSING_PERMISSION_MESSAGE, error);
            }
            // Una caída pasajera no debe impedir que el servicio escale
            log.warn("No se pudo comprobar el permiso de lectura de usuarios de Firebase [errorCode={}]", error.getErrorCode());
        }
    }

    /**
     * Distingue un defecto de despliegue de una caída pasajera.
     *
     * @param error fallo que devolvió Firebase al leer el usuario de prueba
     * @return {@code true} si falta el permiso, la credencial no se reconoce o el proyecto no existe
     */
    private static boolean isMisconfiguration(FirebaseAuthException error) {
        return error.getErrorCode() == ErrorCode.PERMISSION_DENIED
                || error.getErrorCode() == ErrorCode.UNAUTHENTICATED
                || AuthErrorCode.CONFIGURATION_NOT_FOUND.equals(error.getAuthErrorCode());
    }
}
