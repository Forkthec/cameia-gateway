package tech.cameia.gateway.filter;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = TestFirebaseConfig.class
)
@ActiveProfiles("test")
class FirebaseAuthGlobalFilterTest {

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
    }

    // ── Caso 1: token válido con claim plan=PREMIUM ──────────────────────────

    @Test
    void validTokenWithPlan_propagatesHeadersToDownstream() throws Exception {
        FirebaseToken token = mockToken("uid-abc123", Map.of("plan", "PREMIUM"));
        when(firebaseAuth.verifyIdToken("token-valido")).thenReturn(token);

        mockDownstream.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        webTestClient.get()
                .uri("/api/v1/profiles/me")
                .header("Authorization", "Bearer token-valido")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = mockDownstream.takeRequest();
        assertThat(req.getHeader("X-User-Id")).isEqualTo("uid-abc123");
        assertThat(req.getHeader("X-User-Plan")).isEqualTo("PREMIUM");
        assertThat(req.getHeader("Authorization")).isNullOrEmpty(); // REQ-07
    }

    // ── Caso 2: token válido sin claim plan ──────────────────────────────────

    @Test
    void validTokenWithoutPlan_omitsXUserPlanHeader() throws Exception {
        FirebaseToken token = mockToken("uid-sin-plan", Map.of());
        when(firebaseAuth.verifyIdToken("token-sin-plan")).thenReturn(token);

        mockDownstream.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header("Authorization", "Bearer token-sin-plan")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = mockDownstream.takeRequest();
        assertThat(req.getHeader("X-User-Id")).isEqualTo("uid-sin-plan");
        assertThat(req.getHeader("X-User-Plan")).isNull(); // no propaga vacío
    }

    // ── Caso 3: sin header Authorization ────────────────────────────────────

    @Test
    void missingAuthorization_returns401() {
        webTestClient.get()
                .uri("/api/v1/profiles/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_REQUIRED");
    }

    // ── Caso 4: token inválido ───────────────────────────────────────────────

    @Test
    void invalidToken_returns401() throws Exception {
        when(firebaseAuth.verifyIdToken("token-invalido"))
                .thenThrow(mock(FirebaseAuthException.class));

        webTestClient.get()
                .uri("/api/v1/profiles/me")
                .header("Authorization", "Bearer token-invalido")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_REQUIRED");
    }

    // ── Caso 5: POST /webhooks/wompi sin token → llega al downstream ─────────

    @Test
    void wompiWebhook_withoutToken_reachesDownstreamWithoutIdentityHeaders() throws Exception {
        mockDownstream.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        webTestClient.post()
                .uri("/webhooks/wompi")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = mockDownstream.takeRequest();
        assertThat(req.getHeader("X-User-Id")).isNull();
        assertThat(req.getHeader("X-User-Plan")).isNull();
    }

    // ── Caso 6: health check público ────────────────────────────────────────

    @Test
    void actuatorHealth_withoutToken_returns200() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    // ── Caso 7: X-Request-Id enviado por el cliente se conserva (REQ-09) ─────

    @Test
    void clientRequestId_isPreservedToDownstream() throws Exception {
        FirebaseToken token = mockToken("uid-trazado", Map.of());
        when(firebaseAuth.verifyIdToken("token-trazado")).thenReturn(token);

        mockDownstream.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        webTestClient.get()
                .uri("/api/v1/profiles/me")
                .header("Authorization", "Bearer token-trazado")
                .header("X-Request-Id", "abc")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = mockDownstream.takeRequest();
        // un solo valor: se fija con set, no se añade un segundo
        assertThat(req.getHeaders().values("X-Request-Id")).containsExactly("abc");
    }

    // ── Caso 8: sin X-Request-Id el gateway genera uno (REQ-09) ─────────────

    @Test
    void missingRequestId_gatewayGeneratesOneForDownstream() throws Exception {
        mockDownstream.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        webTestClient.post()
                .uri("/webhooks/wompi")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = mockDownstream.takeRequest();
        assertThat(req.getHeader("X-Request-Id")).isNotBlank();
    }

    // ── Utilidad ─────────────────────────────────────────────────────────────

    private FirebaseToken mockToken(String uid, Map<String, Object> claims) {
        FirebaseToken token = mock(FirebaseToken.class);
        when(token.getUid()).thenReturn(uid);
        when(token.getClaims()).thenReturn(claims);
        return token;
    }
}
