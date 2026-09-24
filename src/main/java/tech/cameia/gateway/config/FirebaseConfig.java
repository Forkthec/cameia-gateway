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
import org.springframework.core.env.Environment;

import java.io.IOException;

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
 */
@Configuration
@ConditionalOnProperty(name = "gateway.firebase.enabled", havingValue = "true", matchIfMissing = true)
public class FirebaseConfig {

    static final String EMULATOR_HOST_VARIABLE = "FIREBASE_AUTH_EMULATOR_HOST";
    private static final String CLOUD_RUN_SERVICE_VARIABLE = "K_SERVICE";
    private static final String DEPLOY_PROFILE = "prod";

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    private final String firebaseProjectId;
    private final Environment environment;

    FirebaseConfig(@Value("${FIREBASE_PROJECT_ID}") String firebaseProjectId, Environment environment) {
        this.firebaseProjectId = firebaseProjectId;
        this.environment = environment;
    }

    @PostConstruct
    void init() throws IOException {
        rejectEmulatorInDeployment();
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
     * definida aunque su valor esté vacío: es el criterio más conservador.
     *
     * @throws IllegalStateException si la variable existe y hay {@code K_SERVICE} o el perfil {@code prod}
     */
    private void rejectEmulatorInDeployment() {
        if (environment.containsProperty(EMULATOR_HOST_VARIABLE) && isDeployment()) {
            throw new IllegalStateException(EMULATOR_HOST_VARIABLE + " apunta al emulador de Firebase "
                    + "Auth, que emite tokens sin firma, y no puede estar definida en un despliegue ("
                    + CLOUD_RUN_SERVICE_VARIABLE + " definida o perfil '" + DEPLOY_PROFILE + "' activo)");
        }
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
        String emulatorHost = environment.getProperty(EMULATOR_HOST_VARIABLE);
        if (emulatorHost == null || emulatorHost.isBlank()) {
            return defaultCredentials();
        }
        log.warn("{} definida ({}): Firebase usa el emulador con credenciales ficticias y acepta "
                + "tokens sin firma. Solo para desarrollo local.", EMULATOR_HOST_VARIABLE, emulatorHost);
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
