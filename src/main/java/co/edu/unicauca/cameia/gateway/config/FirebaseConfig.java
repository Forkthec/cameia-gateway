package co.edu.unicauca.cameia.gateway.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Inicializa Firebase Admin SDK al arrancar.
 * Desactivado en el perfil de tests (gateway.firebase.enabled=false)
 * para inyectar un FirebaseAuth simulado sin necesidad de credenciales reales.
 * Si las credenciales son inválidas o falta FIREBASE_PROJECT_ID,
 * la aplicación falla el arranque (fail-fast, REQ-06).
 */
@Configuration
@ConditionalOnProperty(name = "gateway.firebase.enabled", havingValue = "true", matchIfMissing = true)
public class FirebaseConfig {

    @Value("${FIREBASE_PROJECT_ID}")
    private String firebaseProjectId;

    @PostConstruct
    void init() throws IOException {
        if (FirebaseApp.getApps().isEmpty()) {
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .setProjectId(firebaseProjectId)
                    .build();
            FirebaseApp.initializeApp(options);
        }
    }

    @Bean
    FirebaseAuth firebaseAuth() {
        return FirebaseAuth.getInstance();
    }
}
