package tech.cameia.gateway.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;

import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Formatea como {@code {"code":"...","message":"..."}} todo error que no resuelva el filtro de
 * autenticación: fallos del microservicio destino, rutas inexistentes y fallos del propio Gateway.
 *
 * <p>El cuerpo sale siempre de un catálogo fijo (spec §2.4). El detalle de la excepción va al log
 * con su {@code X-Request-Id}, nunca a la respuesta (REQ-12).
 *
 * <p>Orden {@code -1}: corre antes del {@code DefaultErrorWebExceptionHandler} de Spring.
 *
 * <p>El nivel del log depende del estado: un {@code 5xx} sale en {@code ERROR} con traza; un
 * {@code 404} en {@code INFO} y el resto de {@code 4xx} en {@code WARN}, ambos sin traza (CM-184).
 *
 * <p>Requisitos: {@code specs/CM-104-correcciones/spec.md} y
 * {@code specs/CM-184-nivel-log-4xx/spec.md}.
 */
@Component
@Order(-1)
public class GlobalErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    /**
     * Identificador de trazabilidad. Lo fija {@code FirebaseAuthGlobalFilter} en la respuesta; este
     * paquete no importa el filtro, así que comparten el nombre de la cabecera, no una constante.
     */
    static final String X_REQUEST_ID = "X-Request-Id";

    /** Cuerpo de una respuesta de error: código estable para el cliente y mensaje en español. */
    private record ErrorBody(String code, String message) {
    }

    /**
     * Catálogo cerrado de errores del Gateway (spec §2.4, REQ-11).
     *
     * <p>{@code UNAUTHORIZED} es una red de seguridad: el {@code 401} normal lo escribe el filtro de
     * autenticación y nunca llega aquí. La entrada evita que una {@code ResponseStatusException(401)}
     * salga con código {@code INTERNAL_ERROR}.
     */
    private static final Map<HttpStatus, ErrorBody> CATALOG = Map.of(
            HttpStatus.UNAUTHORIZED, new ErrorBody("AUTH_REQUIRED", "Token de acceso requerido o inválido"),
            HttpStatus.NOT_FOUND, new ErrorBody("NOT_FOUND", "Recurso no encontrado"),
            HttpStatus.BAD_GATEWAY, new ErrorBody("BAD_GATEWAY", "Respuesta inválida del servicio destino"),
            HttpStatus.SERVICE_UNAVAILABLE, new ErrorBody("SERVICE_UNAVAILABLE", "Servicio destino no disponible"),
            HttpStatus.GATEWAY_TIMEOUT, new ErrorBody("GATEWAY_TIMEOUT", "El servicio destino no respondió a tiempo")
    );

    /** Cuerpo para cualquier estado que no esté en el catálogo. */
    private static final ErrorBody DEFAULT_BODY = new ErrorBody("INTERNAL_ERROR", "Error interno del gateway");

    /**
     * {@code MediaType.APPLICATION_JSON} no declara {@code charset} (la constante de Spring que sí
     * lo hacía, {@code APPLICATION_JSON_UTF8}, se eliminó en Spring 5.2). ASVS 4.1.1 exige el
     * parámetro {@code charset} explícito en cada respuesta con cuerpo.
     */
    private static final MediaType APPLICATION_JSON_UTF8 =
            new MediaType("application", "json", StandardCharsets.UTF_8);

    /**
     * Registra el fallo con el nivel que corresponde a su estado y responde con el cuerpo del catálogo.
     *
     * @param exchange intercambio HTTP en el que ocurrió el error
     * @param ex       excepción original
     * @return la escritura de la respuesta de error, o el mismo error si la respuesta ya se envió
     */
    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex); // el estado ya salió hacia el cliente: no se puede reescribir
        }

        String requestId = resolveRequestId(exchange);
        HttpStatus status = resolveStatus(ex);
        // REQ-12: el detalle va al log, nunca al cuerpo de la respuesta
        logFailure(requestId, status, ex);

        ErrorBody body = CATALOG.getOrDefault(status, DEFAULT_BODY);
        // Sin escape de JSON: code y message son constantes del catálogo, sin texto de origen externo
        byte[] bytes = """
                {"code":"%s","message":"%s"}""".formatted(body.code(), body.message())
                .getBytes(StandardCharsets.UTF_8);

        response.setStatusCode(status);
        response.getHeaders().setContentType(APPLICATION_JSON_UTF8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    /**
     * Obtiene el {@code X-Request-Id} con el que se registra el error (REQ-12).
     *
     * <p>Si el filtro corrió, ya lo dejó en la respuesta y es el mismo que recibió el microservicio.
     * Si no corrió, como en un {@code 404} sin ruta, se usa el del cliente o se genera uno, y se
     * fija en la respuesta para que el cliente también lo reciba.
     *
     * @param exchange intercambio HTTP en el que ocurrió el error
     * @return el identificador de trazabilidad de la solicitud
     */
    private String resolveRequestId(ServerWebExchange exchange) {
        String fromFilter = exchange.getResponse().getHeaders().getFirst(X_REQUEST_ID);
        if (fromFilter != null && !fromFilter.isBlank()) {
            return fromFilter;
        }
        String incoming = exchange.getRequest().getHeaders().getFirst(X_REQUEST_ID);
        String requestId = incoming == null || incoming.isBlank() ? UUID.randomUUID().toString() : incoming;
        exchange.getResponse().getHeaders().set(X_REQUEST_ID, requestId);
        return requestId;
    }

    /**
     * Registra el fallo con el nivel que corresponde a su estado HTTP (REQ-LOG-01 de CM-184).
     *
     * <p>Un error del servidor (5xx) conserva su traza completa: es lo que hay que investigar. Un
     * error del cliente (4xx) es esperado —la mayoría son escáneres de internet pidiendo rutas que
     * no existen— y su traza no aporta nada, así que sale una sola línea.
     *
     * @param requestId identificador de trazabilidad de la solicitud
     * @param status    estado HTTP con el que se responde
     * @param ex        excepción original
     */
    private void logFailure(String requestId, HttpStatus status, Throwable ex) {
        if (status.is4xxClientError()) {
            logClientError(requestId, status, ex);
        } else {
            log.error("Fallo al procesar la solicitud [requestId={}]", requestId, ex);
        }
    }

    /**
     * Registra un error del cliente en una sola línea, sin traza (REQ-LOG-02, REQ-LOG-03).
     *
     * <p>No se registra el mensaje de la excepción ni la URL: en un {@code 404} incluyen texto que
     * envió el cliente, y ese texto no debe llegar al log sin control (REQ-LOG-05). Del error sale
     * solo el nombre de su clase, que es texto del código.
     *
     * <p>El {@code 404} baja a {@code INFO} porque son cientos al día y llenarían {@code WARN};
     * cualquier otro {@code 4xx} sí merece atención, como el frontend llamando mal a una ruta.
     *
     * @param requestId identificador de trazabilidad de la solicitud
     * @param status    estado HTTP con el que se responde
     * @param ex        excepción original
     */
    private void logClientError(String requestId, HttpStatus status, Throwable ex) {
        if (status == HttpStatus.NOT_FOUND) {
            log.info("Solicitud a una ruta inexistente [requestId={}]", requestId);
        } else {
            log.warn("Solicitud rechazada con error del cliente [requestId={}, estado={}, tipo={}]",
                    requestId, status.value(), ex.getClass().getSimpleName());
        }
    }

    /**
     * Traduce la excepción a un estado HTTP (REQ-11, plan §3.5).
     *
     * <p>Una {@link ResponseStatusException} conserva su estado: Spring Cloud Gateway ya traduce así
     * algunos casos, como el timeout de respuesta.
     *
     * @param ex excepción original
     * @return el estado de la excepción si lo trae, el del fallo de red si se reconoce, o {@code 500}
     */
    private HttpStatus resolveStatus(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            HttpStatus status = HttpStatus.resolve(rse.getStatusCode().value());
            return status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        // Recorre las causas del error y las compara haciendo uso de networkFailureStatus para ver que codigo devolver
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            HttpStatus status = networkFailureStatus(cause);
            if (status != null) {
                return status;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /**
     * Reconoce un fallo de comunicación con el microservicio destino. Se aplica a cada causa de la
     * cadena, porque las librerías de red suelen envolver la excepción original.
     *
     * @param error una excepción de la cadena de causas
     * @return {@code 504}, {@code 503} o {@code 502} según el fallo, o {@code null} si no es de red
     */
    private HttpStatus networkFailureStatus(Throwable error) {
        if (error instanceof TimeoutException) {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
        if (error instanceof ConnectException || error instanceof UnknownHostException) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        // PrematureCloseException y afines: el destino cortó o respondió algo ininterpretable
        if (error.getClass().getName().startsWith("reactor.netty.")) {
            return HttpStatus.BAD_GATEWAY;
        }
        return null;
    }
}
