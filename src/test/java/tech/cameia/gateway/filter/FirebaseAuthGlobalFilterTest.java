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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    @Autowired
    ApplicationContext applicationContext;

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

    // ── CM-14 prueba 2: sin perfil local el health es Caso A (REQ-REG-02) ────

    /**
     * CM-14 REQ-REG-02: esta clase corre sin el perfil {@code local}, así que un health de la lista
     * de desarrollo exige token y la solicitud no llega al microservicio.
     */
    @Test
    void devHealth_withoutLocalProfile_returns401() {
        int requestsBefore = mockDownstream.getRequestCount();

        webTestClient.get()
                .uri("/api/v1/users/health")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_REQUIRED");

        assertThat(mockDownstream.getRequestCount()).isEqualTo(requestsBefore);
    }

    // ── CM-14 prueba 6: el registro llega sin credenciales del cliente ───────

    /**
     * CM-14 REQ-REG-05, REQ-REG-07: {@code POST /api/v1/users} llega a cameia-cuentas sin token. Un
     * ID Token que el navegador envíe por inercia y un {@code X-User-Id} inyectado no llegan; el
     * cuerpo sí, intacto.
     */
    @Test
    void registration_withoutToken_reachesAccountsWithoutClientCredentials() throws Exception {
        String body = "{\"email\":\"ana@cameia.tech\",\"password\":\"no-se-registra\"}";
        mockDownstream.enqueue(new MockResponse().setResponseCode(201).setBody("{}"));

        webTestClient.post()
                .uri("/api/v1/users")
                .header("Authorization", "Bearer token-del-navegador")
                .header("X-User-Id", "atacante")
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated();

        RecordedRequest req = mockDownstream.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/api/v1/users");
        assertThat(req.getHeader("Authorization")).isNull();
        assertThat(req.getHeader("X-User-Id")).isNull();
        assertThat(req.getBody().readUtf8()).isEqualTo(body);
    }

    // ── CM-14 prueba 7: solo POST es público en /api/v1/users (REQ-REG-06) ───

    /**
     * CM-14 REQ-REG-06: la entrada pública es {@code POST}. Cualquier otro método sobre la misma
     * ruta es Caso A y no llega a cameia-cuentas.
     *
     * @param method método HTTP distinto de POST
     */
    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "PATCH", "DELETE"})
    void usersRoot_nonPostWithoutToken_returns401(String method) {
        int requestsBefore = mockDownstream.getRequestCount();

        webTestClient.method(HttpMethod.valueOf(method))
                .uri("/api/v1/users")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_REQUIRED");

        assertThat(mockDownstream.getRequestCount()).isEqualTo(requestsBefore);
    }

    // ── CM-14 prueba 8: los errores de Cuentas pasan intactos (REQ-REG-09) ───

    /**
     * CM-14 REQ-REG-09: un {@code 409} o {@code 422} de cameia-cuentas llega al cliente con el mismo
     * estado y el mismo cuerpo, no con el catálogo de errores del Gateway.
     *
     * @param status estado que devuelve cameia-cuentas
     */
    @ParameterizedTest
    @ValueSource(ints = {409, 422})
    void registration_downstreamError_isReturnedUnchanged(int status) throws Exception {
        String body = "{\"code\":\"CUENTAS_ERROR\",\"message\":\"Ya existe un usuario con ese correo\"}";
        mockDownstream.enqueue(new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json").setBody(body));

        EntityExchangeResult<byte[]> result = webTestClient.post()
                .uri("/api/v1/users")
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectBody().returnResult();

        // se consume antes de afirmar: si la prueba falla, no deja la solicitud en la cola compartida
        mockDownstream.takeRequest(5, TimeUnit.SECONDS);

        assertThat(result.getStatus().value()).isEqualTo(status);
        assertThat(new String(result.getResponseBody(), StandardCharsets.UTF_8)).isEqualTo(body);
    }

    // ── CM-14 verificación de correo: el estado llega a Cuentas (REQ-VER-01 a 03) ──

    /**
     * CM-14 REQ-VER-01 y REQ-VER-02: el claim `email_verified` se propaga tal cual, y un `false`
     * también se envía: para cameia-cuentas no es lo mismo que no saberlo.
     *
     * @param claimValue valor del claim en el token
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void emailVerifiedClaim_isPropagatedToDownstream(boolean claimValue) throws Exception {
        FirebaseToken token = mockToken("uid-verificacion", Map.of("email_verified", claimValue));
        when(firebaseAuth.verifyIdToken("token-" + claimValue)).thenReturn(token);

        mockDownstream.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        webTestClient.post()
                .uri("/api/v1/users/me/verification")
                .header("Authorization", "Bearer token-" + claimValue)
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = mockDownstream.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getHeader("X-User-Email-Verified")).isEqualTo(String.valueOf(claimValue));
    }

    /**
     * CM-14 REQ-VER-03: sin el claim la cabecera no se envía. No se usa
     * {@code FirebaseToken.isEmailVerified()}, que convertiría la ausencia en un `false`.
     */
    @Test
    void tokenWithoutEmailVerifiedClaim_omitsHeader() throws Exception {
        FirebaseToken token = mockToken("uid-sin-claim", Map.of());
        when(firebaseAuth.verifyIdToken("token-sin-claim-verificacion")).thenReturn(token);

        mockDownstream.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        webTestClient.get()
                .uri("/api/v1/profiles/me")
                .header("Authorization", "Bearer token-sin-claim-verificacion")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = mockDownstream.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getHeader("X-User-Email-Verified")).isNull();
    }

    /**
     * CM-14 REQ-VER-04: un cliente que se declara verificado no lo consigue. El token manda, y el
     * destino recibe un único valor: sin esto, cualquiera activaría su cuenta con una cabecera.
     */
    @Test
    void clientEmailVerifiedHeader_isReplacedByTokenClaim() throws Exception {
        FirebaseToken token = mockToken("uid-suplanta-verificacion", Map.of("email_verified", false));
        when(firebaseAuth.verifyIdToken("token-suplanta-verificacion")).thenReturn(token);

        mockDownstream.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        webTestClient.post()
                .uri("/api/v1/users/me/verification")
                .header("Authorization", "Bearer token-suplanta-verificacion")
                .header("X-User-Email-Verified", "true")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest req = mockDownstream.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getHeaders().values("X-User-Email-Verified")).containsExactly("false");
    }

    /**
     * CM-14 REQ-VER-04, Caso B: en una ruta pública no hay usuario autenticado, así que la cabecera
     * del cliente se borra sin reemplazo.
     */
    @Test
    void publicRoute_dropsClientEmailVerifiedHeader() throws Exception {
        mockDownstream.enqueue(new MockResponse().setResponseCode(201).setBody("{}"));

        webTestClient.post()
                .uri("/api/v1/users")
                .header("X-User-Email-Verified", "true")
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isCreated();

        RecordedRequest req = mockDownstream.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getHeader("X-User-Email-Verified")).isNull();
    }

    /**
     * CM-14 REQ-VER-05: la ruta de verificación es Caso A. Sin token no llega a Cuentas, y con token
     * válido llega con el contrato de cabeceras de {@code AGENTS.md} §6.4.
     */
    @Test
    void verificationRoute_withoutToken_returns401() {
        int requestsBefore = mockDownstream.getRequestCount();

        webTestClient.post()
                .uri("/api/v1/users/me/verification")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_REQUIRED");

        assertThat(mockDownstream.getRequestCount()).isEqualTo(requestsBefore);
    }

    /**
     * CM-14 REQ-VER-05: con token válido, cameia-cuentas recibe la ruta completa y el contrato de
     * §6.4. El cuerpo va vacío: la identidad y el estado salen del token.
     */
    @Test
    void verificationRoute_withValidToken_reachesAccountsWithIdentityContract() throws Exception {
        FirebaseToken token = mockToken("uid-activa", Map.of("email_verified", true, "roles", List.of("free")));
        when(token.getEmail()).thenReturn("ana@cameia.tech");
        when(firebaseAuth.verifyIdToken("token-activa")).thenReturn(token);

        mockDownstream.enqueue(new MockResponse().setResponseCode(204));

        webTestClient.post()
                .uri("/api/v1/users/me/verification")
                .header("Authorization", "Bearer token-activa")
                .exchange()
                .expectStatus().isNoContent();

        RecordedRequest req = mockDownstream.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/api/v1/users/me/verification");
        assertThat(req.getHeader("X-User-Id")).isEqualTo("uid-activa");
        assertThat(req.getHeader("X-User-Email")).isEqualTo("ana@cameia.tech");
        assertThat(req.getHeader("X-User-Roles")).isEqualTo("free");
        assertThat(req.getHeader("X-User-Email-Verified")).isEqualTo("true");
        assertThat(req.getHeader("X-Request-Id")).isNotBlank();
        assertThat(req.getHeader("Authorization")).isNullOrEmpty();
    }

    /**
     * CM-14 REQ-VER-06: cameia-cuentas responde los errores con `application/problem+json`
     * (RFC 7807). El Gateway no reescribe el cuerpo ni el tipo de contenido.
     */
    @Test
    void downstreamProblemJson_isReturnedUnchanged() throws Exception {
        String body = "{\"type\":\"about:blank\",\"title\":\"Correo no verificado\",\"status\":403}";
        mockDownstream.enqueue(new MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "application/problem+json").setBody(body));

        EntityExchangeResult<byte[]> result = webTestClient.post()
                .uri("/api/v1/users")
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectBody().returnResult();

        mockDownstream.takeRequest(5, TimeUnit.SECONDS);

        assertThat(result.getStatus().value()).isEqualTo(403);
        assertThat(new String(result.getResponseBody(), StandardCharsets.UTF_8)).isEqualTo(body);
        assertThat(result.getResponseHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // ── OIDC prueba 7: con el flag apagado no hay firma (REQ-OIDC-07, REQ-OIDC-08) ──

    /**
     * REQ-OIDC-07, REQ-OIDC-08: esta clase corre sin {@code gateway.oidc.signing-enabled}, así que
     * el filtro de firma no existe. El resto de pruebas de la clase demuestra la otra mitad: el
     * destino no recibe {@code Authorization} y el {@code 401} sin token sigue funcionando.
     */
    @Test
    void oidcSigningFlagOff_noSigningFilterBean() {
        assertThat(applicationContext.getBeansOfType(OidcSigningGlobalFilter.class)).isEmpty();
    }

    // ── Utilidad ─────────────────────────────────────────────────────────────

    private FirebaseToken mockToken(String uid, Map<String, Object> claims) {
        FirebaseToken token = mock(FirebaseToken.class);
        when(token.getUid()).thenReturn(uid);
        when(token.getClaims()).thenReturn(claims);
        return token;
    }
}
