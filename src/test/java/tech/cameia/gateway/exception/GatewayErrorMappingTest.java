package tech.cameia.gateway.exception;

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
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pruebas de contrato de {@link GlobalErrorHandler}: cómo responde el Gateway cuando el
 * microservicio destino falla (REQ-11, REQ-12, REQ-NF-02).
 *
 * <p>Clase aparte de {@code FirebaseAuthGlobalFilterTest} porque baja el timeout de respuesta a
 * {@code 300ms}: así la prueba de timeout no espera los 30 segundos reales.
 *
 * <p>Destinos simulados:
 * <ul>
 *   <li>cameia-perfil apunta a un {@link MockWebServer} que tarda más que el timeout en responder.</li>
 *   <li>cameia-cuentas apunta a un puerto donde nada escucha.</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = TestFirebaseConfig.class
)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class GatewayErrorMappingTest {

    static MockWebServer slowDownstream;

    /** Puerto que existió y se cerró: está en la URL de cuentas, pero nada escucha en él. */
    static int closedPort;

    @LocalServerPort
    int port;

    WebTestClient webTestClient;

    @Autowired
    FirebaseAuth firebaseAuth;

    @BeforeAll
    static void startDownstreams() throws IOException {
        slowDownstream = new MockWebServer();
        slowDownstream.start();

        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
    }

    @AfterAll
    static void stopDownstreams() throws IOException {
        slowDownstream.shutdown();
    }

    @BeforeEach
    void initWebTestClient() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @DynamicPropertySource
    static void downstreamUrls(DynamicPropertyRegistry registry) {
        registry.add("CAMEIA_PERFIL_URL", () -> "http://localhost:" + slowDownstream.getPort());
        registry.add("CAMEIA_CUENTAS_URL", () -> "http://localhost:" + closedPort);
        registry.add("spring.cloud.gateway.server.webflux.httpclient.response-timeout", () -> "300ms");
    }

    // ── Prueba 11 del plan: destino que no responde a tiempo → 504 ───────────

    /**
     * REQ-11, REQ-NF-02: el microservicio no responde dentro del timeout y el Gateway responde
     * {@code 504 GATEWAY_TIMEOUT}.
     */
    @Test
    void unresponsiveDownstream_returns504() throws Exception {
        requestSlowProfile("token-lento")
                .expectStatus().isEqualTo(504)
                .expectBody()
                .jsonPath("$.code").isEqualTo("GATEWAY_TIMEOUT");

        slowDownstream.takeRequest(1, TimeUnit.SECONDS);
    }

    // ── Prueba 12 del plan: destino inalcanzable → 503 ───────────────────────

    /**
     * REQ-11: nada escucha en el puerto del microservicio y el Gateway responde
     * {@code 503 SERVICE_UNAVAILABLE}. Se usa {@code /webhooks/wompi} porque es pública y no
     * necesita token.
     */
    @Test
    void unreachableDownstream_returns503() {
        webTestClient.post()
                .uri("/webhooks/wompi")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
    }

    // ── Prueba 13 del plan: el cuerpo no revela detalle interno ──────────────

    /**
     * REQ-12: ni el {@code 504} ni el {@code 503} llevan en el cuerpo el mensaje de la excepción,
     * paquetes de Java ni el host o el puerto del microservicio.
     */
    @Test
    void errorBodies_doNotLeakInternalDetail() throws Exception {
        String timeoutBody = requestSlowProfile("token-fuga").expectBody(String.class)
                .returnResult().getResponseBody();
        slowDownstream.takeRequest(1, TimeUnit.SECONDS);

        String unreachableBody = webTestClient.post().uri("/webhooks/wompi").exchange()
                .expectBody(String.class).returnResult().getResponseBody();

        for (String body : new String[] {timeoutBody, unreachableBody}) {
            assertThat(body)
                    .doesNotContain("Exception")
                    .doesNotContain("java.")
                    .doesNotContain("localhost")
                    .doesNotContain("127.0.0.1")
                    .doesNotContain(String.valueOf(slowDownstream.getPort()))
                    .doesNotContain(String.valueOf(closedPort));
        }
    }

    // ── T-28: el fallo se registra con el mismo X-Request-Id del destino ─────

    /**
     * REQ-12: el error queda en el log con el {@code X-Request-Id} que generó el filtro, el mismo
     * que recibió el microservicio y el mismo que recibe el cliente. Sin {@code X-Request-Id} del
     * cliente, que es el caso en que el id solo lo conoce el filtro.
     *
     * @param output salida de consola capturada durante la prueba
     */
    @Test
    void downstreamFailure_isLoggedWithRequestIdSentToDownstream(CapturedOutput output) throws Exception {
        EntityExchangeResult<byte[]> result = requestSlowProfile("token-trazado")
                .expectStatus().isEqualTo(504)
                .expectBody().returnResult();

        RecordedRequest received = slowDownstream.takeRequest(1, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        String requestId = received.getHeader("X-Request-Id");

        assertThat(requestId).isNotBlank();
        assertThat(result.getResponseHeaders().get("X-Request-Id")).containsExactly(requestId);
        assertThat(output.getOut())
                .contains("Fallo al procesar la solicitud [requestId=" + requestId + "]");
    }

    // ── CM-184 T-06: una ruta inexistente no deja traza en el log ────────────

    /**
     * `REQ-LOG-02` del spec `CM-184-nivel-log-4xx`: una URL que no coincide con ninguna ruta
     * responde `404` y deja una sola línea `INFO` con su `X-Request-Id`. La traza de
     * `NoResourceFoundException` —el 90% de los errores que hoy ve Cloud Logging— ya no aparece.
     *
     * <p>Es el caso de extremo a extremo: usa la excepción que Spring lanza de verdad, no una
     * simulada.
     *
     * @param output salida de consola capturada durante la prueba
     */
    @Test
    void unknownRoute_isLoggedAtInfoWithoutStackTrace(CapturedOutput output) {
        EntityExchangeResult<byte[]> result = webTestClient.get()
                .uri("/index.php")
                .exchange()
                .expectStatus().isEqualTo(404)
                .expectBody().returnResult();

        String requestId = result.getResponseHeaders().getFirst("X-Request-Id");
        assertThat(requestId).isNotBlank();
        assertThat(output.getOut())
                .contains("Solicitud a una ruta inexistente [requestId=" + requestId + "]")
                .doesNotContain("NoResourceFoundException");
    }

    // ── Utilidad ─────────────────────────────────────────────────────────────

    /**
     * Envía una solicitud autenticada a cameia-perfil, cuyo simulador tarda más que el timeout.
     *
     * @param idToken token que el mock de Firebase acepta como válido
     * @return la respuesta del Gateway, lista para sus aserciones
     */
    private WebTestClient.ResponseSpec requestSlowProfile(String idToken) throws Exception {
        FirebaseToken token = mock(FirebaseToken.class);
        when(token.getUid()).thenReturn("uid-" + idToken);
        when(token.getClaims()).thenReturn(Map.of());
        when(firebaseAuth.verifyIdToken(idToken)).thenReturn(token);

        slowDownstream.enqueue(new MockResponse().setResponseCode(200).setBody("tarde")
                .setHeadersDelay(2, TimeUnit.SECONDS));

        return webTestClient.get()
                .uri("/api/v1/profiles/me")
                .header("Authorization", "Bearer " + idToken)
                .exchange();
    }
}
