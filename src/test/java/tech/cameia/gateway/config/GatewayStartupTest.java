package tech.cameia.gateway.config;

import tech.cameia.gateway.GatewayApplication;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica que el gateway falla el arranque si faltan variables obligatorias (REQ-06) o si el perfil
 * de desarrollo está activo dentro de Cloud Run (CM-14 REQ-REG-02).
 * La prueba de REQ-06 no usa el perfil 'test' para que FirebaseConfig esté activo y la validación
 * de propiedades corra; las de CM-14 usan el mock de Firebase para aislar la guardia.
 */
class GatewayStartupTest {

    @Test
    void missingFirebaseProjectId_startupFails() {
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

    /**
     * CM-14 REQ-REG-02, prueba 5 del plan: con el perfil {@code local} y {@code K_SERVICE} definida
     * el contexto no arranca.
     *
     * <p>Firebase se reemplaza por el mock para que el único motivo posible del fallo sea la
     * guardia; por eso se afirma sobre el mensaje de la causa raíz y no sobre cualquier excepción.
     * {@code K_SERVICE} se pasa como propiedad: {@code Environment} la resuelve igual que la
     * variable de entorno que inyecta Cloud Run.
     */
    @Test
    void devRoutes_onCloudRun_failsStartup() {
        assertThatThrownBy(() -> startWithMockFirebase("--spring.profiles.active=test,local",
                "--K_SERVICE=cameia-gateway"))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no puede estar activo en Cloud Run");
    }

    /**
     * CM-14 REQ-REG-02: {@code application.yml} no activa el perfil {@code local} por su cuenta.
     *
     * <p>No fija {@code spring.profiles.active}: el perfil activo sale solo de
     * {@code SPRING_PROFILES_ACTIVE}, que {@code docker compose run --rm verify} define como
     * {@code test}.
     */
    @Test
    void startupWithoutProfileArgument_doesNotActivateLocalProfile() {
        try (ConfigurableApplicationContext context = startWithMockFirebase()) {
            assertThat(context.getEnvironment().matchesProfiles("local")).isFalse();
        }
    }

    private ConfigurableApplicationContext startWithMockFirebase(String... extraArgs) {
        String[] baseArgs = {
                "--server.port=0",
                "--gateway.firebase.enabled=false",
                "--CAMEIA_CUENTAS_URL=http://localhost:9001",
                "--CAMEIA_PERFIL_URL=http://localhost:9002",
                "--CAMEIA_ENTREVISTA_URL=http://localhost:9003"
        };
        String[] args = Stream.concat(Arrays.stream(baseArgs), Arrays.stream(extraArgs))
                .toArray(String[]::new);
        return new SpringApplication(GatewayApplication.class, TestFirebaseConfig.class).run(args);
    }
}
