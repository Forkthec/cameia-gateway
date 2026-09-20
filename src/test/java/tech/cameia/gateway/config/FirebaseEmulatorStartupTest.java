package tech.cameia.gateway.config;

import tech.cameia.gateway.GatewayApplication;
import tech.cameia.gateway.filter.OidcTokenSource;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica, con el contexto de Spring completo y el {@link FirebaseConfig} real activo, que la
 * variable del emulador de Firebase Auth solo deja arrancar fuera de un despliegue (REQ-EMU-01 y
 * REQ-EMU-02).
 *
 * <p>Complementa a {@link FirebaseConfigTest}: allí se prueba la clase sola; aquí, que la guardia
 * corre de verdad al arrancar. {@code FIREBASE_AUTH_EMULATOR_HOST} y {@code K_SERVICE} se pasan como
 * propiedades, que {@code Environment} resuelve igual que las variables de entorno que inyecta el
 * despliegue. No se usa el mock de Firebase: la prueba necesita el {@code FirebaseConfig} de verdad.
 */
class FirebaseEmulatorStartupTest {

    /** {@code FirebaseApp} se guarda a nivel de proceso: hay que borrarlo para no contaminar otra prueba. */
    @AfterEach
    void deleteFirebaseApps() {
        FirebaseApp.getApps().forEach(FirebaseApp::delete);
    }

    /** REQ-EMU-02: con la variable del emulador y K_SERVICE, el contexto no arranca. */
    @Test
    void emulatorHost_onCloudRun_failsStartup() {
        assertThatThrownBy(() -> start("--FIREBASE_AUTH_EMULATOR_HOST=localhost:9099",
                "--K_SERVICE=cameia-gateway"))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FIREBASE_AUTH_EMULATOR_HOST")
                .hasMessageContaining("despliegue");
    }

    /** REQ-EMU-02: con la variable del emulador y el perfil prod, el contexto tampoco arranca. */
    @Test
    void emulatorHost_withProdProfile_failsStartup() {
        assertThatThrownBy(() -> start("--FIREBASE_AUTH_EMULATOR_HOST=localhost:9099",
                "--spring.profiles.active=prod"))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FIREBASE_AUTH_EMULATOR_HOST");
    }

    /**
     * REQ-EMU-01: con la variable y sin despliegue, el contexto arranca sin llave de service account
     * ni {@code gcloud}, y Firebase queda inicializado con el ID de proyecto configurado.
     */
    @Test
    void emulatorHost_outsideDeployment_startsWithoutGoogleCredentials() {
        try (ConfigurableApplicationContext context = start("--FIREBASE_AUTH_EMULATOR_HOST=localhost:9099")) {
            assertThat(context.getBean(FirebaseAuth.class)).isNotNull();
            assertThat(FirebaseApp.getInstance().getOptions().getProjectId()).isEqualTo("demo-cameia");
        }
    }

    /** Fuente de tokens falsa: la real exige credenciales de Google. */
    @TestConfiguration
    static class FakeOidcSourceConfig {

        @Bean
        OidcTokenSource fakeTokenSource() {
            return audience -> Mono.just("oidc-falso");
        }
    }

    private ConfigurableApplicationContext start(String... extraArgs) {
        String[] baseArgs = {
                "--server.port=0",
                "--gateway.firebase.enabled=true",
                "--FIREBASE_PROJECT_ID=demo-cameia",
                "--gateway.oidc.google-credentials.enabled=false",
                "--CAMEIA_CUENTAS_URL=http://localhost:9001",
                "--CAMEIA_PERFIL_URL=http://localhost:9002",
                "--CAMEIA_ENTREVISTA_URL=http://localhost:9003"
        };
        String[] args = Stream.concat(Arrays.stream(baseArgs), Arrays.stream(extraArgs))
                .toArray(String[]::new);
        return new SpringApplication(GatewayApplication.class, FakeOidcSourceConfig.class).run(args);
    }
}
