package tech.cameia.gateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tech.cameia.gateway.config.TestFirebaseConfig;

/**
 * Vigila, con sockets reales, lo que el Gateway responde a las peticiones que Reactor Netty o el
 * adaptador de WebFlux rechazan antes de que corra cualquier filtro propio. Esas respuestas salen
 * con el estado HTTP correcto y el cuerpo vacío, sin {@code code} ni {@code X-Request-Id}. Si una
 * actualización de la librería cambia el estado o agrega cuerpo, estas pruebas fallan y obligan a
 * revisar la tabla de rechazos sin código del catálogo de errores.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = TestFirebaseConfig.class
)
@ActiveProfiles("test")
class NettyDecodingRejectionsTest {

    private static final String HEALTH_PATH = "/api/v1/users/health";

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("CAMEIA_CUENTAS_URL", () -> "http://localhost:1");
        registry.add("CAMEIA_PERFIL_URL", () -> "http://localhost:1");
        registry.add("CAMEIA_ENTREVISTA_URL", () -> "http://localhost:1");
    }

    @Test
    void controlCharacterInHeader_returns400WithEmptyBody() throws IOException {
        RawHttpClient.Reply reply = send("GET", HEALTH_PATH, List.of("X-Foo: a\007"));

        assertRejectedWithoutBody(reply, 400);
    }

    @Test
    void oversizedHeader_returns431WithEmptyBody() throws IOException {
        RawHttpClient.Reply reply = send("GET", HEALTH_PATH, List.of("X-Foo: " + "a".repeat(9000)));

        assertRejectedWithoutBody(reply, 431);
    }

    @Test
    void headerBelowLimit_isNotRejected() throws IOException {
        RawHttpClient.Reply reply = send("GET", HEALTH_PATH, List.of("X-Foo: " + "a".repeat(7000)));

        // La ruta es de Caso A y no lleva token: responde el filtro del Gateway, no el decodificador.
        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.body()).contains("\"code\":\"AUTH_REQUIRED\"");
    }

    @Test
    void oversizedUrl_returns414WithEmptyBody() throws IOException {
        RawHttpClient.Reply reply = send("GET", "/" + "a".repeat(9000), List.of());

        assertRejectedWithoutBody(reply, 414);
    }

    @Test
    void malformedQuery_returns400WithEmptyBody() throws IOException {
        RawHttpClient.Reply reply = send("GET", "/api/v1/profiles/professional-roles?lang=%zz", List.of());

        assertRejectedWithoutBody(reply, 400);
    }

    @Test
    void malformedPath_returns400WithEmptyBody() throws IOException {
        RawHttpClient.Reply reply = send("GET", "/api/v1/profiles/%zz", List.of());

        assertRejectedWithoutBody(reply, 400);
    }

    @Test
    void headerValueAtLimit_isNotRejected() throws IOException {
        // Con los encabezados Host y Connection que agrega RawHttpClient, 8153 bytes es el mayor valor admitido.
        RawHttpClient.Reply reply = send("GET", HEALTH_PATH, List.of("X-Foo: " + "a".repeat(8153)));

        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.body()).contains("\"code\":\"AUTH_REQUIRED\"");
    }

    @Test
    void manySmallHeadersBelowTotalLimit_areNotRejected() throws IOException {
        // 300 encabezados de ~14 bytes suman ~4 KB: el límite de Netty es el total, no el número de encabezados.
        RawHttpClient.Reply reply = send("GET", HEALTH_PATH, smallHeaders(300));

        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.body()).contains("\"code\":\"AUTH_REQUIRED\"");
    }

    @Test
    void manySmallHeadersAboveTotalLimit_returns431WithEmptyBody() throws IOException {
        // Ningún encabezado pasa de 20 bytes, pero 700 suman ~9,7 KB: el total supera los 8192 bytes.
        RawHttpClient.Reply reply = send("GET", HEALTH_PATH, smallHeaders(700));

        assertRejectedWithoutBody(reply, 431);
    }

    @Test
    void headerValueOneByteOverLimit_returns431WithEmptyBody() throws IOException {
        RawHttpClient.Reply reply = send("GET", HEALTH_PATH, List.of("X-Foo: " + "a".repeat(8154)));

        assertRejectedWithoutBody(reply, 431);
    }

    @Test
    void urlAtLimit_isNotRejected() throws IOException {
        // La línea de petición completa admite 4096 bytes: con «GET » y « HTTP/1.1», la ruta puede medir 4083.
        RawHttpClient.Reply reply = send("GET", "/" + "a".repeat(4082), List.of());

        assertThat(reply.status()).isEqualTo(404);
        assertThat(reply.body()).contains("\"code\":\"NOT_FOUND\"");
    }

    @Test
    void urlOneByteOverLimit_returns414WithEmptyBody() throws IOException {
        RawHttpClient.Reply reply = send("GET", "/" + "a".repeat(4083), List.of());

        assertRejectedWithoutBody(reply, 414);
    }

    @Test
    void spaceInUrl_returns400WithEmptyBody() throws IOException {
        RawHttpClient.Reply reply = send("GET", HEALTH_PATH + " x", List.of());

        assertRejectedWithoutBody(reply, 400);
    }

    @Test
    void contentLengthWithChunkedEncoding_returns400WithEmptyBody() throws IOException {
        RawHttpClient.Reply reply = send("POST", "/api/v1/users",
                List.of("Content-Length: 4", "Transfer-Encoding: chunked"));

        assertRejectedWithoutBody(reply, 400);
    }

    @Test
    void negativeContentLength_returns400WithEmptyBody() throws IOException {
        RawHttpClient.Reply reply = send("POST", "/api/v1/users", List.of("Content-Length: -1"));

        assertRejectedWithoutBody(reply, 400);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedRequests")
    void rawMalformedRequest_returns400WithEmptyBody(String description, String request) throws IOException {
        RawHttpClient.Reply reply = new RawHttpClient(port).sendRaw(request);

        assertRejectedWithoutBody(reply, 400);
    }

    static Stream<Arguments> malformedRequests() {
        String start = "GET " + HEALTH_PATH + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n";
        String post = "POST /api/v1/users HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n";
        String host = "Host: localhost\r\nConnection: close\r\n\r\n";
        return Stream.of(
                Arguments.of("NUL in a header value", start + "X-A: a\u0000b\r\n\r\n"),
                Arguments.of("DEL in a header value", start + "X-A: a\u007fb\r\n\r\n"),
                Arguments.of("header line without a colon", start + "X-A\r\n\r\n"),
                Arguments.of("space in a header name", start + "X A: a\r\n\r\n"),
                Arguments.of("incomplete percent escape in the query", "GET /api/v1/profiles?a=%0 HTTP/1.1\r\n" + host),
                Arguments.of("lone percent sign in the path", "GET /api/v1/profiles/% HTTP/1.1\r\n" + host),
                Arguments.of("empty request target", "GET  HTTP/1.1\r\n" + host),
                Arguments.of("unreadable HTTP version", "GET / HTTP/9.9x\r\n" + host),
                Arguments.of("non-numeric Content-Length", post + "Content-Length: abc\r\n\r\n"),
                Arguments.of("repeated Content-Length with different values",
                        post + "Content-Length: 4\r\nContent-Length: 5\r\n\r\nabcd"),
                Arguments.of("chunk size that is not hexadecimal",
                        post + "Transfer-Encoding: chunked\r\n\r\nzz\r\nabcd\r\n0\r\n\r\n"));
    }

    @Test
    void gatewayKeepsServing_afterRejections() throws IOException {
        send("GET", HEALTH_PATH, List.of("X-Foo: a\007"));
        send("GET", "/" + "a".repeat(9000), List.of());
        send("GET", "/api/v1/profiles/%zz", List.of());

        RawHttpClient.Reply reply = send("GET", "/actuator/health", List.of());

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("\"status\":\"UP\"");
    }

    private RawHttpClient.Reply send(String method, String target, List<String> headers) throws IOException {
        return new RawHttpClient(port).send(method, target, headers);
    }

    /** {@code count} encabezados de unos 14 bytes cada uno: ninguno es grande, solo su suma. */
    private static List<String> smallHeaders(int count) {
        return IntStream.range(0, count).mapToObj(i -> "X-H" + i + ": aaaa").toList();
    }

    /**
     * El rechazo conserva el estado esperado, no trae cuerpo ni {@code code} y tampoco
     * {@code X-Request-Id}, porque ocurre antes de que corra cualquier filtro del Gateway.
     */
    private static void assertRejectedWithoutBody(RawHttpClient.Reply reply, int expectedStatus) {
        assertThat(reply.status()).isEqualTo(expectedStatus);
        assertThat(reply.body()).isEmpty();
        assertThat(reply.headers()).doesNotContainKey("x-request-id");
        assertThat(reply.headers()).containsEntry("content-length", "0");
    }
}
