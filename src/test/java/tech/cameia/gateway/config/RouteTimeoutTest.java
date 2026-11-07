package tech.cameia.gateway.config;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Comprueba en ejecución que el tiempo máximo de respuesta es distinto por ruta: la de entrevistas
 * espera 900 ms y las demás 300 ms. Es la misma lógica que los 120 s y los 30 s reales, a escala
 * 1:100, para que la prueba no espere minutos.
 *
 * <p>Los destinos son dos {@link MockWebServer} que demoran sus encabezados: el de perfil
 * representa a las "demás rutas" y el de entrevistas a la ruta con tiempo propio. El límite exacto
 * no se prueba (depende del reloj del sistema y sería inestable).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = TestFirebaseConfig.class
)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RouteTimeoutTest {

    private static final String TIMEOUT_BODY =
            "{\"code\":\"GATEWAY_TIMEOUT\",\"message\":\"El servicio destino no respondió a tiempo\"}";

    private static MockWebServer interviews;
    private static MockWebServer profiles;

    @LocalServerPort
    int port;

    @Autowired
    FirebaseAuth firebaseAuth;

    WebTestClient client;

    @BeforeAll
    static void startDownstreams() throws IOException {
        interviews = new MockWebServer();
        interviews.start();
        profiles = new MockWebServer();
        profiles.start();
    }

    @AfterAll
    static void stopDownstreams() throws IOException {
        interviews.shutdown();
        profiles.shutdown();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("CAMEIA_ENTREVISTA_URL", () -> "http://localhost:" + interviews.getPort());
        registry.add("CAMEIA_PERFIL_URL", () -> "http://localhost:" + profiles.getPort());
        registry.add("CAMEIA_CUENTAS_URL", () -> "http://localhost:9");
        registry.add("GATEWAY_TIMEOUT_MS", () -> "300");
        registry.add("GATEWAY_INTERVIEWS_TIMEOUT_MS", () -> "900");
    }

    @BeforeEach
    void authenticate() throws Exception {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(java.time.Duration.ofSeconds(10))
                .build();
        FirebaseToken token = mock(FirebaseToken.class);
        when(token.getUid()).thenReturn("uid-timeout");
        when(token.getClaims()).thenReturn(Map.of());
        when(firebaseAuth.verifyIdToken("token-timeout", true)).thenReturn(token);
    }

    @Test
    @Order(1)
    @DisplayName("Entrevistas: un destino que responde a los 500 ms pasa íntegro: estado, encabezado y cuerpo")
    void interviews_shouldReturn200_whenDownstreamTakes500ms() {
        interviews.enqueue(slow(500, "respuesta-entrevista"));

        client.get().uri("/api/v1/interviews/sessions/1")
                .header("Authorization", "Bearer token-timeout")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Sample", "valor-de-ejemplo")
                .expectBody(String.class).isEqualTo("respuesta-entrevista");
    }

    @Test
    @Order(2)
    @DisplayName("Otra ruta: un destino que responde a los 500 ms da 504")
    void otherRoute_shouldReturn504_whenDownstreamTakes500ms() {
        profiles.enqueue(slow(500, "tarde"));

        client.get().uri("/api/v1/profiles/me")
                .header("Authorization", "Bearer token-timeout")
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectBody().jsonPath("$.code").isEqualTo("GATEWAY_TIMEOUT");
    }

    @Test
    @Order(3)
    @DisplayName("Entrevistas: un destino que responde a los 1000 ms da 504")
    void interviews_shouldReturn504_whenDownstreamTakes1000ms() {
        interviews.enqueue(slow(1000, "tarde"));

        client.get().uri("/api/v1/interviews/sessions/1")
                .header("Authorization", "Bearer token-timeout")
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectBody().jsonPath("$.code").isEqualTo("GATEWAY_TIMEOUT");
    }

    @Test
    @Order(4)
    @DisplayName("Otra ruta: un destino que responde a los 1000 ms da 504")
    void otherRoute_shouldReturn504_whenDownstreamTakes1000ms() {
        profiles.enqueue(slow(1000, "tarde"));

        client.get().uri("/api/v1/profiles/me")
                .header("Authorization", "Bearer token-timeout")
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectBody().jsonPath("$.code").isEqualTo("GATEWAY_TIMEOUT");
    }

    @Test
    @Order(5)
    @DisplayName("El 504 lleva el encabezado X-Request-Id")
    void timeout_shouldCarryRequestIdHeader() {
        interviews.enqueue(slow(1000, "tarde"));

        EntityExchangeResult<byte[]> result = client.get().uri("/api/v1/interviews/sessions/1")
                .header("Authorization", "Bearer token-timeout")
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectBody().returnResult();

        assertThat(result.getResponseHeaders().getFirst("X-Request-Id")).isNotBlank();
    }

    @Test
    @Order(6)
    @DisplayName("El 504 conserva el cuerpo {code,message} y el charset UTF-8")
    void timeout_shouldKeepBodyShape() {
        interviews.enqueue(slow(1000, "tarde"));

        EntityExchangeResult<String> result = client.get().uri("/api/v1/interviews/sessions/1")
                .header("Authorization", "Bearer token-timeout")
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectBody(String.class).returnResult();

        assertThat(result.getResponseBody()).isEqualTo(TIMEOUT_BODY);
        assertThat(result.getResponseHeaders().getContentType().toString())
                .isEqualToIgnoringCase("application/json;charset=UTF-8");
    }

    @Test
    @Order(7)
    @DisplayName("Dos peticiones lentas a la vez cuentan su tiempo cada una por su lado")
    void interviews_shouldTimeOutIndependently_whenTwoSlowRequests() {
        interviews.enqueue(slow(1000, "tarde"));
        interviews.enqueue(slow(1000, "tarde"));

        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(this::interviewsStatus);
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(this::interviewsStatus);

        assertThat(first.join()).isEqualTo(504);
        assertThat(second.join()).isEqualTo(504);
    }

    /**
     * Debe ser la última: apaga el destino de entrevistas para que la conexión sea rechazada.
     */
    @Test
    @Order(99)
    @DisplayName("Entrevistas: un destino caído da 503, como las demás rutas")
    void interviews_shouldReturn503_whenDownstreamClosed() throws IOException {
        interviews.shutdown();

        client.get().uri("/api/v1/interviews/sessions/1")
                .header("Authorization", "Bearer token-timeout")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
    }

    private int interviewsStatus() {
        return client.get().uri("/api/v1/interviews/sessions/1")
                .header("Authorization", "Bearer token-timeout")
                .exchange()
                .returnResult(Void.class)
                .getStatus().value();
    }

    private static MockResponse slow(long headersDelayMs, String body) {
        return new MockResponse().setResponseCode(200).setBody(body)
                .setHeader("X-Sample", "valor-de-ejemplo")
                .setHeadersDelay(headersDelayMs, TimeUnit.MILLISECONDS);
    }
}
