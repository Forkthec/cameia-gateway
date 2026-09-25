package tech.cameia.gateway.config;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifica de dónde salen las credenciales de Firebase y que la variable del emulador impide el
 * arranque en un despliegue (REQ-EMU-01, REQ-EMU-02 y REQ-EMU-03 de CM-190) o fuera del entorno del
 * proceso (CM-188 REQ-EMC-08 a REQ-EMC-10).
 *
 * <p>No levanta Spring: usa un {@link MockEnvironment} para fijar {@code FIREBASE_AUTH_EMULATOR_HOST},
 * {@code K_SERVICE} y el perfil sin tocar el entorno del proceso. Una propiedad del
 * {@code MockEnvironment} equivale a un {@code -D} o un argumento; {@link #withProcessVariables} simula
 * una variable de entorno del sistema operativo, la única que ve el Admin SDK. Las credenciales por defecto de
 * Google se sustituyen por unas de mentira con un token reconocible, de modo que el resultado no
 * depende de que la máquina que corre la prueba tenga o no una llave o {@code gcloud}.
 */
@ExtendWith(OutputCaptureExtension.class)
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
        RecordingFirebaseConfig config = configWith(withProcessVariables(new MockEnvironment(),
                Map.of(FirebaseConfig.EMULATOR_HOST_VARIABLE, "localhost:9099")));

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

    /**
     * CM-188 REQ-EMC-09: la variable solo como propiedad de Spring ({@code -D}, argumento o YAML)
     * impide el arranque, porque el Admin SDK no la vería.
     */
    @Test
    void emulatorHost_onlyInSpring_failsStartup() {
        RecordingFirebaseConfig config = configWith(new MockEnvironment()
                .withProperty(FirebaseConfig.EMULATOR_HOST_VARIABLE, "localhost:9099"));

        assertThatThrownBy(config::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FirebaseConfig.EMULATOR_HOST_VARIABLE)
                .hasMessageContaining("variable de entorno del sistema operativo");
        assertThat(FirebaseApp.getApps()).isEmpty();
    }

    /**
     * CM-188 REQ-EMC-09: un {@code -D} que sobrescribe la variable de entorno con otro valor también
     * impide el arranque: Spring vería un host y el SDK otro.
     */
    @Test
    void emulatorHost_springValueDiffersFromProcess_failsStartup() {
        MockEnvironment environment = withProcessVariables(new MockEnvironment()
                        .withProperty(FirebaseConfig.EMULATOR_HOST_VARIABLE, "localhost:9099"),
                Map.of(FirebaseConfig.EMULATOR_HOST_VARIABLE, "cameia-firebase-emulator:9099"));

        assertThatThrownBy(configWith(environment)::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("variable de entorno del sistema operativo");
        assertThat(FirebaseApp.getApps()).isEmpty();
    }

    /** CM-188 REQ-EMC-10: la variable en el entorno del proceso también dispara la guardia de despliegue. */
    @Test
    void emulatorHost_inProcessEnvironment_onCloudRun_failsStartup() {
        MockEnvironment environment = withProcessVariables(new MockEnvironment(), Map.of(
                FirebaseConfig.EMULATOR_HOST_VARIABLE, "localhost:9099", "K_SERVICE", "cameia-gateway"));

        assertThatThrownBy(configWith(environment)::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("despliegue");
        assertThat(FirebaseApp.getApps()).isEmpty();
    }

    /**
     * CM-188 REQ-EMC-05: con el emulador, un ID de proyecto real impide el arranque y el mensaje
     * nombra las dos variables. El emulador nunca emitiría tokens para ese proyecto.
     */
    @Test
    void emulatorHost_withRealProjectId_failsStartup() {
        RecordingFirebaseConfig config = new RecordingFirebaseConfig("cameia-2d8b5", withProcessVariables(
                new MockEnvironment(), Map.of(FirebaseConfig.EMULATOR_HOST_VARIABLE, "localhost:9099")));

        assertThatThrownBy(config::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FIREBASE_PROJECT_ID")
                .hasMessageContaining(FirebaseConfig.EMULATOR_HOST_VARIABLE)
                .hasMessageContaining("cameia-2d8b5");
        assertThat(FirebaseApp.getApps()).isEmpty();
    }

    /** CM-188 REQ-EMC-05: sin el emulador, un ID de proyecto real es lo normal y arranca. */
    @Test
    void noEmulatorHost_withRealProjectId_startsWithDefaultCredentials() throws IOException {
        RecordingFirebaseConfig config = new RecordingFirebaseConfig("cameia-2d8b5", new MockEnvironment());

        config.init();

        assertThat(config.defaultCredentialsRequests).isEqualTo(1);
        assertThat(FirebaseApp.getInstance().getOptions().getProjectId()).isEqualTo("cameia-2d8b5");
    }

    /**
     * CM-188 REQ-EMC-06: al arrancar con el emulador, el log dice con qué ID de proyecto se verificarán
     * los tokens.
     *
     * @param output salida de consola capturada durante la prueba
     */
    @Test
    void emulatorHost_logsProjectId(CapturedOutput output) throws IOException {
        configWith(withProcessVariables(new MockEnvironment(),
                Map.of(FirebaseConfig.EMULATOR_HOST_VARIABLE, "localhost:9099"))).init();

        assertThat(output.getOut()).contains("del proyecto '" + PROJECT_ID + "'");
    }

    private static RecordingFirebaseConfig configWith(ConfigurableEnvironment environment) {
        return new RecordingFirebaseConfig(PROJECT_ID, environment);
    }

    /**
     * Añade la fuente {@code systemEnvironment}, la que en ejecución envuelve {@code System.getenv()}.
     *
     * @param environment entorno de prueba
     * @param variables   variables de entorno simuladas del sistema operativo
     * @return el mismo entorno, para encadenar
     */
    private static MockEnvironment withProcessVariables(MockEnvironment environment, Map<String, Object> variables) {
        environment.getPropertySources().addLast(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        return environment;
    }

    /** {@link FirebaseConfig} que cuenta cuántas veces se piden las credenciales por defecto de Google. */
    private static final class RecordingFirebaseConfig extends FirebaseConfig {

        private int defaultCredentialsRequests;

        RecordingFirebaseConfig(String projectId, ConfigurableEnvironment environment) {
            super(projectId, environment);
        }

        @Override
        GoogleCredentials defaultCredentials() {
            defaultCredentialsRequests++;
            return GoogleCredentials.create(new AccessToken(DEFAULT_CREDENTIALS_TOKEN, null));
        }
    }
}
