package tech.cameia.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Declara el charset UTF-8 en las respuestas de texto de actuator.
 *
 * <p>Spring Boot responde los puntos de actuator con {@code application/vnd.spring-boot.actuator.v3+json}
 * sin {@code charset}, y el estándar de seguridad exige declararlo en toda respuesta con cuerpo. No hay
 * propiedad que lo configure, así que este filtro lo completa justo antes de confirmar la respuesta,
 * conservando el tipo que eligió Spring. Solo toca tipos de texto (JSON y {@code text/*}): un cuerpo
 * binario, como un volcado de memoria, no tiene juego de caracteres.
 *
 * <p>Es un {@link WebFilter} y no un filtro global del Gateway porque actuator se atiende con su propio
 * {@code HandlerMapping}, antes que las rutas proxificadas. Solo actúa bajo la ruta base de actuator
 * ({@code management.endpoints.web.base-path}): lo que devuelven los microservicios se reenvía sin tocar.
 */
@Component
public class ActuatorCharsetWebFilter implements WebFilter, Ordered {

    private final PathPattern actuatorPaths;

    /**
     * Construye el filtro para la ruta base de actuator.
     *
     * @param basePath ruta base de actuator, por defecto {@code /actuator}; una barra final se ignora
     * @throws IllegalStateException si la ruta base es la raíz: actuator compartiría el espacio de rutas
     *                               con las rutas proxificadas y el filtro no podría distinguirlas
     */
    public ActuatorCharsetWebFilter(@Value("${management.endpoints.web.base-path:/actuator}") String basePath) {
        String cleanPath = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        if (cleanPath.isEmpty()) {
            throw new IllegalStateException(
                    "management.endpoints.web.base-path no puede ser la raíz en el Gateway: "
                            + "actuator se mezclaría con las rutas proxificadas");
        }
        // «/**» también coincide con la ruta base sola; el patrón ignora los parámetros de matriz (;x=1).
        this.actuatorPaths = PathPatternParser.defaultInstance.parse(cleanPath + "/**");
    }

    /**
     * Si la petición va a actuator, registra un gancho que completa el charset al confirmar la respuesta.
     *
     * <p>La ruta se compara sin el prefijo de la aplicación ({@code spring.webflux.base-path}), igual que
     * la resuelve Spring al elegir el punto de actuator. Las demás peticiones pasan sin cambios.
     *
     * @param exchange petición y respuesta en curso
     * @param chain    resto de la cadena de filtros
     * @return la continuación de la cadena
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (actuatorPaths.matches(exchange.getRequest().getPath().pathWithinApplication())) {
            exchange.getResponse().beforeCommit(() -> {
                declareUtf8(exchange.getResponse().getHeaders());
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }

    /** Añade {@code charset=UTF-8} si el tipo de contenido es de texto y no declara ya un charset. */
    private static void declareUtf8(HttpHeaders headers) {
        MediaType type = headers.getContentType();
        if (type != null && type.getCharset() == null && isText(type)) {
            headers.setContentType(new MediaType(type, StandardCharsets.UTF_8));
        }
    }

    /** Indica si el tipo transporta texto: {@code text/*}, {@code application/json} o {@code application/*+json}. */
    private static boolean isText(MediaType type) {
        String subtype = type.getSubtype();
        return "text".equals(type.getType()) || "json".equals(subtype) || subtype.endsWith("+json");
    }

    /**
     * Va antes que los demás {@code WebFilter} para que el gancho quede registrado aunque un filtro
     * posterior responda sin llegar a actuator. El orden no cambia cuándo corre el gancho: siempre al
     * confirmar la respuesta.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
