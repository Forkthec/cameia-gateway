package co.edu.unicauca.cameia.gateway.exception;

import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Formatea todos los errores del gateway como JSON uniforme.
 * Aplica para errores no capturados por el filtro (502, 503, 504, etc.).
 * Orden negativo = corre antes del DefaultErrorWebExceptionHandler de Spring.
 */
@Component
@Order(-1)
public class GlobalErrorHandler implements WebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        HttpStatus status = resolveStatus(ex);
        String code = status.name();
        String message = ex.getMessage() != null ? ex.getMessage() : "Error interno del gateway";

        String body = """
                {"code":"%s","message":"%s"}""".formatted(code, sanitize(message));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private HttpStatus resolveStatus(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            return HttpStatus.resolve(rse.getStatusCode().value()) != null
                    ? HttpStatus.resolve(rse.getStatusCode().value())
                    : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private String sanitize(String message) {
        return message.replace("\"", "'").replace("\n", " ");
    }
}
