package tech.cameia.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * Pruebas de {@link OidcSigningGlobalFilter} aislado, sin el resto de la cadena.
 *
 * <p>Hacen falta porque en la integración {@code FirebaseAuthGlobalFilter} ya borra el
 * {@code Authorization} del cliente: ahí un {@code header(...)} en lugar de {@code set} pasaría
 * inadvertido. Aquí el valor del cliente llega intacto al filtro de firma (REQ-OIDC-03).
 *
 * <p>También usan una URI {@code https://*.run.app} real en forma, que la integración con
 * {@code http://localhost:<puerto>} no cubre: {@code Route} le agrega {@code :443} y el audience
 * debe salir sin él (REQ-OIDC-02).
 */
class OidcSigningGlobalFilterUnitTest {

    private final OidcSigningGlobalFilter filter = new OidcSigningGlobalFilter(audience -> Mono.just("oidc-" + audience));

    @Test
    void clientAuthorization_isReplacedNotAppended() {
        ServerWebExchange exchange = exchangeWithRoute("https://cameia-perfil-abc.run.app/");

        ServerWebExchange forwarded = runFilter(exchange);

        assertThat(forwarded.getRequest().getHeaders().get("Authorization"))
                .containsExactly("Bearer oidc-https://cameia-perfil-abc.run.app");
    }

    @Test
    void withoutResolvedRoute_forwardsUnsigned() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/sin-ruta"));

        assertThat(runFilter(exchange).getRequest().getHeaders().get("Authorization")).isNull();
    }

    private ServerWebExchange exchangeWithRoute(String routeUri) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/profiles/me").header("Authorization", "Basic xyz"));
        Route route = Route.async().id("perfil").uri(URI.create(routeUri)).predicate(e -> true).build();
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        return exchange;
    }

    private ServerWebExchange runFilter(ServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = next -> {
            forwarded.set(next);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return forwarded.get();
    }
}
