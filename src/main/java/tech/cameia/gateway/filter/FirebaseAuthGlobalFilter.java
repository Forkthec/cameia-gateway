package tech.cameia.gateway.filter;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import static java.util.stream.Collectors.joining;

/**
 * Filtro global de autenticación del Gateway: la única pieza que decide quién es el usuario
 * y la única fuente de las cabeceras de identidad que reciben los microservicios.
 *
 * <p>Atiende dos casos, descritos en {@code AGENTS.md} §6:
 * <ul>
 *   <li><b>Caso A — ruta protegida.</b> Verifica el ID Token de Firebase de la cabecera
 *       {@code Authorization}. Si es válido, borra toda cabecera {@code X-User-*} que haya enviado
 *       el cliente, escribe las del token y retira el {@code Authorization} original. Si falta o es
 *       inválido, responde {@code 401 AUTH_REQUIRED} sin reenviar nada.</li>
 *   <li><b>Caso B — ruta pública</b> ({@code PUBLIC_PATHS}). No verifica token, pero igualmente
 *       borra toda cabecera {@code X-User-*} del cliente: no hay usuario autenticado y el
 *       microservicio no debe creer que lo hay.</li>
 * </ul>
 *
 * <p>En los dos casos garantiza un {@code X-Request-Id} en la solicitud reenviada.
 *
 * <p>Las cabeceras se fijan siempre con {@code set} y nunca con {@code header(...)}: este último
 * <em>añade</em> un segundo valor cuando el cliente ya envió la misma cabecera, y deja indefinido
 * cuál lee el microservicio. Es la regla que impide suplantar a otro usuario (REQ-07).
 *
 * <p>Este filtro no aplica reglas de negocio ni interpreta los claims: los propaga tal como vienen
 * en el token (REQ-08).
 *
 * <p>Requisitos: {@code specs/CM-104-correcciones/spec.md}.
 */
@Component
public class FirebaseAuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthGlobalFilter.class);

    /** Identificador del usuario: el {@code uid} del token de Firebase. Siempre presente en Caso A. */
    static final String X_USER_ID = "X-User-Id";

    /** Correo del usuario, del claim {@code email}. Se omite si el token no lo trae (GW-TBD-07). */
    static final String X_USER_EMAIL = "X-User-Email";

    /** Roles del usuario, del claim {@code roles}, separados por comas. Se omite si el claim falta. */
    static final String X_USER_ROLES = "X-User-Roles";

    /** Plan del usuario, del custom claim {@code plan}. Se omite si el claim falta; nunca llega vacío. */
    static final String X_USER_PLAN = "X-User-Plan";

    /**
     * Identificador de trazabilidad de la solicitud. Se conserva el del cliente o se genera uno.
     * Es trazabilidad, no identidad: por eso no pertenece a {@code IDENTITY_HEADERS}.
     */
    static final String X_REQUEST_ID = "X-Request-Id";

    /** Nombre del custom claim de Firebase con el plan del usuario. Lo escribe cameia-cuentas. */
    static final String PLAN_CLAIM = "plan";

    /** Nombre del custom claim de Firebase con los roles del usuario. */
    static final String ROLES_CLAIM = "roles";

    /** Esquema de la cabecera {@code Authorization} que lleva el ID Token de Firebase, con su espacio. */
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Cabeceras que solo el Gateway emite. Cualquier valor que llegue del cliente con uno de estos
     * nombres se descarta antes de reenviar, tanto en rutas protegidas como públicas (REQ-07, REQ-10).
     */
    private static final Set<String> IDENTITY_HEADERS = Set.of(
            X_USER_ID, X_USER_EMAIL, X_USER_ROLES, X_USER_PLAN
    );

    /**
     * Rutas que no exigen token de Firebase (Caso B). La comparación es exacta, sin comodines.
     *
     * <p>Las entradas de Actuator son inertes: Actuator lo atiende su propio handler y este filtro
     * nunca lo ve. Se retiran en T-31 (REQ-13).
     */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/webhooks/wompi",
            "/actuator/health",
            "/actuator/info"
    );

    private final FirebaseAuth firebaseAuth;

    /**
     * @param firebaseAuth cliente del Admin SDK de Firebase con el que se verifican los ID Tokens
     */
    public FirebaseAuthGlobalFilter(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    /**
     * Máxima precedencia: la identidad debe quedar resuelta y saneada antes de que corra cualquier
     * otro filtro o se reenvíe la solicitud.
     *
     * @return {@link Ordered#HIGHEST_PRECEDENCE}
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * Decide entre Caso A y Caso B, y reenvía la solicitud saneada o responde {@code 401}.
     *
     * <p>La verificación del token es bloqueante, así que se ejecuta en
     * {@link Schedulers#boundedElastic()} para no ocupar el hilo de evento reactivo.
     *
     * @param exchange intercambio HTTP de la solicitud entrante
     * @param chain    resto de la cadena de filtros del Gateway
     * @return la continuación de la cadena con la solicitud saneada, o la escritura de un {@code 401}
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // REQ-09: se resuelve antes de bifurcar, para que exista en rutas públicas y protegidas
        String requestId = resolveRequestId(exchange);
        String path = exchange.getRequest().getPath().value();

        if (isPublicPath(path)) {
            return chain.filter(withoutIdentity(exchange, requestId));
        }

        String idToken = extractIdToken(exchange);
        if (idToken == null) {
            // REQ-02: se rechaza antes de llamar a Firebase, que con un token vacío lanzaría otra excepción
            return writeUnauthorized(exchange, "Token de acceso requerido", requestId);
        }

        // El rechazo se captura antes del flatMap: así solo cubre la verificación del token, y un
        // fallo al reenviar al microservicio nunca se confunde con un token inválido (plan §3.4)
        return Mono.fromCallable(() -> firebaseAuth.verifyIdToken(idToken))
                .subscribeOn(Schedulers.boundedElastic())
                // tras escribir el 401 se completa vacío: el flatMap no corre y nada se reenvía
                .onErrorResume(this::isTokenRejection,
                        e -> writeUnauthorized(exchange, "Token de acceso inválido", requestId)
                                .then(Mono.<FirebaseToken>empty()))
                .flatMap(decodedToken -> chain.filter(withIdentity(exchange, decodedToken, requestId)));
    }

    /**
     * Decide si un fallo de la verificación es culpa del token y merece {@code 401} (REQ-02).
     *
     * <p>No abarca {@link Throwable} en bruto: un fallo de red al descargar las claves públicas de
     * Firebase es un problema del Gateway, no del cliente, y debe seguir llegando al manejador global.
     *
     * @param error excepción lanzada al verificar el ID Token
     * @return {@code true} si es {@link FirebaseAuthException} o {@link IllegalArgumentException}
     */
    private boolean isTokenRejection(Throwable error) {
        return error instanceof FirebaseAuthException || error instanceof IllegalArgumentException;
    }

    /**
     * @param path path de la solicitud, sin query string
     * @return {@code true} si la ruta está en {@code PUBLIC_PATHS} y no exige token
     */
    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.contains(path);
    }

    /**
     * Obtiene el identificador de trazabilidad de la solicitud (REQ-09).
     *
     * @param exchange intercambio HTTP de la solicitud entrante
     * @return el {@code X-Request-Id} del cliente, o un UUID nuevo si falta o está en blanco
     */
    private String resolveRequestId(ServerWebExchange exchange) {
        String incoming = exchange.getRequest().getHeaders().getFirst(X_REQUEST_ID);
        if (incoming == null || incoming.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return incoming;
    }

    /**
     * Extrae el ID Token de la cabecera {@code Authorization} sin verificarlo (REQ-02).
     *
     * <p>El token se recorta con {@code trim()}: {@code Bearer } seguido solo de espacios es un token
     * vacío y no debe llegar a Firebase.
     *
     * @param exchange intercambio HTTP de la solicitud entrante
     * @return el token sin el prefijo {@code Bearer }, o {@code null} si falta la cabecera, el esquema
     *         no es {@code Bearer} o el token queda vacío
     */
    private String extractIdToken(ServerWebExchange exchange) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String idToken = authHeader.substring(BEARER_PREFIX.length()).trim();
        return idToken.isEmpty() ? null : idToken;
    }

    /**
     * Caso B: prepara la solicitud de una ruta pública. No hay usuario autenticado, así que no debe
     * quedar rastro de identidad (REQ-03, REQ-10).
     *
     * <p>No retira el {@code Authorization} entrante: {@code /webhooks/wompi} se autentica con la
     * firma del cuerpo. La decisión se revisa en el spec de OIDC (plan §3.3).
     *
     * @param exchange  intercambio HTTP de la solicitud entrante
     * @param requestId identificador de trazabilidad ya resuelto
     * @return un intercambio sin cabeceras {@code X-User-*} y con un único {@code X-Request-Id}
     */
    private ServerWebExchange withoutIdentity(ServerWebExchange exchange, String requestId) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> {
                    IDENTITY_HEADERS.forEach(h::remove);
                    h.set(X_REQUEST_ID, requestId); // set y no header: header añadiría un segundo valor
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    /**
     * Caso A: prepara la solicitud de una ruta protegida con la identidad del token ya verificado.
     *
     * <p>El orden importa: primero borra lo que mandó el cliente y después escribe lo verificado.
     * Las cabeceras opcionales se omiten si su valor falta, en lugar de enviarse vacías (REQ-01).
     *
     * @param exchange  intercambio HTTP de la solicitud entrante
     * @param token     ID Token de Firebase ya verificado
     * @param requestId identificador de trazabilidad ya resuelto
     * @return un intercambio con las cabeceras de identidad del token, un único valor por cabecera
     *         y sin el {@code Authorization} del cliente
     */
    private ServerWebExchange withIdentity(ServerWebExchange exchange, FirebaseToken token, String requestId) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> {
                    IDENTITY_HEADERS.forEach(h::remove);  // REQ-07: borra lo que mandó el cliente
                    h.remove(HttpHeaders.AUTHORIZATION);  // REQ-07: no propagar el token original
                    h.set(X_USER_ID, token.getUid());     // set y no header: header añadiría un segundo valor
                    setIfPresent(h, X_USER_EMAIL, token.getEmail());
                    setIfPresent(h, X_USER_ROLES, readRoles(token));
                    setIfPresent(h, X_USER_PLAN, readPlan(token));
                    h.set(X_REQUEST_ID, requestId);
                })
                .build();
        return exchange.mutate().request(request).build();
    }

    /**
     * Lee el custom claim {@code plan} sin interpretarlo (REQ-08).
     *
     * @param token ID Token de Firebase ya verificado
     * @return el valor del claim como texto, o {@code null} si el claim no existe
     */
    private String readPlan(FirebaseToken token) {
        Object claim = token.getClaims().get(PLAN_CLAIM);
        return claim == null ? null : claim.toString();
    }

    /**
     * Lee el claim {@code roles}, que puede llegar como lista o como cadena mientras
     * GW-TBD-06 siga abierto.
     *
     * <p>No cambia mayúsculas, no ordena, no deduplica y no deduce los roles a partir de
     * {@code plan} (REQ-08, GW-TBD-09).
     *
     * @param token ID Token de Firebase ya verificado
     * @return los roles unidos por comas si el claim es una lista, el valor tal cual si es una
     *         cadena, o {@code null} si el claim no existe
     */
    private String readRoles(FirebaseToken token) {
        Object claim = token.getClaims().get(ROLES_CLAIM);
        if (claim == null) {
            return null; // se omite la cabecera
        }
        if (claim instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).collect(joining(","));
        }
        return String.valueOf(claim); // cadena única, tal cual
    }

    /**
     * Fija una cabecera solo si tiene valor, para que ninguna cabecera se propague vacía (REQ-01).
     *
     * @param headers cabeceras de la solicitud que se va a reenviar
     * @param name    nombre de la cabecera
     * @param value   valor a fijar; si es {@code null} o está en blanco, la cabecera no se escribe
     */
    private void setIfPresent(HttpHeaders headers, String name, String value) {
        if (value != null && !value.isBlank()) {
            headers.set(name, value);
        }
    }

    /**
     * Responde {@code 401} con código {@code AUTH_REQUIRED} y termina el procesamiento: la solicitud
     * no llega al microservicio (REQ-02).
     *
     * <p>Registra el rechazo en nivel {@code WARN} con su {@code X-Request-Id} (REQ-09), y devuelve
     * ese mismo identificador en la cabecera de la respuesta para que el cliente pueda citarlo.
     * Nunca registra el token ni la cabecera {@code Authorization}.
     *
     * @param exchange  intercambio HTTP de la solicitud entrante
     * @param message   mensaje en español para el cuerpo de la respuesta; debe ser un texto fijo,
     *                  nunca el mensaje de una excepción
     * @param requestId identificador de trazabilidad que se incluye en el log y en la respuesta
     * @return la escritura de la respuesta {@code 401}
     */
    private Mono<Void> writeUnauthorized(ServerWebExchange exchange, String message, String requestId) {
        // REQ-09: el rechazo queda trazado. Nunca se registra el token ni la cabecera Authorization
        log.warn("Solicitud rechazada por autenticación [requestId={}]", requestId);

        String body = """
                {"code":"AUTH_REQUIRED","message":"%s"}""".formatted(message);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(X_REQUEST_ID, requestId); // REQ-09: set, un solo valor

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
