package tech.cameia.gateway.filter;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;

import tech.cameia.gateway.config.TestFirebaseConfig;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pruebas de contrato de {@link OidcSigningGlobalFilter}, con la firma encendida y una fuente de
 * tokens falsa: sin credenciales de Google (REQ-NF-OIDC-03).
 *
 * <p>cameia-perfil apunta al {@link MockWebServer} <b>con barra final</b> y cameia-cuentas sin ella:
 * el audience tiene que salir igual en los dos casos (REQ-OIDC-02).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {TestFirebaseConfig.class, OidcSigningFilterTest.TestOidcConfig.class},
        properties = {
                "gateway.oidc.signing-enabled=true",
                "spring.cloud.gateway.server.webflux.httpclient.response-timeout=500ms"
        }
)
@ActiveProfiles("test")
class OidcSigningFilterTest {

    /**
     * Fuente de tokens falsa (T-16): devuelve {@code oidc-para-<audience>}, registra cada audience
     * pedido y falla a demanda.
     *
     * <p>Anidada aquí y no en el paquete {@code config} de pruebas: esta clase la usa desde
     * {@code filter}, y ArchUnit prohíbe que {@code filter} acceda a {@code config}.
     */
    @TestConfiguration
    static class TestOidcConfig {

        static final List<String> REQUESTED_AUDIENCES = new CopyOnWriteArrayList<>();

        static volatile boolean failing = false;

        @Bean
        OidcTokenSource fakeTokenSource() {
            return audience -> {
                REQUESTED_AUDIENCES.add(audience);
                return failing
                        ? Mono.error(new IllegalStateException("sin credenciales"))
                        : Mono.just("oidc-para-" + audience);
            };
        }
    }

    static MockWebServer mockDownstream;

    @LocalServerPort
    int port;

    WebTestClient webTestClient;

    @Autowired
    FirebaseAuth firebaseAuth;

    @BeforeAll
    static void startMockServer() throws IOException {
        mockDownstream = new MockWebServer();
        mockDownstream.start();
    }

    @AfterAll
    static void stopMockServer() throws IOException {
        mockDownstream.shutdown();
    }

    @DynamicPropertySource
    static void downstreamUrls(DynamicPropertyRegistry registry) {
        String url = "http://localhost:" + mockDownstream.getPort();
        registry.add("CAMEIA_PERFIL_URL", () -> url + "/"); // barra final a propósito
        registry.add("CAMEIA_CUENTAS_URL", () -> url);
        registry.add("CAMEIA_ENTREVISTA_URL", () -> url);
    }

    @BeforeEach
    void reset() {
        webTestClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        TestOidcConfig.REQUESTED_AUDIENCES.clear();
        TestOidcConfig.failing = false;
    }

    // ── Prueba 1: Caso A llega firmado, un solo valor (REQ-OIDC-01, REQ-OIDC-03) ──

    @Test
    void caseA_downstreamReceivesSingleOidcToken() throws Exception {
        callProfilesWithFirebaseToken("firebase-id-token");

        RecordedRequest req = takeRequest();
        assertThat(req.getHeaders().values("Authorization")).containsExactly("Bearer " + expectedToken());
        assertThat(req.getHeader("X-User-Id")).isEqualTo("uid-oidc"); // la identidad sigue llegando
    }

    // ── Prueba 2: el audience sale de la ruta, sin path ni barra final (REQ-OIDC-02) ──

    @Test
    void audience_matchesRouteUriWithoutPathOrTrailingSlash() throws Exception {
        callProfilesWithFirebaseToken("firebase-id-token");
        takeRequest();

        assertThat(TestOidcConfig.REQUESTED_AUDIENCES).containsExactly(expectedAudience());
    }

    // ── Prueba 3: Caso B también sale firmado (REQ-OIDC-01) ──────────────────

    @Test
    void wompiWebhook_withoutFirebaseToken_isSigned() throws Exception {
        mockDownstream.enqueue(new MockResponse().setResponseCode(200));

        webTestClient.post().uri("/webhooks/wompi").exchange().expectStatus().isOk();

        assertThat(takeRequest().getHeaders().values("Authorization")).containsExactly("Bearer " + expectedToken());
    }

    // ── Prueba 4: el Authorization del cliente se reemplaza (REQ-OIDC-03) ────

    @Test
    void clientBasicAuthorization_isReplacedByOidcToken() throws Exception {
        mockDownstream.enqueue(new MockResponse().setResponseCode(200));

        webTestClient.post().uri("/webhooks/wompi")
                .header("Authorization", "Basic xyz")
                .exchange()
                .expectStatus().isOk();

        assertThat(takeRequest().getHeaders().values("Authorization")).containsExactly("Bearer " + expectedToken());
    }

    // ── Prueba 5: el ID Token de Firebase no sale (REQ-OIDC-03) ──────────────

    @Test
    void caseA_firebaseIdToken_neverReachesDownstream() throws Exception {
        callProfilesWithFirebaseToken("firebase-secreto-123");

        assertThat(takeRequest().getHeaders().toString()).doesNotContain("firebase-secreto-123");
    }

    // ── CM-14 T-20: el registro también sale firmado (REQ-REG-10) ────────────

    @Test
    void registration_isSignedLikeAnyOtherRoute() throws Exception {
        mockDownstream.enqueue(new MockResponse().setResponseCode(201));

        webTestClient.post().uri("/api/v1/users")
                .header("Authorization", "Bearer token-del-navegador")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isCreated();

        assertThat(takeRequest().getHeaders().values("Authorization")).containsExactly("Bearer " + expectedToken());
    }

    // ── Prueba 6: fail closed, sin fuga y sin reenvío (REQ-OIDC-04, REQ-OIDC-09) ──

    @Test
    void tokenSourceFailure_returns503WithoutForwarding() {
        TestOidcConfig.failing = true;
        int requestsBefore = mockDownstream.getRequestCount();

        EntityExchangeResult<byte[]> result = webTestClient.post().uri("/webhooks/wompi")
                .exchange()
                .expectBody().returnResult();

        String body = new String(result.getResponseBody(), StandardCharsets.UTF_8);
        assertThat(result.getStatus().value()).isEqualTo(503);
        assertThat(body).contains("\"code\":\"SERVICE_UNAVAILABLE\"")
                .doesNotContain("Exception", "java.", "localhost", "credenciales");
        assertThat(mockDownstream.getRequestCount()).isEqualTo(requestsBefore);
    }

    // ── Plan §3.6 corregido: un fallo del destino no se disfraza de fallo de firma ──

    /**
     * El {@code onErrorResume} de la firma solo cubre la obtención del token. Si cubriera también
     * el reenvío, como en el boceto del plan §3.5, este timeout del destino saldría como {@code 503}
     * de firma en lugar de {@code 504}.
     */
    @Test
    void downstreamTimeout_isStill504WithSigningEnabled() throws Exception {
        mockDownstream.enqueue(new MockResponse().setResponseCode(200).setHeadersDelay(2, TimeUnit.SECONDS));

        webTestClient.post().uri("/webhooks/wompi")
                .exchange()
                .expectStatus().isEqualTo(504);

        takeRequest(); // la petición sí salió, firmada: el fallo es del destino
    }

    // ── Utilidades ───────────────────────────────────────────────────────────

    private void callProfilesWithFirebaseToken(String idToken) throws Exception {
        FirebaseToken token = mock(FirebaseToken.class);
        when(token.getUid()).thenReturn("uid-oidc");
        when(token.getClaims()).thenReturn(Map.of());
        when(firebaseAuth.verifyIdToken(idToken)).thenReturn(token);
        mockDownstream.enqueue(new MockResponse().setResponseCode(200));

        webTestClient.get().uri("/api/v1/profiles/me")
                .header("Authorization", "Bearer " + idToken)
                .exchange()
                .expectStatus().isOk();
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest req = mockDownstream.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).as("el destino no recibió la petición").isNotNull();
        return req;
    }

    private String expectedAudience() {
        return "http://localhost:" + mockDownstream.getPort();
    }

    private String expectedToken() {
        return "oidc-para-" + expectedAudience();
    }
}
