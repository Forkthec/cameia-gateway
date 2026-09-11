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
    void tokenValidoConPlan_propagaHeadersAlDownstream() throws Exception {
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
    void tokenValidoSinPlan_omiteHeaderXUserPlan() throws Exception {
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
    void sinAuthorization_retorna401() {
        webTestClient.get()
                .uri("/api/v1/profiles/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_REQUIRED");
    }

    // ── Caso 4: token inválido ───────────────────────────────────────────────

    @Test
    void tokenInvalido_retorna401() throws Exception {
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
    void wompiWebhook_sinToken_pasaAlDownstreamSinHeadersIdentidad() throws Exception {
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
    void actuatorHealth_sinToken_retorna200() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    // ── Utilidad ─────────────────────────────────────────────────────────────

    private FirebaseToken mockToken(String uid, Map<String, Object> claims) {
        FirebaseToken token = mock(FirebaseToken.class);
        when(token.getUid()).thenReturn(uid);
        when(token.getClaims()).thenReturn(claims);
        return token;
    }
}
