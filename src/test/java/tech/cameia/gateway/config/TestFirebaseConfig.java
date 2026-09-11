package tech.cameia.gateway.config;

import com.google.firebase.auth.FirebaseAuth;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.Mockito.mock;

/**
 * Reemplaza FirebaseAuth por un mock en todos los tests.
 * FirebaseConfig está desactivado por gateway.firebase.enabled=false en application-test.yml,
 * así que este bean es el único FirebaseAuth en el contexto de test.
 */
@TestConfiguration
public class TestFirebaseConfig {

    @Bean
    @Primary
    public FirebaseAuth firebaseAuth() {
        return mock(FirebaseAuth.class);
    }
}
