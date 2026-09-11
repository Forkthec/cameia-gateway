package tech.cameia.gateway.filter;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
public class FirebaseAuthGlobalFilter implements GlobalFilter, Ordered {

    static final String PLAN_CLAIM = "plan";
    static final String X_USER_ID = "X-User-Id";
    static final String X_USER_PLAN = "X-User-Plan";

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/webhooks/wompi",
            "/actuator/health",
            "/actuator/info"
    );

    private final FirebaseAuth firebaseAuth;

    public FirebaseAuthGlobalFilter(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return writeUnauthorized(exchange, "Token de acceso requerido");
        }

        String idToken = authHeader.substring(7);

        return Mono.fromCallable(() -> firebaseAuth.verifyIdToken(idToken))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(decodedToken -> chain.filter(propagateClaims(exchange, decodedToken)))
                .onErrorResume(FirebaseAuthException.class,
                        e -> writeUnauthorized(exchange, "Token de acceso inválido"));
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.contains(path);
    }

    private ServerWebExchange propagateClaims(ServerWebExchange exchange, FirebaseToken token) {
        ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate()
                .header(X_USER_ID, token.getUid())
                .headers(h -> h.remove("Authorization")); // REQ-07: no propagar el token original

        Object planClaim = token.getClaims().get(PLAN_CLAIM);
        if (planClaim != null) {
            requestBuilder.header(X_USER_PLAN, planClaim.toString());
        }

        return exchange.mutate().request(requestBuilder.build()).build();
    }

    private Mono<Void> writeUnauthorized(ServerWebExchange exchange, String message) {
        String body = """
                {"code":"AUTH_REQUIRED","message":"%s"}""".formatted(message);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
