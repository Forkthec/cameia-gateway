package tech.cameia.gateway.filter;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
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
 *   <li><b>Caso B — ruta pública</b> ({@code PUBLIC_ROUTES}, y {@code DEV_PUBLIC_ROUTES} con el
 *       perfil {@code local}), por método y ruta exactos. No verifica token, pero igualmente borra
 *       toda cabecera {@code X-User-*} y el {@code Authorization} del cliente: no hay usuario
 *       autenticado y el microservicio no debe creer que lo hay.</li>
 * </ul>
 *
 * <p>En los dos casos garantiza un {@code X-Request-Id} en la solicitud reenviada y en la respuesta
 * al cliente, con el mismo valor.
 *
 * <p>Las cabeceras se fijan siempre con {@code set} y nunca con {@code header(...)}: este último
 * <em>añade</em> un segundo valor cuando el cliente ya envió la misma cabecera, y deja indefinido
 * cuál lee el microservicio. Es la regla que impide suplantar a otro usuario (REQ-07).
 *
 * <p>Este filtro no aplica reglas de negocio ni interpreta los claims: los propaga tal como vienen
 * en el token (REQ-08).
 *
 * <p>Requisitos: {@code specs/CM-104-correcciones/spec.md} y {@code specs/CM-14-Registro-usuario/spec.md}.
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
     * Estado de verificación del correo, del claim {@code email_verified}. Un {@code false} sí se
     * propaga: es un dato, no una ausencia. Solo se omite si el claim no existe (CM-14 REQ-VER-03).
     */
    static final String X_USER_EMAIL_VERIFIED = "X-User-Email-Verified";

    /**
     * Identificador de trazabilidad de la solicitud. Se conserva el del cliente o se genera uno.
     * Es trazabilidad, no identidad: por eso no pertenece a {@code IDENTITY_HEADERS}.
     */
    static final String X_REQUEST_ID = "X-Request-Id";

    /** Nombre del custom claim de Firebase con el plan del usuario. Lo escribe cameia-cuentas. */
    static final String PLAN_CLAIM = "plan";

    /** Nombre del custom claim de Firebase con los roles del usuario. */
    static final String ROLES_CLAIM = "roles";

    /** Nombre del claim estándar de Firebase con el estado de verificación del correo. */
    static final String EMAIL_VERIFIED_CLAIM = "email_verified";

    /** Esquema de la cabecera {@code Authorization} que lleva el ID Token de Firebase, con su espacio. */
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Cabeceras que solo el Gateway emite. Cualquier valor que llegue del cliente con uno de estos
     * nombres se descarta antes de reenviar, tanto en rutas protegidas como públicas (REQ-07, REQ-10).
     */
    private static final Set<String> IDENTITY_HEADERS = Set.of(
            X_USER_ID, X_USER_EMAIL, X_USER_ROLES, X_USER_PLAN, X_USER_EMAIL_VERIFIED
    );

    /** Perfil de desarrollo: el único que abre {@code DEV_PUBLIC_ROUTES} (CM-14 REQ-REG-01). */
    static final String DEV_PROFILE = "local";

    /** Perfil de despliegue: nunca puede convivir con {@code DEV_PROFILE} (CM-14 plan §3.3). */
    static final String DEPLOY_PROFILE = "prod";

    /**
     * Variable que Cloud Run inyecta en todo contenedor de servicio. Si existe, el Gateway está
     * desplegado y la lista de desarrollo no puede estar activa (CM-14 REQ-REG-02).
     */
    static final String CLOUD_RUN_SERVICE_VARIABLE = "K_SERVICE";

    /**
     * Entrada pública: método HTTP y ruta exactos. Al ser {@code record}, {@code equals} compara
     * por valor, así que {@code Set.contains} sigue siendo una comparación exacta sin comodines
     * (CM-14 REQ-REG-03, REQ-REG-06).
     *
     * @param method método HTTP que debe tener la solicitud
     * @param path   ruta exacta, sin query string
     */
    private record PublicRoute(HttpMethod method, String path) { }

    /**
     * Rutas del Gateway que no exigen token de Firebase (Caso B) en cualquier perfil. Se comparan
     * por método y ruta: otro método sobre la misma ruta es Caso A (CM-14 REQ-REG-06).
     *
     * <p>Vive en código y no en YAML a propósito: cualquier propiedad enlazada se puede sobrescribir
     * con una variable de entorno, y abrir una ruta debe exigir recompilar (CM-14 REQ-REG-02).
     *
     * <p>Aquí no van {@code /actuator/health} ni {@code /actuator/info}, y no es un olvido (REQ-13).
     * Actuator se atiende con su propio {@code HandlerMapping} (orden {@code -100}), antes que las
     * rutas del Gateway (orden {@code 1}), así que este filtro nunca ve esas solicitudes: son
     * públicas por arquitectura, no por esta lista. Una entrada aquí aparentaría controlar algo que
     * no controla. La prueba {@code actuatorHealth_withoutToken_returns200} lo demuestra.
     */
    private static final Set<PublicRoute> PUBLIC_ROUTES = Set.of(
            new PublicRoute(HttpMethod.POST, "/webhooks/wompi"),
            new PublicRoute(HttpMethod.POST, "/api/v1/users")   // registro de usuario (CM-14 REQ-REG-05)
    );

    /**
     * Health v1 de cada microservicio, públicos solo con el perfil de desarrollo (CM-14 REQ-REG-01).
     * Una versión nueva de un health no se abre sola: se añade aquí y se recompila.
     */
    private static final Set<PublicRoute> DEV_PUBLIC_ROUTES = Set.of(
            new PublicRoute(HttpMethod.GET, "/api/v1/users/health"),
            new PublicRoute(HttpMethod.GET, "/api/v1/profiles/health"),
            new PublicRoute(HttpMethod.GET, "/api/v1/interviews/health"),
            new PublicRoute(HttpMethod.GET, "/api/v1/voice-service/health"),
            new PublicRoute(HttpMethod.GET, "/api/v1/audit/health")
    );

    private final FirebaseAuth firebaseAuth;

    /** Se calcula una sola vez: los perfiles activos no cambian después del arranque. */
    private final boolean devRoutesEnabled;

    /**
     * @param firebaseAuth cliente del Admin SDK de Firebase con el que se verifican los ID Tokens
     * @param environment  entorno de Spring, del que se leen los perfiles activos y {@code K_SERVICE}
     * @throws IllegalStateException si el perfil de desarrollo está activo dentro de Cloud Run o
     *                               junto al perfil de despliegue
     */
    public FirebaseAuthGlobalFilter(FirebaseAuth firebaseAuth, Environment environment) {
        this.firebaseAuth = firebaseAuth;
        this.devRoutesEnabled = environment.matchesProfiles(DEV_PROFILE);
        if (devRoutesEnabled && isDeployment(environment)) {
            // CM-14 REQ-REG-02: se falla el arranque en vez de abrir los health en despliegue
            throw new IllegalStateException("El perfil '" + DEV_PROFILE + "' abre rutas públicas de "
                    + "desarrollo y no puede estar activo en Cloud Run (" + CLOUD_RUN_SERVICE_VARIABLE
                    + " definida) ni junto al perfil '" + DEPLOY_PROFILE + "'");
        }
    }

    /**
     * @param environment entorno de Spring
     * @return {@code true} si existe {@code K_SERVICE} o está activo el perfil {@code prod}
     */
    private static boolean isDeployment(Environment environment) {
        return environment.getProperty(CLOUD_RUN_SERVICE_VARIABLE) != null
                || environment.matchesProfiles(DEPLOY_PROFILE);
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
        exposeRequestId(exchange, requestId);

        if (isPublicRoute(exchange.getRequest())) {
            return chain.filter(withoutIdentity(exchange, requestId));
        }

        String idToken = extractIdToken(exchange);
        if (idToken == null) {
            // REQ-02: se rechaza antes de llamar a Firebase, que con un token vacío lanzaría otra excepción
            return writeUnauthorized(exchange, "Token de acceso requerido", requestId);
        }

        return verifyIdToken(exchange, idToken, requestId)
                .flatMap(decodedToken -> chain.filter(withIdentity(exchange, decodedToken, requestId)));
    }

    /**
     * Verifica el ID Token y traduce sus fallos: {@code 401} si el problema es el token, {@code 503}
     * si el servidor de Firebase Auth no respondió (CM-188 REQ-EMC-01, REQ-EMC-02).
     *
     * <p>Los fallos se capturan aquí y no después del {@code flatMap} de {@code filter}: así solo
     * cubren la verificación, y un fallo al reenviar al microservicio nunca se confunde con un token
     * inválido (plan §3.4 de CM-104-correcciones).
     *
     * @param exchange  intercambio HTTP de la solicitud entrante
     * @param idToken   ID Token de Firebase sin verificar
     * @param requestId identificador de trazabilidad ya resuelto
     * @return el token verificado; vacío si ya se escribió el {@code 401}; o un error {@code 503}
     */
    private Mono<FirebaseToken> verifyIdToken(ServerWebExchange exchange, String idToken, String requestId) {
        return Mono.fromCallable(() -> firebaseAuth.verifyIdToken(idToken, true))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(this::isAuthServerUnreachable, e -> authServerUnavailable(requestId, e))
                // tras escribir el 401 se completa vacío: el flatMap no corre y nada se reenvía
                .onErrorResume(this::isTokenRejection,
                        e -> writeUnauthorized(exchange, "Token de acceso inválido", requestId)
                                .then(Mono.<FirebaseToken>empty()));
    }

    /**
     * Decide si un fallo de la verificación es culpa del token y merece {@code 401} (REQ-02).
     *
     * <p>No abarca {@link Throwable} en bruto ni toda {@link FirebaseAuthException}: la que se debe a
     * un fallo de red es un problema del Gateway, no del cliente (CM-188 REQ-EMC-02).
     *
     * @param error excepción lanzada al verificar el ID Token
     * @return {@code true} si es {@link IllegalArgumentException}, o {@link FirebaseAuthException}
     *         que no se debe a un fallo de red
     */
    private boolean isTokenRejection(Throwable error) {
        return error instanceof IllegalArgumentException
                || (error instanceof FirebaseAuthException && !isAuthServerUnreachable(error));
    }

    /**
     * Decide si la verificación falló porque el servidor de Firebase Auth (real o emulador) no se pudo
     * contactar (CM-188 REQ-EMC-01).
     *
     * <p>El Admin SDK envuelve todo fallo de red en una {@link FirebaseAuthException} cuya cadena de
     * causas contiene una {@link IOException}; un rechazo del token no trae causa. El {@code ErrorCode}
     * no sirve para distinguirlos: vale {@code UNAVAILABLE} o {@code UNKNOWN} según el fallo (plan
     * §1.1 de CM-188).
     *
     * @param error excepción lanzada al verificar el ID Token
     * @return {@code true} si es {@link FirebaseAuthException} con una {@link IOException} en su cadena
     */
    private boolean isAuthServerUnreachable(Throwable error) {
        if (!(error instanceof FirebaseAuthException)) {
            return false;
        }
        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Falla cerrado cuando el servidor de Firebase Auth no respondió (CM-188 REQ-EMC-01, REQ-EMC-03).
     *
     * <p>La causa va al log con su {@code X-Request-Id}; al cliente solo le llega el {@code 503} del
     * catálogo de {@code GlobalErrorHandler}. La excepción va <b>sin reason</b>: cualquier texto
     * podría acabar en la respuesta. Nada se reenvía al microservicio.
     *
     * @param requestId identificador de trazabilidad ya resuelto
     * @param error     fallo de red devuelto por el Admin SDK
     * @return un error {@code 503}
     */
    private Mono<FirebaseToken> authServerUnavailable(String requestId, Throwable error) {
        log.error("El servidor de Firebase Auth no respondió al verificar el token [requestId={}]", requestId, error);
        return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
    }

    /**
     * Decide si la solicitud es Caso B comparando método y ruta exactos (CM-14 REQ-REG-06).
     *
     * <p>La lista de desarrollo solo se consulta con el perfil {@code local} activo: sin él, esos
     * health son Caso A como cualquier otra ruta (CM-14 REQ-REG-02).
     *
     * @param request solicitud entrante
     * @return {@code true} si la solicitud coincide con una entrada pública y no exige token
     */
    private boolean isPublicRoute(ServerHttpRequest request) {
        PublicRoute route = new PublicRoute(request.getMethod(), request.getPath().value());
        return PUBLIC_ROUTES.contains(route) || (devRoutesEnabled && DEV_PUBLIC_ROUTES.contains(route));
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
     * Devuelve el {@code X-Request-Id} en la respuesta al cliente, con un único valor (REQ-09).
     *
     * <p>Se fija dos veces, y las dos hacen falta:
     * <ol>
     *   <li>De inmediato, para que {@code GlobalErrorHandler} lo encuentre y registre el error con
     *       el mismo id que recibió el microservicio.</li>
     *   <li>Justo antes de enviar la respuesta, porque el Gateway <em>añade</em> las cabeceras que
     *       devuelve el microservicio: si este trae su propio {@code X-Request-Id}, el cliente
     *       recibiría dos valores.</li>
     * </ol>
     *
     * @param exchange  intercambio HTTP de la solicitud entrante
     * @param requestId identificador de trazabilidad ya resuelto
     */
    private void exposeRequestId(ServerWebExchange exchange, String requestId) {
        HttpHeaders responseHeaders = exchange.getResponse().getHeaders();
        responseHeaders.set(X_REQUEST_ID, requestId);
        exchange.getResponse().beforeCommit(() -> {
            responseHeaders.set(X_REQUEST_ID, requestId); // set reemplaza el valor del microservicio
            return Mono.empty();
        });
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
     * <p>También retira el {@code Authorization} entrante (CM-14 REQ-REG-07): un ID Token que el
     * navegador envíe por inercia no debe llegar al microservicio (AGENTS.md §7, bloqueante 4).
     * {@code /webhooks/wompi} no lo necesita: se autentica con la firma del cuerpo.
     *
     * @param exchange  intercambio HTTP de la solicitud entrante
     * @param requestId identificador de trazabilidad ya resuelto
     * @return un intercambio sin cabeceras {@code X-User-*} ni {@code Authorization}, y con un único
     *         {@code X-Request-Id}
     */
    private ServerWebExchange withoutIdentity(ServerWebExchange exchange, String requestId) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> {
                    IDENTITY_HEADERS.forEach(h::remove);
                    h.remove(HttpHeaders.AUTHORIZATION);  // CM-14 REQ-REG-07
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
                    setIfPresent(h, X_USER_EMAIL_VERIFIED, readEmailVerified(token));
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
     * Lee el claim {@code email_verified} sin interpretarlo (CM-14 REQ-VER-01 a REQ-VER-03).
     *
     * <p>Se lee del mapa de claims y no de {@code FirebaseToken.isEmailVerified()}: ese método
     * devuelve {@code false} cuando el claim no existe, y convertiría una ausencia en una
     * afirmación. Para cameia-cuentas no es lo mismo, aunque en los dos casos responda {@code 403}.
     *
     * @param token ID Token de Firebase ya verificado
     * @return el valor del claim como texto, o {@code null} si el claim no existe
     */
    private String readEmailVerified(FirebaseToken token) {
        Object claim = token.getClaims().get(EMAIL_VERIFIED_CLAIM);
        return claim == null ? null : claim.toString(); // "false" también se propaga
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
     * <p>Registra el rechazo en nivel {@code WARN} con su {@code X-Request-Id} (REQ-09). Nunca
     * registra el token ni la cabecera {@code Authorization}. La cabecera {@code X-Request-Id} de la
     * respuesta ya la fijó {@code filter(...)}.
     *
     * @param exchange  intercambio HTTP de la solicitud entrante
     * @param message   mensaje en español para el cuerpo de la respuesta; debe ser un texto fijo,
     *                  nunca el mensaje de una excepción
     * @param requestId identificador de trazabilidad que se incluye en el log
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

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
