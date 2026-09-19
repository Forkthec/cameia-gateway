package tech.cameia.gateway.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas del nivel de log de {@link GlobalErrorHandler} (spec `CM-184-nivel-log-4xx`).
 *
 * <p>Son unitarias: instancian el manejador y le pasan un intercambio simulado, sin levantar el
 * contexto de Spring. Lo que se afirma es la salida de consola, que es exactamente lo que llega a
 * Cloud Logging.
 *
 * <p>El {@code X-Request-Id} llega en la solicitud para poder afirmar sobre un valor conocido, en
 * lugar del UUID que generaría el manejador.
 */
@ExtendWith(OutputCaptureExtension.class)
class GlobalErrorHandlerLoggingTest {

    /** Identificador conocido: aparece en la línea de log y permite afirmar sobre ella. */
    private static final String REQUEST_ID = "cm184-request-id";

    /** Mensaje con texto del cliente, como el de un {@code 404} real de Spring (REQ-LOG-05). */
    private static final String CLIENT_TEXT =
            "No static resource ../../etc/passwd for request 'http://externo'";

    private final GlobalErrorHandler handler = new GlobalErrorHandler();

    // ── REQ-LOG-02: una ruta inexistente deja una sola línea ─────────────────

    /**
     * Un {@code 404} sale en {@code INFO} y sin traza: es el caso de los escáneres de internet, el
     * 90% de los errores que hoy ve Cloud Logging.
     *
     * @param output salida de consola capturada durante la prueba
     */
    @Test
    void notFound_isLoggedAtInfoWithoutStackTrace(CapturedOutput output) {
        handle(new ResponseStatusException(HttpStatus.NOT_FOUND, CLIENT_TEXT));

        assertThat(output.getOut())
                .contains("Solicitud a una ruta inexistente [requestId=" + REQUEST_ID + "]")
                .doesNotContain("ERROR")
                .doesNotContain("at org.")
                .doesNotContain("at java.");
    }

    // ── REQ-LOG-03: otro 4xx deja una línea con su estado ────────────────────

    /**
     * Un {@code 4xx} que no es {@code 404} sí merece atención, pero tampoco necesita traza: basta
     * el estado y el tipo de la excepción.
     *
     * @param output salida de consola capturada durante la prueba
     */
    @Test
    void clientError_isLoggedAtWarnWithoutStackTrace(CapturedOutput output) {
        handle(new ResponseStatusException(HttpStatus.BAD_REQUEST, CLIENT_TEXT));

        assertThat(output.getOut())
                .contains("Solicitud rechazada con error del cliente [requestId=" + REQUEST_ID
                        + ", estado=400, tipo=ResponseStatusException]")
                .doesNotContain("ERROR")
                .doesNotContain("at org.")
                .doesNotContain("at java.");
    }

    // ── REQ-LOG-01: un 5xx conserva su traza ─────────────────────────────────

    /**
     * Un error del servidor no cambia: mismo mensaje, nivel {@code ERROR} y traza completa. Es lo
     * que hay que investigar y lo que las alertas de DevOps van a vigilar.
     *
     * @param output salida de consola capturada durante la prueba
     */
    @Test
    void serverError_isLoggedAtErrorWithStackTrace(CapturedOutput output) {
        handle(new IllegalStateException("fallo interno simulado"));

        assertThat(output.getOut())
                .contains("ERROR")
                .contains("Fallo al procesar la solicitud [requestId=" + REQUEST_ID + "]")
                .contains("java.lang.IllegalStateException: fallo interno simulado")
                .contains("at tech.cameia.gateway.exception.GlobalErrorHandlerLoggingTest");
    }

    // ── REQ-LOG-04: la respuesta no cambia ───────────────────────────────────

    /**
     * El cambio se ve solo en el log: el cliente recibe el mismo estado, el mismo tipo de contenido
     * y el mismo cuerpo del catálogo que antes de CM-184.
     */
    @Test
    void errorResponse_isUnchanged() {
        MockServerWebExchange exchange =
                handle(new ResponseStatusException(HttpStatus.NOT_FOUND, CLIENT_TEXT));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange.getResponse().getHeaders().getContentType().toString())
                .isEqualTo("application/json;charset=UTF-8");
        assertThat(exchange.getResponse().getBodyAsString().block())
                .isEqualTo("{\"code\":\"NOT_FOUND\",\"message\":\"Recurso no encontrado\"}");
    }

    // ── REQ-LOG-05: el log no repite texto del cliente ───────────────────────

    /**
     * El mensaje de un {@code 404} de Spring incluye la ruta que pidió el cliente. Ese texto no
     * entra al log, por la misma razón por la que no entra al cuerpo de la respuesta (REQ-12).
     *
     * @param output salida de consola capturada durante la prueba
     */
    @Test
    void clientErrorLog_doesNotLeakExceptionMessage(CapturedOutput output) {
        handle(new ResponseStatusException(HttpStatus.NOT_FOUND, CLIENT_TEXT));

        assertThat(output.getOut())
                .doesNotContain(CLIENT_TEXT)
                .doesNotContain("etc/passwd")
                .doesNotContain("http://externo");
    }

    // ── Utilidad ─────────────────────────────────────────────────────────────

    /**
     * Ejecuta el manejador sobre un intercambio simulado con el {@code X-Request-Id} conocido.
     *
     * @param ex excepción que recibe el manejador
     * @return el intercambio ya resuelto, para afirmar sobre su respuesta
     */
    private MockServerWebExchange handle(Throwable ex) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/ruta-que-no-existe").header("X-Request-Id", REQUEST_ID));
        handler.handle(exchange, ex).block();
        return exchange;
    }
}
