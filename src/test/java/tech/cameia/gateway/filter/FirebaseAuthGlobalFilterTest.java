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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pruebas de contrato de {@link FirebaseAuthGlobalFilter}.
 *
 * <p>Arrancan el Gateway completo en un puerto aleatorio y sustituyen los microservicios por un
 * {@link MockWebServer}. Así cada prueba comprueba lo que el microservicio <em>recibe de verdad</em>
 * ({@link RecordedRequest}), y no solo lo que el filtro cree haber enviado.
 *
 * <p>{@link FirebaseAuth} es un mock ({@code TestFirebaseConfig}): cada prueba decide qué token
 * es válido y con qué claims, sin credenciales de Google.
 *
 * <p>Las pruebas de suplantación son las más importantes: comprueban que un cliente no puede hacer
 * llegar al microservicio una cabecera {@code X-User-*} escrita por él (REQ-03, REQ-07).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = TestFirebaseConfig.class
)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
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

    // ── Caso 1: token válido con claim plan=PREMIUM (REQ-01: contrato completo) ──

    /**
     * REQ-01: con un token válido que trae {@code email}, {@code roles} y {@code plan}, el
     * microservicio recibe las cinco cabeceras del contrato y no recibe el {@code Authorization}
     * del cliente (REQ-07).
     */
    @Test
    void validTokenWithPlan_propagatesHeadersToDownstream() throws Exception {
        FirebaseToken token = mockToken("uid-abc123",
                Map.of("plan", "PREMIUM", "roles", List.of("premium")));
        when(token.getEmail()).thenReturn("ana@cameia.tech");
        when(firebaseAuth.verifyIdToken("token-valido")).thenReturn(token);

        mockDownstream.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        webTestClient.get()
                .uri("/api/v1/profiles/me")
                .header("Authorization", "Bearer token-valido")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = mockDownstream.takeRequest();
        assertThat(req.getHeader("X-User-Id")).isEqualTo("uid-abc123");
        assertThat(req.getHeader("X-User-Email")).isEqualTo("ana@cameia.tech");
        assertThat(req.getHeader("X-User-Roles")).isEqualTo("premium");
        assertThat(req.getHeader("X-User-Plan")).isEqualTo("PREMIUM");
        assertThat(req.getHeader("X-Request-Id")).isNotBlank();
        assertThat(req.getHeader("Authorization")).isNullOrEmpty(); // REQ-07
    }

    // ── Caso 1b: el cliente intenta suplantar a otro usuario (REQ-07) ────────

    /**
     * REQ-07: un usuario con token válido envía además {@code X-User-Id: atacante} para intentar
     * suplantar a otro usuario.
     *
     * <p>La petición no se rechaza: la cabecera del cliente se descarta y se reemplaza por el
     * {@code uid} del token. Se comprueba con {@code values(...)} y no con {@code getHeader(...)},
     * porque este último solo devuelve el primer valor y no detectaría un valor duplicado.
     */
    @Test
    void clientIdentityHeader_isReplacedByTokenUid() throws Exception {
        FirebaseToken token = mockToken("uid-legitimo", Map.of());
        when(firebaseAuth.verifyIdToken("token-suplantacion")).thenReturn(token);

        mockDownstream.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        webTestClient.get()
                .uri("/api/v1/profiles/me")
                .header("Authorization", "Bearer token-suplantacion")
                .header("X-User-Id", "atacante")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = mockDownstream.takeRequest();
        // un solo valor, el del token: el del cliente se descarta en vez de sumarse
        assertThat(req.getHeaders().values("X-User-Id")).containsExactly("uid-legitimo");
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

    // ── Caso 2b: roles como lista se une con comas (GW-TBD-06) ───────────────

    /**
     * GW-TBD-06: el claim {@code roles} llega como lista y el microservicio lo recibe unido por
     * comas, en el mismo orden del token.
     */
    @Test
    void rolesClaimAsList_isJoinedWithCommas() throws Exception {
        FirebaseToken token = mockToken("uid-roles-lista", Map.of("roles", List.of("free", "premium")));
        when(firebaseAuth.verifyIdToken("token-roles-lista")).thenReturn(token);

        mockDownstream.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        webTestClient.get()
                .uri("/api/v1/profiles/me")
                .header("Authorization", "Bearer token-roles-lista")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = mockDownstream.takeRequest();
        assertThat(req.getHeader("X-User-Roles")).isEqualTo("free,premium");
    }

    // ── Caso 2c: roles como cadena se propaga tal cual (REQ-08) ──────────────

    /**
     * REQ-08: el claim {@code roles} llega como cadena y se propaga tal cual. Se usa
     * {@code PREMIUM} en mayúsculas para demostrar que el Gateway no normaliza el valor.
     */
    @Test
    void rolesClaimAsString_isPropagatedUnchanged() throws Exception {
        FirebaseToken token = mockToken("uid-roles-cadena", Map.of("roles", "PREMIUM"));
        when(firebaseAuth.verifyIdToken("token-roles-cadena")).thenReturn(token);

        mockDownstream.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        webTestClient.get()
                .uri("/api/v1/profiles/me")
                .header("Authorization", "Bearer token-roles-cadena")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = mockDownstream.takeRequest();
        assertThat(req.getHeader("X-User-Roles")).isEqualTo("PREMIUM"); // sin cambiar mayúsculas
    }

    // ── Caso 2d: token sin email omite la cabecera (REQ-01, GW-TBD-07) ───────

    /**
     * REQ-01, GW-TBD-07: un token sin correo, posible con métodos de login que no lo exigen, hace
     * que {@code X-User-Email} se omita en lugar de llegar vacía.
     */
    @Test
    void tokenWithoutEmail_omitsXUserEmailHeader() throws Exception {
        FirebaseToken token = mockToken("uid-sin-email", Map.of());
        when(firebaseAuth.verifyIdToken("token-sin-email")).thenReturn(token);

        mockDownstream.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        webTestClient.get()
                .uri("/api/v1/profiles/me")
                .header("Authorization", "Bearer token-sin-email")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = mockDownstream.takeRequest();
        assertThat(req.getHeader("X-User-Email")).isNull(); // se omite, no llega vacía
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

    // ── Caso 3b: el rechazo se registra con su X-Request-Id (REQ-09) ─────────

    /**
     * REQ-09, tercera cláusula: un rechazo de autenticación queda en el log con el
     * {@code X-Request-Id} de la solicitud, para poder rastrearlo.
     *
     * <p>La salida se captura con {@link OutputCaptureExtension}, declarada en la clase.
     *
     * @param output salida de consola capturada durante la prueba
     */
    @Test
    void authRejection_isLoggedWithRequestId(CapturedOutput output) {
        webTestClient.get()
                .uri("/api/v1/profiles/me")
                .header("X-Request-Id", "req-rechazo-123")
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(output.getOut())
                .contains("Solicitud rechazada por autenticación [requestId=req-rechazo-123]");
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

    // ── Caso 4b: Bearer vacío (REQ-02, prueba 7 del plan) ────────────────────

    /**
     * REQ-02: {@code Authorization: Bearer } sin token responde {@code 401} y no {@code 500}, y la
     * solicitud no llega al microservicio.
     */
    @Test
    void emptyBearerToken_returns401WithoutReachingDownstream() {
        int requestsBefore = mockDownstream.getRequestCount();

        webTestClient.get()
                .uri("/api/v1/profiles/me")
                .header("Authorization", "Bearer ")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_REQUIRED");

        assertThat(mockDownstream.getRequestCount()).isEqualTo(requestsBefore);
    }

    // ── Caso 4c: esquema distinto de Bearer (REQ-02, prueba 8 del plan) ──────

    @Test
    void nonBearerScheme_returns401() {
        webTestClient.get()
                .uri("/api/v1/profiles/me")
                .header("Authorization", "Basic xyz")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_REQUIRED");
    }

    // ── Caso 4d: Firebase rechaza el formato del token (REQ-02, T-23) ────────

    /**
     * REQ-02: si Firebase rechaza el token con {@link IllegalArgumentException} en lugar de
     * {@link FirebaseAuthException}, el cliente igualmente recibe {@code 401}.
     */
    @Test
    void firebaseIllegalArgument_returns401() throws Exception {
        when(firebaseAuth.verifyIdToken("token-malformado"))
                .thenThrow(new IllegalArgumentException("token malformado"));

        webTestClient.get()
                .uri("/api/v1/profiles/me")
                .header("Authorization", "Bearer token-malformado")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_REQUIRED");
    }

    // ── Caso 4e: fallo del Gateway al verificar no es un 401 (plan §3.4) ─────

    /**
     * Plan §3.4: un fallo que no es culpa del token, como no poder hablar con Firebase, no se
     * disfraza de {@code 401}. Es un problema del Gateway y responde {@code 500}.
     */
    @Test
    void firebaseUnexpectedFailure_isNotTreatedAsTokenRejection() throws Exception {
        when(firebaseAuth.verifyIdToken("token-sin-red"))
                .thenThrow(new IllegalStateException("sin conexión con Firebase"));

        webTestClient.get()
                .uri("/api/v1/profiles/me")
                .header("Authorization", "Bearer token-sin-red")
                .exchange()
                .expectStatus().isEqualTo(500);
    }

    // ── Caso 4f: el 401 devuelve el X-Request-Id (REQ-09, T-24a) ─────────────

    /**
     * REQ-09: la respuesta {@code 401} lleva el mismo {@code X-Request-Id} que envió el cliente,
     * con un único valor.
     */
    @Test
    void unauthorizedResponse_includesRequestId() {
        webTestClient.get()
                .uri("/api/v1/profiles/me")
                .header("X-Request-Id", "abc")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().values("X-Request-Id", values -> assertThat(values).containsExactly("abc"));
    }

    // ── Caso 5: POST /webhooks/wompi sin token → llega al downstream ─────────

    /**
     * REQ-03: {@code /webhooks/wompi} es pública y llega al microservicio sin token de Firebase,
     * pero un {@code X-User-Id} enviado por el cliente se borra: en una ruta pública no hay usuario
     * autenticado y el microservicio no debe creer que lo hay.
     */
    @Test
    void wompiWebhook_withoutToken_reachesDownstreamWithoutIdentityHeaders() throws Exception {
        mockDownstream.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        webTestClient.post()
                .uri("/webhooks/wompi")
                .header("X-User-Id", "atacante") // REQ-03: en ruta pública se borra
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
                .expectStatus().isOk()
                // el cliente también recibe el id, aunque el microservicio no lo devuelva
                .expectHeader().values("X-Request-Id", values -> assertThat(values).containsExactly("abc"));

        RecordedRequest req = mockDownstream.takeRequest();
        // un solo valor: se fija con set, no se añade un segundo
        assertThat(req.getHeaders().values("X-Request-Id")).containsExactly("abc");
    }

    // ── Caso 7b: el microservicio devuelve el mismo X-Request-Id ─────────────

    /**
     * REQ-09: si el microservicio devuelve su propio {@code X-Request-Id}, el cliente recibe un solo
     * valor y no dos (el que fijó el filtro más el del microservicio).
     */
    @Test
    void downstreamEchoedRequestId_isNotDuplicatedInResponse() throws Exception {
        FirebaseToken token = mockToken("uid-eco", Map.of());
        when(firebaseAuth.verifyIdToken("token-eco")).thenReturn(token);

        mockDownstream.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("X-Request-Id", "abc").setBody("ok"));

        EntityExchangeResult<byte[]> result = webTestClient.get()
                .uri("/api/v1/profiles/me")
                .header("Authorization", "Bearer token-eco")
                .header("X-Request-Id", "abc")
                .exchange()
                .expectBody().returnResult();

        // se consume antes de afirmar: si la prueba falla, no deja la solicitud en la cola compartida
        mockDownstream.takeRequest();

        assertThat(result.getStatus().value()).isEqualTo(200);
        assertThat(result.getResponseHeaders().get("X-Request-Id")).containsExactly("abc");
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
