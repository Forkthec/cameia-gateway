package tech.cameia.gateway.config;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica de dónde salen las credenciales de Firebase y que la variable del emulador impide el
 * arranque en un despliegue (REQ-EMU-01, REQ-EMU-02 y REQ-EMU-03).
 *
 * <p>No levanta Spring: usa un {@link MockEnvironment} para fijar {@code FIREBASE_AUTH_EMULATOR_HOST},
 * {@code K_SERVICE} y el perfil sin tocar el entorno del proceso. Las credenciales por defecto de
 * Google se sustituyen por unas de mentira con un token reconocible, de modo que el resultado no
 * depende de que la máquina que corre la prueba tenga o no una llave o {@code gcloud}.
 */
class FirebaseConfigTest {

    private static final String PROJECT_ID = "demo-cameia";
    private static final String DEFAULT_CREDENTIALS_TOKEN = "credenciales-por-defecto-simuladas";

    /** {@code FirebaseApp} se guarda a nivel de proceso: hay que borrarlo para no contaminar otra prueba. */
    @AfterEach
    void deleteFirebaseApps() {
        FirebaseApp.getApps().forEach(FirebaseApp::delete);
    }

    /** REQ-EMU-01: con el emulador y sin despliegue se usan credenciales ficticias, no las de Google. */
    @Test
    void emulatorHost_outsideDeployment_usesPlaceholderCredentials() throws IOException {
        RecordingFirebaseConfig config = configWith(new MockEnvironment()
                .withProperty(FirebaseConfig.EMULATOR_HOST_VARIABLE, "localhost:9099"));

        config.init();

        assertThat(config.defaultCredentialsRequests).isZero();
        assertThat(FirebaseApp.getInstance().getOptions().getProjectId()).isEqualTo(PROJECT_ID);
    }

    /** REQ-EMU-02: en Cloud Run (K_SERVICE) la variable impide el arranque y no se inicializa nada. */
    @Test
    void emulatorHost_onCloudRun_failsStartup() {
        RecordingFirebaseConfig config = configWith(new MockEnvironment()
                .withProperty(FirebaseConfig.EMULATOR_HOST_VARIABLE, "localhost:9099")
                .withProperty("K_SERVICE", "cameia-gateway"));

        assertThatThrownBy(config::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FirebaseConfig.EMULATOR_HOST_VARIABLE)
                .hasMessageContaining("despliegue");
        assertThat(FirebaseApp.getApps()).isEmpty();
    }

    /** REQ-EMU-02: con el perfil prod la variable también impide el arranque, aunque falte K_SERVICE. */
    @Test
    void emulatorHost_withProdProfile_failsStartup() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(FirebaseConfig.EMULATOR_HOST_VARIABLE, "localhost:9099");
        environment.setActiveProfiles("prod");

        assertThatThrownBy(configWith(environment)::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FirebaseConfig.EMULATOR_HOST_VARIABLE);
        assertThat(FirebaseApp.getApps()).isEmpty();
    }

    /** REQ-EMU-02: una variable definida pero vacía también cuenta; no deja pasar el despliegue. */
    @Test
    void emptyEmulatorHost_onCloudRun_failsStartup() {
        RecordingFirebaseConfig config = configWith(new MockEnvironment()
                .withProperty(FirebaseConfig.EMULATOR_HOST_VARIABLE, "")
                .withProperty("K_SERVICE", "cameia-gateway"));

        assertThatThrownBy(config::init).isInstanceOf(IllegalStateException.class);
        assertThat(FirebaseApp.getApps()).isEmpty();
    }

    /** REQ-EMU-03: sin la variable se piden las credenciales por defecto, como siempre. */
    @Test
    void noEmulatorHost_usesDefaultCredentials() throws IOException {
        RecordingFirebaseConfig config = configWith(new MockEnvironment());

        config.init();

        assertThat(config.defaultCredentialsRequests).isEqualTo(1);
    }

    /** REQ-EMU-03: una variable vacía fuera de un despliegue no activa el emulador. */
    @Test
    void emptyEmulatorHost_outsideDeployment_usesDefaultCredentials() throws IOException {
        RecordingFirebaseConfig config = configWith(new MockEnvironment()
                .withProperty(FirebaseConfig.EMULATOR_HOST_VARIABLE, ""));

        config.init();

        assertThat(config.defaultCredentialsRequests).isEqualTo(1);
    }

    /**
     * REQ-EMU-03: la ruta normal de producción (Cloud Run, sin la variable) no se ve afectada por la
     * guardia. Es la prueba de que la guardia no rompe el despliegue real.
     */
    @Test
    void noEmulatorHost_onCloudRun_startsWithDefaultCredentials() throws IOException {
        RecordingFirebaseConfig config = configWith(new MockEnvironment()
                .withProperty("K_SERVICE", "cameia-gateway"));

        config.init();

        assertThat(config.defaultCredentialsRequests).isEqualTo(1);
        assertThat(FirebaseApp.getApps()).hasSize(1);
    }

    private static RecordingFirebaseConfig configWith(Environment environment) {
        return new RecordingFirebaseConfig(environment);
    }

    /** {@link FirebaseConfig} que cuenta cuántas veces se piden las credenciales por defecto de Google. */
    private static final class RecordingFirebaseConfig extends FirebaseConfig {

        private int defaultCredentialsRequests;

        RecordingFirebaseConfig(Environment environment) {
            super(PROJECT_ID, environment);
        }

        @Override
        GoogleCredentials defaultCredentials() {
            defaultCredentialsRequests++;
            return GoogleCredentials.create(new AccessToken(DEFAULT_CREDENTIALS_TOKEN, null));
        }
    }
}
