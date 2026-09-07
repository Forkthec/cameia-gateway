package co.edu.unicauca.cameia.gateway.config;

import co.edu.unicauca.cameia.gateway.GatewayApplication;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica que el gateway falla el arranque si faltan variables obligatorias (REQ-06).
 * No usa el perfil 'test' para que FirebaseConfig esté activo y la validación de propiedades corra.
 */
class GatewayStartupTest {

    @Test
    void sinFirebaseProjectId_elArranqueFalla() {
        // Sin FIREBASE_PROJECT_ID, Spring no puede resolver ${FIREBASE_PROJECT_ID}
        // y lanza una excepción antes de completar el contexto.
        assertThatThrownBy(() ->
                new SpringApplication(GatewayApplication.class).run(
                        "--server.port=0",
                        "--CAMEIA_CUENTAS_URL=http://localhost:9001",
                        "--CAMEIA_PERFIL_URL=http://localhost:9002",
                        "--CAMEIA_ENTREVISTA_URL=http://localhost:9003",
                        "--GOOGLE_APPLICATION_CREDENTIALS=/nonexistent/creds.json"
                        // FIREBASE_PROJECT_ID deliberadamente ausente
                )
        ).isInstanceOf(Exception.class);
    }
}
