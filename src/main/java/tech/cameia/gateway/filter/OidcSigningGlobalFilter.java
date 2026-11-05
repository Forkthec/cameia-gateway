package tech.cameia.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import java.net.URI;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * Firma cada llamada saliente con un token OIDC cuyo audience es el microservicio destino
 * ({@code AGENTS.md} §6.3, {@code specs/CM-104-correcciones-OIDC/}).
 *
 * <p>No distingue Caso A de Caso B: firma toda ruta resuelta, así que una ruta nueva del YAML sale
 * firmada sin tocar Java (REQ-OIDC-01, REQ-NF-OIDC-01). Si no obtiene el token, la petición no sale
 * (fail closed, REQ-OIDC-04).
 *
 * <p>No es {@code @Component}: lo crea {@code OidcConfig} solo con la firma encendida.
 */
public class OidcSigningGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(OidcSigningGlobalFilter.class);

    /**
     * Antes del reenvío. Verificado en el jar de Spring Cloud Gateway 5.0.3: {@code NettyRoutingFilter}
     * usa {@code LOWEST_PRECEDENCE} y {@code WebsocketRoutingFilter} {@code LOWEST_PRECEDENCE - 1}.
     * Con {@code - 2} el orden frente a los dos es explícito, sin empates.
     */
    static final int ORDER = Ordered.LOWEST_PRECEDENCE - 2;

    private static final String X_REQUEST_ID = "X-Request-Id";

    private final OidcTokenSource tokenSource;

    /**
     * @param tokenSource fuente de tokens OIDC; en pruebas, una falsa
     */
    public OidcSigningGlobalFilter(OidcTokenSource tokenSource) {
        this.tokenSource = tokenSource;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * Pide el token para el destino de la ruta y lo pone en {@code Authorization}.
     *
     * <p>El {@code onErrorResume} va <em>antes</em> del {@code flatMap}: así solo cubre la obtención
     * del token. Un fallo del microservicio (timeout, conexión rechazada) sigue llegando a
     * {@code GlobalErrorHandler} con su propio código, y no se disfraza de fallo de firma.
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange); // sin ruta resuelta no hay destino que firmar
        }
        String audience = audienceOf(route.getUri());

        return tokenSource.tokenFor(audience)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("La fuente OIDC no entregó token")))
                .onErrorResume(error -> failClosed(exchange, audience, error))
                .flatMap(token -> chain.filter(withOidcToken(exchange, token)));
    }

    /**
     * Audience = {@code esquema://host[:puerto]}, sin path y sin barra final (REQ-OIDC-02). Se
     * reconstruye desde sus partes para que una barra de más en la variable de entorno no rompa la
     * llamada (GW-TBD-12).
     *
     * <p>El puerto por defecto del esquema se omite: {@code Route} de Spring Cloud Gateway lo
     * <em>agrega</em> cuando el YAML no lo trae, así que {@code https://x.run.app} llega aquí como
     * {@code https://x.run.app:443}, y ese audience no coincide con el que espera Cloud Run.
     *
     * @param uri {@code uri} de la ruta, ya normalizada por {@code Route}
     * @return el audience
     */
    static String audienceOf(URI uri) {
        String base = uri.getScheme() + "://" + uri.getHost();
        return isDefaultPort(uri) ? base : base + ":" + uri.getPort();
    }

    private static boolean isDefaultPort(URI uri) {
        int port = uri.getPort();
        return port == -1
                || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443)
                || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80);
    }

    /**
     * El {@code Authorization} saliente es <b>solo</b> el token OIDC: {@code set} reemplaza lo que
     * haya, {@code header(...)} añadiría un segundo valor (REQ-OIDC-03).
     */
    private ServerWebExchange withOidcToken(ServerWebExchange exchange, String token) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .build();
        return exchange.mutate().request(request).build();
    }

    /**
     * La causa va al log con su {@code X-Request-Id} y el audience; al cliente solo le llega el
     * {@code 503} del catálogo de {@code GlobalErrorHandler}. La excepción va <b>sin reason</b>:
     * cualquier texto podría acabar en la respuesta (REQ-OIDC-09).
     */
    private Mono<String> failClosed(ServerWebExchange exchange, String audience, Throwable error) {
        String requestId = exchange.getRequest().getHeaders().getFirst(X_REQUEST_ID);
        log.error("No se pudo firmar la llamada saliente [requestId={}, audience={}]", requestId, audience, error);
        return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
