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
import java.util.UUID;

@Component
public class FirebaseAuthGlobalFilter implements GlobalFilter, Ordered {

    static final String PLAN_CLAIM = "plan";
    static final String X_USER_ID = "X-User-Id";
    static final String X_USER_PLAN = "X-User-Plan";
    static final String X_REQUEST_ID = "X-Request-Id";

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
        // REQ-09: se resuelve antes de bifurcar, para que exista en rutas públicas y protegidas
        String requestId = resolveRequestId(exchange);
        String path = exchange.getRequest().getPath().value();

        if (isPublicPath(path)) {
            return chain.filter(withRequestId(exchange, requestId));
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return writeUnauthorized(exchange, "Token de acceso requerido");
        }

        String idToken = authHeader.substring(7);

        return Mono.fromCallable(() -> firebaseAuth.verifyIdToken(idToken))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(decodedToken -> chain.filter(propagateClaims(exchange, decodedToken, requestId)))
                .onErrorResume(FirebaseAuthException.class,
                        e -> writeUnauthorized(exchange, "Token de acceso inválido"));
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.contains(path);
    }

    /** REQ-09: conserva el identificador del cliente; si falta o está en blanco, genera uno. */
    private String resolveRequestId(ServerWebExchange exchange) {
        String incoming = exchange.getRequest().getHeaders().getFirst(X_REQUEST_ID);
        if (incoming == null || incoming.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return incoming;
    }

    /** Se usa set y no header: header añadiría un segundo valor si el cliente ya envió uno. */
    private ServerWebExchange withRequestId(ServerWebExchange exchange, String requestId) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.set(X_REQUEST_ID, requestId))
                .build();
        return exchange.mutate().request(request).build();
    }

    private ServerWebExchange propagateClaims(ServerWebExchange exchange, FirebaseToken token, String requestId) {
        ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate()
                .header(X_USER_ID, token.getUid())
                .headers(h -> {
                    h.remove("Authorization"); // REQ-07: no propagar el token original
                    h.set(X_REQUEST_ID, requestId);
                });

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
