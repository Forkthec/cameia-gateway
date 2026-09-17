package tech.cameia.gateway.filter;

import tech.cameia.gateway.config.TestFirebaseConfig;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de la lista de desarrollo de {@link FirebaseAuthGlobalFilter}: los health v1 de cada
 * microservicio son públicos solo con el perfil {@code local} (CM-14 REQ-REG-01, REQ-REG-03,
 * REQ-REG-04).
 *
 * <p>Clase aparte de {@code FirebaseAuthGlobalFilterTest} porque necesita otro contexto: el perfil
 * {@code local} activo. La otra clase, con solo {@code test}, demuestra que sin ese perfil los
 * mismos health responden {@code 401} (REQ-REG-02).
 *
 * <p>Los cinco microservicios apuntan al mismo {@link MockWebServer}: basta con saber que la
 * solicitud llegó y con qué cabeceras.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = TestFirebaseConfig.class
)
@ActiveProfiles({"test", "local"})
class DevHealthRoutesTest {

    static MockWebServer mockDownstream;

    @LocalServerPort
    int port;

    WebTestClient webTestClient;

    @BeforeAll
    static void startMockServer() throws IOException {
        mockDownstream = new MockWebServer();
        mockDownstream.start();
    }

    @BeforeEach
    void initWebTestClient() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @AfterAll
    static void stopMockServer() throws IOException {
        mockDownstream.shutdown();
    }

    @DynamicPropertySource
    static void downstreamUrls(DynamicPropertyRegistry registry) {
        String url = "http://localhost:" + mockDownstream.getPort();
        registry.add("CAMEIA_CUENTAS_URL", () -> url);
        registry.add("CAMEIA_PERFIL_URL", () -> url);
        registry.add("CAMEIA_ENTREVISTA_URL", () -> url);
        registry.add("CAMEIA_VOZ_URL", () -> url);
        registry.add("CAMEIA_AUDITORIA_URL", () -> url);
    }

    // ── Prueba 1 del plan: los cinco health llegan sin token (REQ-REG-01, REQ-REG-04) ──

    /**
     * REQ-REG-01, REQ-REG-04: con el perfil {@code local}, cada health v1 llega al microservicio sin
     * token. Los {@code X-User-*} y el {@code Authorization} del cliente se borran, y el
     * {@code X-Request-Id} se propaga.
     *
     * @param path health v1 de un microservicio
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/users/health",
            "/api/v1/profiles/health",
            "/api/v1/interviews/health",
            "/api/v1/voice-service/health",
            "/api/v1/audit/health"
    })
    void devHealth_withLocalProfile_reachesDownstreamWithoutToken(String path) throws Exception {
        mockDownstream.enqueue(new MockResponse().setResponseCode(200).setBody("{\"status\":\"UP\"}"));

        webTestClient.get()
                .uri(path)
                .header("X-User-Id", "atacante")
                .header("Authorization", "Bearer token-del-navegador")
                .header("X-Request-Id", "req-health")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = mockDownstream.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo(path);
        assertThat(req.getHeader("X-User-Id")).isNull();
        assertThat(req.getHeader("Authorization")).isNull();
        assertThat(req.getHeaders().values("X-Request-Id")).containsExactly("req-health");
    }

    // ── Prueba 4 del plan: solo el texto exacto (REQ-REG-03) ─────────────────

    /**
     * REQ-REG-03: una barra final o un segmento extra no coinciden con la lista de desarrollo, así
     * que exigen token aunque el perfil {@code local} esté activo.
     *
     * @param path variante de un health que no está en la lista
     */
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/users/health/", "/api/v1/users/health/extra"})
    void devHealth_trailingSlashOrSubpath_returns401(String path) {
        int requestsBefore = mockDownstream.getRequestCount();

        webTestClient.get()
                .uri(path)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_REQUIRED");

        assertThat(mockDownstream.getRequestCount()).isEqualTo(requestsBefore);
    }

    // ── Prueba 3 del plan: solo el método exacto (REQ-REG-03) ────────────────

    /**
     * REQ-REG-03: la entrada de desarrollo es {@code GET}; un {@code POST} a la misma ruta es Caso A.
     */
    @Test
    void devHealth_postMethod_returns401() {
        int requestsBefore = mockDownstream.getRequestCount();

        webTestClient.post()
                .uri("/api/v1/users/health")
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(mockDownstream.getRequestCount()).isEqualTo(requestsBefore);
    }
}
