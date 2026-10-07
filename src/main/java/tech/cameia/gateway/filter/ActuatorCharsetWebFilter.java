package tech.cameia.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Declara el charset UTF-8 en las respuestas de actuator.
 *
 * <p>Spring Boot responde {@code /actuator/**} con {@code application/vnd.spring-boot.actuator.v3+json}
 * sin {@code charset}, y el estándar de seguridad exige declararlo en toda respuesta con cuerpo. No hay
 * propiedad que lo configure, así que este filtro lo completa justo antes de confirmar la respuesta,
 * conservando el tipo que eligió Spring.
 *
 * <p>Es un {@link WebFilter} y no un filtro global del Gateway porque actuator se atiende con su propio
 * {@code HandlerMapping}, antes que las rutas proxificadas. Solo actúa sobre {@code /actuator/**}: lo que
 * devuelven los microservicios se reenvía sin tocar.
 */
@Component
public class ActuatorCharsetWebFilter implements WebFilter, Ordered {

    private static final String ACTUATOR_PREFIX = "/actuator";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.equals(ACTUATOR_PREFIX) || path.startsWith(ACTUATOR_PREFIX + "/")) {
            exchange.getResponse().beforeCommit(() -> {
                declareUtf8(exchange.getResponse().getHeaders());
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }

    /** Añade {@code charset=UTF-8} al tipo de contenido si lo hay y no declara ya un charset. */
    private static void declareUtf8(HttpHeaders headers) {
        MediaType type = headers.getContentType();
        if (type != null && type.getCharset() == null) {
            headers.setContentType(new MediaType(type, StandardCharsets.UTF_8));
        }
    }

    /** Se registra antes que el resto para que su gancho exista cuando Spring confirme la respuesta. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
