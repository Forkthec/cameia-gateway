package tech.cameia.gateway.config;

import tech.cameia.gateway.GatewayApplication;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Arranca la aplicación completa con valores de tiempo y comprueba que el Gateway no arranca con
 * un valor inválido o vacío, conserva el valor por omisión cuando la variable no está definida y
 * atiende una petición real con el valor máximo permitido.
 */
class RouteTimeoutStartupTest {

    @Test
    @DisplayName("Con GATEWAY_INTERVIEWS_TIMEOUT_MS=0 el contexto no arranca y el mensaje nombra la variable")
    void context_shouldFail_whenInterviewsTimeoutInvalid() {
        assertThatThrownBy(() -> start("--GATEWAY_INTERVIEWS_TIMEOUT_MS=0"))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GATEWAY_INTERVIEWS_TIMEOUT_MS debe ser un entero entre 1 y 600000 "
                        + "(milisegundos); valor recibido: '0'");
    }

    @Test
    @DisplayName("Con GATEWAY_TIMEOUT_MS=abc el contexto no arranca con el mensaje claro, no el técnico de Spring")
    void context_shouldFail_whenGlobalTimeoutInvalid() {
        assertThatThrownBy(() -> start("--GATEWAY_TIMEOUT_MS=abc"))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GATEWAY_TIMEOUT_MS debe ser un entero entre 1 y 600000 (milisegundos); "
                        + "valor recibido: 'abc'");
    }

    @Test
    @DisplayName("Con GATEWAY_INTERVIEWS_TIMEOUT_MS=900 el contexto arranca y la ruta lo usa")
    void context_shouldStart_whenInterviewsTimeoutValid() {
        try (ConfigurableApplicationContext context = start("--GATEWAY_INTERVIEWS_TIMEOUT_MS=900")) {
            assertThat(interviewsMetadata(context)).isEqualTo("900");
        }
    }

    @Test
    @DisplayName("Con la variable definida y vacía el contexto no arranca: la ruta recibiría un texto vacío")
    void context_shouldFail_whenInterviewsTimeoutEmpty() {
        assertThatThrownBy(() -> start("--GATEWAY_INTERVIEWS_TIMEOUT_MS="))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GATEWAY_INTERVIEWS_TIMEOUT_MS debe ser un entero entre 1 y 600000 "
                        + "(milisegundos); valor recibido: ''");
    }

    @Test
    @DisplayName("Con GATEWAY_TIMEOUT_MS definida y vacía el contexto no arranca con el mensaje claro")
    void context_shouldFail_whenGlobalTimeoutEmpty() {
        assertThatThrownBy(() -> start("--GATEWAY_TIMEOUT_MS="))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GATEWAY_TIMEOUT_MS debe ser un entero entre 1 y 600000 (milisegundos); "
                        + "valor recibido: ''");
    }

    @Test
    @DisplayName("Con GATEWAY_TIMEOUT_MS en el mayor long el contexto no arranca: desbordaría el reloj de Reactor")
    void context_shouldFail_whenGlobalTimeoutIsLongMax() {
        assertThatThrownBy(() -> start("--GATEWAY_TIMEOUT_MS=9223372036854775807"))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GATEWAY_TIMEOUT_MS debe ser un entero entre 1 y 600000 (milisegundos); "
                        + "valor recibido: '9223372036854775807'");
    }

    @Test
    @DisplayName("Con GATEWAY_TIMEOUT_MS=600000, el máximo, el Gateway atiende una petición real")
    void context_shouldServeRequests_whenGlobalTimeoutIsMaximum() throws IOException {
        try (MockWebServer accounts = new MockWebServer()) {
            accounts.enqueue(new MockResponse().setResponseCode(201).setBody("creada"));
            accounts.start();

            try (ConfigurableApplicationContext context = start(
                    "--GATEWAY_TIMEOUT_MS=600000",
                    "--CAMEIA_CUENTAS_URL=http://localhost:" + accounts.getPort())) {
                String port = context.getEnvironment().getProperty("local.server.port");

                WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                        .post().uri("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{}")
                        .exchange()
                        .expectStatus().isCreated()
                        .expectBody(String.class).isEqualTo("creada");
            }
        }
    }

    @Test
    @DisplayName("Sin la variable definida la ruta conserva 120000")
    void context_shouldKeepDefault_whenInterviewsTimeoutUndefined() {
        try (ConfigurableApplicationContext context = start()) {
            assertThat(interviewsMetadata(context)).isEqualTo("120000");
        }
    }

    private static Object interviewsMetadata(ConfigurableApplicationContext context) {
        Route route = context.getBean(RouteLocator.class).getRoutes()
                .filter(candidate -> "cameia-entrevista".equals(candidate.getId()))
                .blockFirst();
        return route.getMetadata().get("response-timeout");
    }

    private static String nameOf(String argument) {
        int equals = argument.indexOf('=');
        return equals < 0 ? argument : argument.substring(0, equals);
    }

    private static ConfigurableApplicationContext start(String... extraArgs) {
        String[] baseArgs = {
                "--server.port=0",
                "--gateway.firebase.enabled=false",
                "--gateway.oidc.google-credentials.enabled=false",
                "--CAMEIA_CUENTAS_URL=http://localhost:9001",
                "--CAMEIA_PERFIL_URL=http://localhost:9002",
                "--CAMEIA_ENTREVISTA_URL=http://localhost:9003"
        };
        // Un argumento repetido se concatena con coma: el extra reemplaza al base del mismo nombre.
        String[] args = Stream.concat(
                        Arrays.stream(baseArgs).filter(base -> Arrays.stream(extraArgs)
                                .noneMatch(extra -> nameOf(extra).equals(nameOf(base)))),
                        Arrays.stream(extraArgs))
                .toArray(String[]::new);
        return new SpringApplication(GatewayApplication.class, TestFirebaseConfig.class).run(args);
    }
}
