package tech.cameia.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.firebase.auth.FirebaseAuth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Verifica que {@code GATEWAY_CORS_ALLOWED_ORIGIN} admite varios orígenes separados por coma en
 * un único valor, sin sintaxis de lista en el YAML (REQ-09).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = TestFirebaseConfig.class
)
@ActiveProfiles("test")
class GlobalCorsConfigTest {

    @LocalServerPort
    int port;

    WebTestClient webTestClient;

    @Autowired
    FirebaseAuth firebaseAuth;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("CAMEIA_CUENTAS_URL", () -> "http://localhost:1");
        registry.add("CAMEIA_PERFIL_URL", () -> "http://localhost:1");
        registry.add("CAMEIA_ENTREVISTA_URL", () -> "http://localhost:1");
        registry.add("GATEWAY_CORS_ALLOWED_ORIGIN",
                () -> "https://cameia.app,https://cameia-e245f.web.app");
    }

    @BeforeEach
    void initWebTestClient() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void preflight_firstOriginInCommaSeparatedList_isAllowed() {
        webTestClient.options()
                .uri("/api/v1/users")
                .header(HttpHeaders.ORIGIN, "https://cameia.app")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
                .exchange()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://cameia.app");
    }

    @Test
    void preflight_secondOriginInCommaSeparatedList_isAllowed() {
        webTestClient.options()
                .uri("/api/v1/users")
                .header(HttpHeaders.ORIGIN, "https://cameia-e245f.web.app")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
                .exchange()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://cameia-e245f.web.app");
    }

    private static final String ALLOWED_ORIGIN = "https://cameia.app";
    private static final String SESSIONS_PATH = "/api/v1/interviews/sessions";
    private static final String ANSWERS_PATH = "/api/v1/interviews/sessions/abc/answers";

    /** Preflight de un POST con los encabezados pedidos, como lo envía un navegador. */
    private WebTestClient.ResponseSpec preflight(String origin, String path, String requestHeaders) {
        return webTestClient.options()
                .uri(path)
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, requestHeaders)
                .exchange();
    }

    @ParameterizedTest
    @ValueSource(strings = {"idempotency-key", "Idempotency-Key"})
    void preflight_idempotencyKeyOnSessionCreation_isAllowed(String requestHeader) {
        preflight(ALLOWED_ORIGIN, SESSIONS_PATH, requestHeader)
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN)
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        value -> assertThat(value.toLowerCase())
                                .contains("idempotency-key"));
    }

    @Test
    void preflight_idempotencyKeyOnAnswers_isAllowed() {
        preflight(ALLOWED_ORIGIN, ANSWERS_PATH, "idempotency-key")
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN);
    }

    @Test
    void preflight_existingHeaders_stillAllowed() {
        preflight(ALLOWED_ORIGIN, SESSIONS_PATH, "authorization,content-type,x-request-id")
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN)
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        value -> assertThat(value.toLowerCase())
                                .contains("authorization", "content-type", "x-request-id"));
    }

    @Test
    void preflight_allHeadersTheWebSends_inOneRequest_areAllListedAsAllowed() {
        // Con varios encabezados, Spring responde 200 aunque alguno no esté admitido y deja fuera
        // de Allow-Headers los que no admite: es el navegador quien bloquea. Por eso se comprueba
        // la lista devuelta y no solo el estado.
        preflight(ALLOWED_ORIGIN, SESSIONS_PATH, "authorization,content-type,idempotency-key,x-request-id")
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN)
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        value -> assertThat(value.toLowerCase())
                                .contains("authorization", "content-type", "idempotency-key", "x-request-id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"x-user-id", "x-user-plan", "x-user-email-verified", "x-otro"})
    void preflight_headerNotInList_isRejected(String requestHeader) {
        preflight(ALLOWED_ORIGIN, SESSIONS_PATH, requestHeader)
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void preflight_idempotencyKeyFromUnknownOrigin_isRejected() {
        preflight("https://evil.example.com", SESSIONS_PATH, "idempotency-key")
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void preflight_originNotInList_getsNoAllowOriginHeader() {
        webTestClient.options()
                .uri("/api/v1/users")
                .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
                .exchange()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }
}
