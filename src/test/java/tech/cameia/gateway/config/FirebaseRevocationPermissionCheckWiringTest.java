package tech.cameia.gateway.config;

import tech.cameia.gateway.GatewayApplication;
import tech.cameia.gateway.filter.OidcTokenSource;

import com.google.firebase.ErrorCode;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Comprueba, con el contexto de Spring completo, que la comprobación del permiso de lectura de usuarios está conectada al
 * arranque: con Firebase habilitado, la falta de permiso detiene el arranque y un usuario de prueba inexistente lo deja pasar.
 *
 * <p>Complementa a {@link FirebaseRevocationPermissionCheckTest}, que llama al método directamente: allí no se detectaría que
 * alguien quitara {@code @Component} o {@code @PostConstruct}. {@code FirebaseConfig} es el real, con las credenciales
 * ficticias del emulador (la dirección llega como propiedad de Spring), y solo {@link FirebaseAuth} se sustituye por un
 * simulacro que responde a la lectura del usuario de prueba.</p>
 */
class FirebaseRevocationPermissionCheckWiringTest {

    /** Simulacro que el contexto inyecta; cada prueba lo configura antes de arrancar. */
    private static FirebaseAuth probeAuth;

    /** {@code FirebaseApp} se guarda a nivel de proceso: hay que borrarlo para no contaminar otra prueba. */
    @AfterEach
    void deleteFirebaseApps() {
        FirebaseApp.getApps().forEach(FirebaseApp::delete);
    }

    /** Sin permiso de lectura, el contexto no arranca y el mensaje nombra el rol que falta. */
    @Test
    void startup_shouldFail_whenFirebaseDeniesPermission() throws Exception {
        probeAuth = mock(FirebaseAuth.class);
        when(probeAuth.getUser(FirebaseRevocationPermissionCheck.PROBE_UID)).thenThrow(
                new FirebaseAuthException(ErrorCode.PERMISSION_DENIED, "sin permiso", null, null, null));

        assertThatThrownBy(this::start)
                .hasStackTraceContaining("java.lang.IllegalStateException: "
                        + FirebaseRevocationPermissionCheck.MISSING_PERMISSION_MESSAGE)
                .hasRootCauseInstanceOf(FirebaseAuthException.class);
    }

    /** Con el permiso confirmado por «usuario no encontrado», el contexto arranca y la comprobación existe. */
    @Test
    void startup_shouldSucceedWithCheckBean_whenProbeUserNotFound() throws Exception {
        probeAuth = mock(FirebaseAuth.class);
        when(probeAuth.getUser(FirebaseRevocationPermissionCheck.PROBE_UID)).thenThrow(
                new FirebaseAuthException(ErrorCode.NOT_FOUND, "sin usuario", null, null, AuthErrorCode.USER_NOT_FOUND));

        try (ConfigurableApplicationContext context = start()) {
            assertThat(context.getBeansOfType(FirebaseRevocationPermissionCheck.class)).hasSize(1);
        }
    }

    /** Sustituye {@link FirebaseAuth} por el simulacro de la prueba en curso. */
    @TestConfiguration
    static class ProbeAuthConfig {

        @Bean
        @Primary
        FirebaseAuth probeFirebaseAuth() {
            return probeAuth;
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

    private ConfigurableApplicationContext start() {
        return new SpringApplication(GatewayApplication.class, ProbeAuthConfig.class, FakeOidcSourceConfig.class).run(
                "--server.port=0",
                "--gateway.firebase.enabled=true",
                "--FIREBASE_PROJECT_ID=demo-cameia",
                "--FIREBASE_AUTH_EMULATOR_HOST=localhost:9099",
                "--gateway.oidc.google-credentials.enabled=false",
                "--CAMEIA_CUENTAS_URL=http://localhost:9001",
                "--CAMEIA_PERFIL_URL=http://localhost:9002",
                "--CAMEIA_ENTREVISTA_URL=http://localhost:9003");
    }
}
