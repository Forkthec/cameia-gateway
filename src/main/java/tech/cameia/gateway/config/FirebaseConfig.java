package tech.cameia.gateway.config;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.util.Map;

/**
 * Inicializa Firebase Admin SDK al arrancar.
 * Desactivado en el perfil de tests (gateway.firebase.enabled=false)
 * para inyectar un FirebaseAuth simulado sin necesidad de credenciales reales.
 * Si las credenciales son inválidas o falta FIREBASE_PROJECT_ID,
 * la aplicación falla el arranque (fail-fast, REQ-06).
 *
 * <p><b>Emulador de Firebase Auth.</b> Con {@code FIREBASE_AUTH_EMULATOR_HOST} definida, el Admin SDK
 * habla con el emulador local en vez de con Google: detecta la variable por sí solo, leyéndola del
 * entorno del proceso. Aun así {@link FirebaseOptions} exige unas credenciales no nulas, así que
 * aquí se le entregan unas ficticias y no se piden las de Google, que sin llave de service account
 * fallarían. El emulador emite tokens sin firma, y el SDK los acepta mientras la variable esté
 * definida. Por eso, en un despliegue (Cloud Run, o perfil {@code prod}) la variable impide el
 * arranque: el Gateway no puede ignorarla, porque el SDK la lee directamente del entorno.
 *
 * <p><b>Una sola fuente para el modo emulador</b> (CM-188 REQ-EMC-08, REQ-EMC-09). El SDK solo ve las
 * variables de entorno del proceso; Spring ve además {@code -D}, argumentos {@code --} y YAML. Por eso
 * el modo emulador se decide con la fuente {@code systemEnvironment} de Spring, que es
 * {@code System.getenv()}, y el arranque falla si la variable solo existe en las otras fuentes: el
 * Gateway entregaría credenciales ficticias mientras el SDK verifica contra Google.
 */
@Configuration
@ConditionalOnProperty(name = "gateway.firebase.enabled", havingValue = "true", matchIfMissing = true)
public class FirebaseConfig {

    static final String EMULATOR_HOST_VARIABLE = "FIREBASE_AUTH_EMULATOR_HOST";
    private static final String CLOUD_RUN_SERVICE_VARIABLE = "K_SERVICE";
    private static final String DEPLOY_PROFILE = "prod";
    /** Prefijo que el emulador exige a su ID de proyecto (CM-189). */
    private static final String DEMO_PROJECT_PREFIX = "demo-";

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    private final String firebaseProjectId;
    private final ConfigurableEnvironment environment;

    FirebaseConfig(@Value("${FIREBASE_PROJECT_ID}") String firebaseProjectId, ConfigurableEnvironment environment) {
        this.firebaseProjectId = firebaseProjectId;
        this.environment = environment;
    }

    @PostConstruct
    void init() throws IOException {
        rejectEmulatorInDeployment();
        rejectEmulatorOutsideProcessEnvironment();
        rejectNonDemoProjectInEmulator();
        if (FirebaseApp.getApps().isEmpty()) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(resolveCredentials())
                    .setProjectId(firebaseProjectId)
                    .build();
            FirebaseApp.initializeApp(options);
        }
    }

    @Bean
    FirebaseAuth firebaseAuth() {
        return FirebaseAuth.getInstance();
    }

    /**
     * Falla el arranque si la variable del emulador está definida en un despliegue. Cuenta como
     * definida aunque su valor esté vacío, y venga de Spring o del entorno del proceso: es el criterio
     * más conservador (REQ-EMU-02 de CM-190, CM-188 REQ-EMC-10).
     *
     * @throws IllegalStateException si la variable existe y hay {@code K_SERVICE} o el perfil {@code prod}
     */
    private void rejectEmulatorInDeployment() {
        boolean defined = environment.containsProperty(EMULATOR_HOST_VARIABLE)
                || processVariable(EMULATOR_HOST_VARIABLE) != null;
        if (defined && isDeployment()) {
            throw new IllegalStateException(EMULATOR_HOST_VARIABLE + " apunta al emulador de Firebase "
                    + "Auth, que emite tokens sin firma, y no puede estar definida en un despliegue ("
                    + CLOUD_RUN_SERVICE_VARIABLE + " definida o perfil '" + DEPLOY_PROFILE + "' activo)");
        }
    }

    /**
     * Falla el arranque si la variable del emulador tiene valor en Spring pero no en el entorno del
     * proceso, o si allí tiene otro valor (CM-188 REQ-EMC-09). Ocurre al definirla con {@code -D}, con
     * un argumento {@code --} o en un YAML: el SDK no la vería y rechazaría todo token del emulador
     * sin explicar por qué.
     *
     * @throws IllegalStateException si el valor de Spring no es el que ve el Admin SDK
     */
    private void rejectEmulatorOutsideProcessEnvironment() {
        String springValue = environment.getProperty(EMULATOR_HOST_VARIABLE);
        if (springValue != null && !springValue.isBlank()
                && !springValue.equals(processVariable(EMULATOR_HOST_VARIABLE))) {
            throw new IllegalStateException(EMULATOR_HOST_VARIABLE + " debe definirse como variable de "
                    + "entorno del sistema operativo: el Admin SDK de Firebase no lee propiedades -D, "
                    + "argumentos -- ni archivos YAML. Se recomienda arrancar con docker compose (README.md)");
        }
    }

    /**
     * Falla el arranque si, con el emulador, el ID de proyecto no es de demostración (CM-188
     * REQ-EMC-05). El emulador solo arranca con IDs {@code demo-*}, y sus tokens llevan ese ID en
     * {@code aud} e {@code iss}: con el ID de un proyecto real, todo token daría {@code 401} sin
     * explicación.
     *
     * @throws IllegalStateException si el modo emulador está activo y el ID no empieza por {@code demo-}
     */
    private void rejectNonDemoProjectInEmulator() {
        if (isEmulatorMode() && !firebaseProjectId.startsWith(DEMO_PROJECT_PREFIX)) {
            throw new IllegalStateException("Con " + EMULATOR_HOST_VARIABLE + " definida, FIREBASE_PROJECT_ID "
                    + "debe empezar por '" + DEMO_PROJECT_PREFIX + "' y coincidir con el del emulador "
                    + "(valor recibido: '" + firebaseProjectId + "')");
        }
    }

    /**
     * @return {@code true} si la variable del emulador tiene valor en el entorno del proceso, que es
     *         cuando el Admin SDK habla con el emulador (CM-188 REQ-EMC-08)
     */
    private boolean isEmulatorMode() {
        String emulatorHost = processVariable(EMULATOR_HOST_VARIABLE);
        return emulatorHost != null && !emulatorHost.isBlank();
    }

    /**
     * Lee una variable de entorno del proceso con su nombre exacto, como hace el Admin SDK.
     *
     * <p>Sale de la fuente {@code systemEnvironment} de Spring, que en ejecución es
     * {@code System.getenv()}; así una prueba puede sustituirla sin tocar el entorno real. Se lee el
     * mapa en bruto porque la búsqueda de Spring es flexible con mayúsculas y separadores, y el SDK no.
     *
     * @param name nombre exacto de la variable
     * @return su valor, o {@code null} si no existe
     */
    private String processVariable(String name) {
        PropertySource<?> source = environment.getPropertySources()
                .get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (source == null || !(source.getSource() instanceof Map<?, ?> variables)) {
            return null;
        }
        Object value = variables.get(name);
        return value == null ? null : value.toString();
    }

    /**
     * @return {@code true} si existe {@code K_SERVICE} (Cloud Run la inyecta) o está activo el perfil {@code prod}
     */
    private boolean isDeployment() {
        return environment.containsProperty(CLOUD_RUN_SERVICE_VARIABLE)
                || environment.matchesProfiles(DEPLOY_PROFILE);
    }

    /**
     * @return credenciales ficticias si la variable del emulador tiene valor; si no, las de Google
     * @throws IOException si no hay credenciales por defecto y no se usa el emulador
     */
    private GoogleCredentials resolveCredentials() throws IOException {
        if (!isEmulatorMode()) {
            return defaultCredentials();
        }
        // CM-188 REQ-EMC-06: el ID de proyecto se anuncia para compararlo con el emulador y con Cuentas
        log.warn("{} definida ({}): Firebase usa el emulador con credenciales ficticias y acepta "
                + "tokens sin firma, del proyecto '{}'. Solo para desarrollo local.",
                EMULATOR_HOST_VARIABLE, processVariable(EMULATOR_HOST_VARIABLE), firebaseProjectId);
        return GoogleCredentials.create(new AccessToken("emulador-sin-credenciales", null));
    }

    /**
     * Credenciales por defecto de Google. Es un método aparte para poder sustituirlas en una prueba
     * y comprobar de dónde salen las credenciales sin depender de la máquina que la ejecuta.
     *
     * @return las credenciales por defecto del entorno (llave de service account o servidor de metadatos)
     * @throws IOException si el entorno no tiene ninguna
     */
    GoogleCredentials defaultCredentials() throws IOException {
        return GoogleCredentials.getApplicationDefault();
    }
}
