package tech.cameia.gateway.server;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import tech.cameia.gateway.config.TestFirebaseConfig;

/**
 * Fija que el Gateway no pone tope al cuerpo ni interpreta el contenido de la petición: reenvía el
 * cuerpo completo y el {@code Accept} tal cual, y devuelve sin tocarlo el error que responde el
 * servicio destino. Los topes de tamaño de un {@code multipart} y el rechazo de un {@code Accept}
 * ilegible son del servicio destino.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = TestFirebaseConfig.class
)
@ActiveProfiles("test")
class BodyForwardingTest {

    private static final int ELEVEN_MIB = 11 * 1024 * 1024;
    private static final String BOUNDARY = "cameia-boundary";
    private static final String TOO_LARGE =
            "{\"code\":\"REQUEST_BODY_TOO_LARGE\",\"message\":\"Cuerpo de la petición demasiado grande.\"}";

    static MockWebServer downstream;

    @LocalServerPort
    int port;

    @BeforeAll
    static void startDownstream() throws IOException {
        downstream = new MockWebServer();
        downstream.start();
    }

    @AfterAll
    static void stopDownstream() throws IOException {
        downstream.shutdown();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        String url = "http://localhost:" + downstream.getPort();
        registry.add("CAMEIA_CUENTAS_URL", () -> url);
        registry.add("CAMEIA_PERFIL_URL", () -> url);
        registry.add("CAMEIA_ENTREVISTA_URL", () -> url);
    }

    @Test
    void multipartBodyOverTenMiB_isForwardedWhole_andTheDownstreamErrorIsReturnedUntouched() throws Exception {
        downstream.enqueue(new MockResponse()
                .setResponseCode(413)
                .setHeader("Content-Type", "application/problem+json;charset=UTF-8")
                .setBody(TOO_LARGE));
        byte[] body = multipartBody(ELEVEN_MIB);

        // POST /api/v1/users es de Caso B: no necesita token de Firebase para llegar al destino.
        client().post().uri("/api/v1/users")
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody(String.class).isEqualTo(TOO_LARGE);

        RecordedRequest received = downstream.takeRequest(10, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.getBodySize()).isEqualTo(body.length);
    }

    @Test
    void unreadableAcceptHeader_isForwardedAndTheDownstreamAnswerIsReturned() throws Exception {
        downstream.enqueue(new MockResponse()
                .setResponseCode(406)
                .setHeader("Content-Type", "application/problem+json;charset=UTF-8")
                .setBody("{\"code\":\"MEDIA_TYPE_NOT_ACCEPTABLE\"}"));

        client().post().uri("/api/v1/users")
                .header("Accept", ";;;")
                .exchange()
                .expectStatus().isEqualTo(406)
                .expectBody(String.class).isEqualTo("{\"code\":\"MEDIA_TYPE_NOT_ACCEPTABLE\"}");

        RecordedRequest received = downstream.takeRequest(10, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.getHeader("Accept")).isEqualTo(";;;");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** Un {@code multipart} válido cuya única parte de archivo mide {@code fileBytes} bytes. */
    private static byte[] multipartBody(int fileBytes) {
        byte[] head = ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(UTF_8);
        byte[] tail = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(UTF_8);
        byte[] body = new byte[head.length + fileBytes + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        Arrays.fill(body, head.length, head.length + fileBytes, (byte) 'a');
        System.arraycopy(tail, 0, body, head.length + fileBytes, tail.length);
        return body;
    }
}
