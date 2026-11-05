# Tarjetas — CM-291 en `cameia-gateway`

Escritas con la opción A de P-1, P-2 y P-3 de la spec, decididas por Paula el 13-oct-2026. El rol `roles/firebaseauth.viewer` de la cuenta del Gateway ya existe (13-oct).

Reglas que aplican a **todas**:

- Identificadores en inglés; Javadoc, comentarios, mensajes, logs y documentos en español. Nunca `CM-291` ni rutas de otros archivos
  en comentarios de código. Pruebas con el estilo del repo: `escenario_condicion_resultado` en inglés.
- Prohibido: dependencias nuevas; tocar `PUBLIC_ROUTES`, `DEV_PUBLIC_ROUTES`, la firma OIDC, `GlobalErrorHandler` o `.github/`;
  registrar el token, el `uid` o el correo en los logs del filtro; `flatMap` anidados (máximo dos niveles).
- `filter` no importa `config` ni `exception` (`GatewayArchTest`).
- Comandos en PowerShell desde la raíz del repositorio. El Gateway no necesita Docker para `clean verify`; sí para G-5.
- **Detenerse y reportar** con la salida real si la tarjeta contradice el código, una prueba existente se rompe sin causa clara o hace
  falta algo no listado.
- Commit al cerrar cada tarjeta con el mensaje indicado, sin `[IA-ASISTIDO]` y con el trailer `Co-Authored-By` del modelo.

---

## G-0 · Preparación y línea base (sin commit)

1. `git fetch origin`. La copia local de `develop` está **un commit atrás** (le falta el PR #62, `1f7b8ee`): la rama sale de
   `origin/develop`, nunca de `develop` local. Si `mvnw.cmd` o `.classpath` aparecen en `git status`, son locales de Paula: no se
   incluyen ni se borran sin preguntar.
2. `git switch -c CM-291-cerrar-sesion-en-todos-los-dispositivos origin/develop`; anotar el SHA (esperado `1f7b8ee` o posterior).
3. `./mvnw.cmd clean verify` → `BUILD SUCCESS`. Línea base del 13-oct sobre `1f7b8ee`: 130 pruebas, 0 fallos. Guardar en
   `C:\Users\paanm\Documents\cameia\ejecucion\evidencia\CM-291\base-gateway.log`.

---

## G-1 · Tiempos de espera del Admin SDK

**Requisito:** REQ-291-20 (REST-0002, R6).

**Archivo:** `src/main/java/tech/cameia/gateway/config/FirebaseConfig.java`.

```java
    /** Espera máxima para abrir la conexión con Firebase; la misma de Cuentas. */
    static final int CONNECT_TIMEOUT_MS = 3_000;

    /**
     * Espera máxima para leer la respuesta de Firebase; la misma de Cuentas. Con la verificación de revocación, cada petición
     * privada hace una lectura de red: sin este tope, Firebase lento dejaría la petición colgada.
     */
    static final int READ_TIMEOUT_MS = 5_000;
```

En `init()`: `FirebaseOptions.builder().setCredentials(...).setProjectId(...).setConnectTimeout(CONNECT_TIMEOUT_MS).setReadTimeout(READ_TIMEOUT_MS).build()`.

**Prueba nueva en `FirebaseConfigTest`**, copiando la preparación de `emulatorHost_outsideDeployment_usesPlaceholderCredentials`:

- `firebaseOptions_useConnectAndReadTimeouts`: tras `init()`, `FirebaseApp.getInstance().getOptions().getConnectTimeout()` = `3000` y
  `getReadTimeout()` = `5000`.

**Comando:** `./mvnw.cmd test "-Dtest=FirebaseConfigTest"`.
**Commit:** `CM-291 | feat(gateway): tiempos de espera del Admin SDK de Firebase`.

**Resultado (15-oct-2026):** FirebaseConfigTest 8 pruebas, 0 fallos, `BUILD SUCCESS`. Antes del cambio la prueba nueva `firebaseOptions_useConnectAndReadTimeouts` falló (`expected: 3000`); con el cambio pasa.

---

## G-2 · No arrancar sin permiso para leer usuarios

**Requisito:** REQ-291-19 (P-1 A; evita repetir el 401 general); AC-0004 FIA-01; OWASP API8.

**Archivo nuevo:** `src/main/java/tech/cameia/gateway/config/FirebaseRevocationPermissionCheck.java`.

```java
package tech.cameia.gateway.config;

/**
 * Comprueba al arrancar que la cuenta de servicio del Gateway puede leer usuarios de Firebase Auth.
 *
 * <p>Verificar si un token fue revocado obliga al Admin SDK a leer el usuario en cada petición. Sin el permiso de lectura, esa
 * lectura falla para todas las personas: es lo que dejó a todo el mundo sin acceso cuando se activó por primera vez. Esta
 * comprobación convierte ese defecto de despliegue en un arranque fallido: la revisión nueva no recibe tráfico y Cloud Run sigue
 * sirviendo la anterior.</p>
 *
 * <p>Corre en {@code @PostConstruct}, antes de que el servidor abra el puerto. Una caída pasajera de Firebase no impide arrancar:
 * solo la falta de permiso o un proyecto equivocado, que no se arreglan reintentando.</p>
 */
@Component
@ConditionalOnProperty(name = "gateway.firebase.enabled", havingValue = "true", matchIfMissing = true)
class FirebaseRevocationPermissionCheck {

    private static final Logger log = LoggerFactory.getLogger(FirebaseRevocationPermissionCheck.class);

    /** Identificador que no pertenece a nadie: la respuesta esperada es «usuario no encontrado». */
    static final String PROBE_UID = "cameia-gateway-permission-probe";

    static final String MISSING_PERMISSION_MESSAGE = "La cuenta de servicio del Gateway no puede leer usuarios de Firebase Auth: "
            + "sin ese permiso cada petición respondería 503. Conceda roles/firebaseauth.viewer";

    private final FirebaseAuth firebaseAuth;

    FirebaseRevocationPermissionCheck(FirebaseAuth firebaseAuth) { this.firebaseAuth = firebaseAuth; }

    /**
     * Lee un usuario inexistente y decide según la respuesta.
     *
     * @throws IllegalStateException si Firebase niega el permiso, no reconoce la credencial o no encuentra el proyecto
     */
    @PostConstruct
    void check() {
        try {
            firebaseAuth.getUser(PROBE_UID);
            log.info("Permiso de lectura de usuarios de Firebase confirmado");
        } catch (FirebaseAuthException error) {
            if (AuthErrorCode.USER_NOT_FOUND.equals(error.getAuthErrorCode())) {
                log.info("Permiso de lectura de usuarios de Firebase confirmado");
                return;
            }
            if (isMisconfiguration(error)) {
                throw new IllegalStateException(MISSING_PERMISSION_MESSAGE, error);
            }
            // Una caída pasajera no debe impedir que el servicio escale
            log.warn("No se pudo comprobar el permiso de lectura de usuarios de Firebase [errorCode={}]", error.getErrorCode());
        }
    }

    private static boolean isMisconfiguration(FirebaseAuthException error) {
        return error.getErrorCode() == ErrorCode.PERMISSION_DENIED
                || error.getErrorCode() == ErrorCode.UNAUTHENTICATED
                || AuthErrorCode.CONFIGURATION_NOT_FOUND.equals(error.getAuthErrorCode());
    }
}
```

`ErrorCode` es `com.google.firebase.ErrorCode`. Javadoc en el constructor y en `isMisconfiguration`.

**Archivo nuevo:** `src/test/java/tech/cameia/gateway/config/FirebaseRevocationPermissionCheckTest.java` (sin Spring;
`FirebaseAuth firebaseAuth = mock(FirebaseAuth.class)`; `@ExtendWith(OutputCaptureExtension.class)`):

| Método | Simulación de `getUser("cameia-gateway-permission-probe")` | Esperado |
|---|---|---|
| `check_passes_whenProbeUserNotFound` | `new FirebaseAuthException(ErrorCode.NOT_FOUND, "sin usuario", null, null, AuthErrorCode.USER_NOT_FOUND)` | no lanza; log contiene «Permiso de lectura de usuarios de Firebase confirmado» |
| `check_passes_whenProbeUserExists` | devuelve `mock(UserRecord.class)` | no lanza |
| `check_shouldFailStartup_whenPermissionDenied` | `ErrorCode.PERMISSION_DENIED`, sin código de autenticación | `IllegalStateException`; mensaje contiene `roles/firebaseauth.viewer` |
| `check_shouldFailStartup_whenUnauthenticated` | `ErrorCode.UNAUTHENTICATED` | `IllegalStateException` |
| `check_shouldFailStartup_whenProjectNotFound` | `ErrorCode.NOT_FOUND`, `AuthErrorCode.CONFIGURATION_NOT_FOUND` | `IllegalStateException` |
| `check_warnsAndPasses_whenFirebaseUnavailable` | `ErrorCode.UNAVAILABLE` | no lanza; log contiene `errorCode=UNAVAILABLE` |

Además, en `GatewayStartupTest` (o, si no aplica, en una prueba nueva con `ApplicationContextRunner`): con el perfil `test`
(`gateway.firebase.enabled=false`) el bean `FirebaseRevocationPermissionCheck` **no existe**, para que ninguna prueba de contexto
haga una llamada real.

**Comando:** `./mvnw.cmd test "-Dtest=FirebaseRevocationPermissionCheckTest,GatewayStartupTest,GatewayArchTest"`.
**Commit:** `CM-291 | feat(gateway): no arrancar sin permiso para leer usuarios de Firebase`.

**Resultado (15-oct-2026):** FirebaseRevocationPermissionCheckTest 6, GatewayStartupTest 7 (1 nueva: el bean no existe con Firebase apagado), GatewayArchTest 4 y FirebaseEmulatorStartupTest 3: 20 pruebas, 0 fallos, `BUILD SUCCESS`. Desviación aprobada por Paula (opción A): `FirebaseEmulatorStartupTest` pasaba la dirección del emulador como propiedad de Spring y no como variable de entorno real, que es la única que lee el SDK, así que con el chequeo llamaba al Firebase real de Google y el arranque fallaba con `OAuth2Credentials instance does not support refreshing the access token`. La prueba ahora quita el chequeo de su contexto (`WithoutPermissionCheckConfig`); con la variable real el chequeo contra el emulador pasa y registra «Permiso de lectura de usuarios de Firebase confirmado».

---

## G-3 · Verificación con revocación y clasificación 401 / 503

**Requisitos:** REQ-291-10 a REQ-291-17; CA-1.8.2, 1.8.9, 1.8.10, 1.8.11, 1.8.12; P-2 A y P-3 A.

**Paso 1 (mecánico, primero):** en las pruebas, reemplazar **todas** las simulaciones `when(firebaseAuth.verifyIdToken("X"))` por
`when(firebaseAuth.verifyIdToken("X", true))`: 18 en `FirebaseAuthGlobalFilterTest`, 1 en `GatewayErrorMappingTest`, 1 en
`OidcSigningFilterTest`. Comprobar con `git grep -n "verifyIdToken(\"[^\"]*\")" -- src/test` → vacío.

**Paso 2 (prueba que falla primero, reproduce la causa de septiembre):** agregar a `FirebaseAuthGlobalFilterTest`:

```java
    @ParameterizedTest
    @MethodSource("verificationFailures")
    void revocationLookupFailure_returns503AuthUnavailable(FirebaseAuthException failure) throws Exception {
        when(firebaseAuth.verifyIdToken("token-sin-confirmar", true)).thenThrow(failure);
        int before = mockDownstream.getRequestCount();

        webTestClient.get().uri("/api/v1/profiles/me")
                .header("Authorization", "Bearer token-sin-confirmar")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Content-Type", "application/json;charset=UTF-8")
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTH_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("Servicio de autenticación no disponible");

        assertThat(mockDownstream.getRequestCount()).isEqualTo(before);
    }

    static Stream<FirebaseAuthException> verificationFailures() {
        return Stream.of(
                new FirebaseAuthException(ErrorCode.PERMISSION_DENIED, "sin permiso", null, null, null),
                new FirebaseAuthException(ErrorCode.UNAVAILABLE, "caído", null, null, null),
                new FirebaseAuthException(ErrorCode.UNKNOWN, "sin red", new java.net.ConnectException("rechazada"), null, null),
                new FirebaseAuthException(ErrorCode.UNKNOWN, "sin claves", null, null, AuthErrorCode.CERTIFICATE_FETCH_FAILED));
    }
```

**Trampa del ensayo «antes»:** el filtro actual llama a `verifyIdToken(token)` con un argumento; si solo se simula el de dos, el mock
devuelve `null`, el `Mono` queda vacío y la respuesta no reproduce nada. Para la corrida «antes», agregar **temporalmente** en la
primera línea de la prueba `when(firebaseAuth.verifyIdToken("token-sin-confirmar")).thenThrow(failure);`. Correr
`./mvnw.cmd test "-Dtest=FirebaseAuthGlobalFilterTest#revocationLookupFailure_returns503AuthUnavailable"` **antes** de tocar el filtro:
debe fallar con `expected 503 but was 401` en los cuatro casos. Pegar esa salida en el reporte (evidencia «antes») y **quitar** la
simulación temporal al terminar el paso 3.

**Paso 3 (filtro):** en `FirebaseAuthGlobalFilter`:

```java
    /** Código del 503 cuando Firebase no pudo confirmar el token; la sesión de la persona se conserva. */
    static final String AUTH_UNAVAILABLE_CODE = "AUTH_UNAVAILABLE";

    /** Causa registrada cuando falta el token, viene vacío o no usa el esquema Bearer. */
    static final String CAUSE_TOKEN_MISSING = "TOKEN_MISSING";

    /** Causa registrada cuando Firebase rechaza el formato del token con {@code IllegalArgumentException}. */
    static final String CAUSE_TOKEN_MALFORMED = "TOKEN_MALFORMED";

    /**
     * Rechazos que son culpa del token o de la cuenta: la persona debe iniciar sesión de nuevo. Cualquier otro fallo de Firebase
     * significa que no se pudo confirmar el token, y responder 401 cerraría la sesión de todo el mundo durante una caída.
     */
    private static final Set<AuthErrorCode> TOKEN_REJECTIONS = EnumSet.of(
            AuthErrorCode.INVALID_ID_TOKEN, AuthErrorCode.EXPIRED_ID_TOKEN, AuthErrorCode.REVOKED_ID_TOKEN,
            AuthErrorCode.USER_DISABLED, AuthErrorCode.USER_NOT_FOUND, AuthErrorCode.TENANT_ID_MISMATCH);
```

En `filter(...)` (mantener el comentario existente sobre capturar antes del `flatMap`):

```java
        return Mono.fromCallable(() -> firebaseAuth.verifyIdToken(idToken, true)) // true: comprueba revocación y cuenta
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(this::isTokenRejection,
                        e -> writeUnauthorized(exchange, "Token de acceso inválido", requestId, rejectionCause(e))
                                .then(Mono.<FirebaseToken>empty()))
                .onErrorResume(FirebaseAuthException.class::isInstance,
                        e -> writeAuthUnavailable(exchange, requestId, (FirebaseAuthException) e)
                                .then(Mono.<FirebaseToken>empty()))
                .flatMap(decodedToken -> shouldReject(exchange.getRequest(), decodedToken)
                        ? writeEmailNotVerified(exchange, requestId)
                        : chain.filter(withIdentity(exchange, decodedToken, requestId)));
```

- `isTokenRejection(Throwable)`: `true` si es `IllegalArgumentException`, o si es `FirebaseAuthException` y
  `TOKEN_REJECTIONS.contains(getAuthErrorCode())` (`EnumSet.contains(null)` devuelve `false`). Actualizar su Javadoc.
- `rejectionCause(Throwable)`: `CAUSE_TOKEN_MALFORMED` para `IllegalArgumentException`; el `name()` del `AuthErrorCode` para
  `FirebaseAuthException`.
- `writeUnauthorized(exchange, message, requestId, cause)`: nuevo parámetro; log
  `log.warn("Solicitud rechazada por autenticación [requestId={}] [causa={}]", requestId, cause)`. La llamada por token ausente pasa
  `CAUSE_TOKEN_MISSING`.
- `writeAuthUnavailable(exchange, requestId, error)`: `log.error("No se pudo verificar el token con Firebase [code={}, requestId={}, errorCode={}]", AUTH_UNAVAILABLE_CODE, requestId, error.getErrorCode(), error)`
  y `writeError(exchange, HttpStatus.SERVICE_UNAVAILABLE, AUTH_UNAVAILABLE_CODE, "Servicio de autenticación no disponible")`.
- Javadoc de la clase: agregar que el token se verifica con revocación (cuenta deshabilitada, eliminada o sesión cerrada → 401) y que
  un fallo de Firebase al confirmar responde 503 `AUTH_UNAVAILABLE`.

**Paso 4 (pruebas):**

- Ajustar `invalidToken_returns401AndUtf8Charset`: lanzar
  `new FirebaseAuthException(ErrorCode.INVALID_ARGUMENT, "token inválido", null, null, AuthErrorCode.INVALID_ID_TOKEN)` en lugar de
  `mock(FirebaseAuthException.class)` (un mock tiene código de autenticación `null` y ahora sería 503).
- Ajustar `authRejection_isLoggedWithRequestId` solo si deja de pasar: la línea sigue conteniendo
  «Solicitud rechazada por autenticación [requestId=req-rechazo-123]»; agregar la aserción `contains("[causa=TOKEN_MISSING]")`.
- Pruebas nuevas en `FirebaseAuthGlobalFilterTest` (todas sobre `GET /api/v1/profiles/me` salvo que se diga otra cosa; cada 401 y 503
  comprueba `Content-Type: application/json;charset=UTF-8`, `code`, `message` y que `mockDownstream.getRequestCount()` no cambió):

| Método | Simulación de `verifyIdToken(token, true)` | Esperado |
|---|---|---|
| `revokedToken_returns401WithoutReachingDownstream` (CA-1.8.9) | `ErrorCode.INVALID_ARGUMENT`, `AuthErrorCode.REVOKED_ID_TOKEN` | 401 `AUTH_REQUIRED` «Token de acceso inválido» |
| `disabledUser_returns401WithoutReachingDownstream` (CA-1.8.10) | `AuthErrorCode.USER_DISABLED` | ídem |
| `deletedUser_returns401WithoutReachingDownstream` (CA-1.8.11) | `ErrorCode.NOT_FOUND`, `AuthErrorCode.USER_NOT_FOUND` | ídem |
| `expiredToken_returns401WithoutReachingDownstream` | `AuthErrorCode.EXPIRED_ID_TOKEN` | ídem |
| `validToken_isVerifiedWithRevocationCheck` (CA-1.8.12) | devuelve un token válido (como `validTokenWithPlan_propagatesHeadersToDownstream`) | 200 desde el destino; `verify(firebaseAuth).verifyIdToken("token-vigente", true)`; `verify(firebaseAuth, never()).verifyIdToken("token-vigente")` |
| `revocationLookupFailure_returns503AuthUnavailable` (paso 2) | 4 casos | 503 `AUTH_UNAVAILABLE` |
| `revokedToken_isLoggedWithCauseWithoutToken` (`CapturedOutput`) | `REVOKED_ID_TOKEN`, token `token-revocado-secreto`, `X-Request-Id: req-revocado-1` | log contiene `[requestId=req-revocado-1] [causa=REVOKED_ID_TOKEN]`; no contiene `token-revocado-secreto` |
| `authUnavailable_isLoggedAtErrorWithRequestId` (`CapturedOutput`) | `ErrorCode.UNAVAILABLE`, `X-Request-Id: req-caido-1` | log contiene `code=AUTH_UNAVAILABLE, requestId=req-caido-1` y `ERROR` |
| `malformedToken_isLoggedAsTokenMalformed` (`CapturedOutput`) | `IllegalArgumentException` | log contiene `[causa=TOKEN_MALFORMED]` |

`firebaseUnexpectedFailure_isNotTreatedAsTokenRejection` (500 ante `IllegalStateException`) **no cambia** y debe seguir en verde.

**Comando:** `./mvnw.cmd test "-Dtest=FirebaseAuthGlobalFilterTest,GatewayErrorMappingTest,OidcSigningFilterTest,GatewayArchTest"`.
**Terminado:** todo en verde y la salida «antes» del paso 2 pegada en el reporte.
**Commit:** `CM-291 | feat(gateway): rechazar con 401 los tokens revocados y responder 503 si Firebase no confirma`.

**Resultado (15-oct-2026):** FirebaseAuthGlobalFilterTest 66, GatewayErrorMappingTest 5, OidcSigningFilterTest 8 y GatewayArchTest 4: 83 pruebas, 0 fallos, `BUILD SUCCESS`. Evidencia «antes» (filtro sin tocar, `ejecucion/evidencia/CM-291/antes-gateway-503.log`): los 4 casos de `revocationLookupFailure_returns503AuthUnavailable` fallan con `Status expected:<503 SERVICE_UNAVAILABLE> but was:<401 UNAUTHORIZED>`. Hallazgos de la tarjeta: (1) las simulaciones de `verifyIdToken` con un argumento eran 17 en `FirebaseAuthGlobalFilterTest`, no 18, más una verificación `never()` que pasó a la forma de dos argumentos; (2) la prueba parametrizada del paso 2 debe simular con `doThrow(...).when(...)` y no con `when(...)`, porque el segundo caso relanzaba la excepción del primero al volver a simular el mismo método sobre el mismo mock compartido; (3) `invalidOrMissingToken_stillReturns401` también usaba `mock(FirebaseAuthException.class)` y se ajustó igual que `invalidToken_returns401AndUtf8Charset`.

---

## G-4 · Ruta hacia Cuentas

**Requisitos:** REQ-291-18; CA-1.8.1, CA-1.8.2.

**Archivo:** `src/main/resources/application.yml`, debajo de la ruta `cameia-cuentas-users`:

```yaml
            # Cierre de sesión en todos los dispositivos (Cuentas). Caso A: no entra en PUBLIC_ROUTES.
            - id: cameia-cuentas-auth
              uri: ${CAMEIA_CUENTAS_URL}
              predicates:
                - Path=/api/v1/auth/**
```

**Pruebas nuevas en `FirebaseAuthGlobalFilterTest`** (el destino simulado ya atiende `CAMEIA_CUENTAS_URL`):

| Método | Petición | Esperado |
|---|---|---|
| `logoutAllRoute_withValidToken_reachesAccountsWithIdentity` | `POST /api/v1/auth/logout-all`, `Bearer token-logout`, token válido de `uid-logout` con `email_verified=true`; el destino responde 204 | 204 al cliente; `mockDownstream.takeRequest()`: método `POST`, ruta `/api/v1/auth/logout-all`, `X-User-Id: uid-logout`, sin el `Bearer token-logout` |
| `logoutAllRoute_clientIdentityHeader_isReplacedByTokenUid` | igual, más `X-User-Id: uid-ajeno` del cliente | el destino recibe un solo `X-User-Id`, `uid-logout` |
| `logoutAllRoute_withoutToken_returns401WithoutReachingDownstream` | sin `Authorization` | 401 `AUTH_REQUIRED` «Token de acceso requerido»; destino sin peticiones nuevas |
| `logoutAllRoute_unverifiedEmail_returns403WithoutReachingDownstream` | token con `email_verified=false` | 403 `EMAIL_NOT_VERIFIED` |

**Comando:** `./mvnw.cmd test "-Dtest=FirebaseAuthGlobalFilterTest,VersionedYamlTest"`.
**Commit:** `CM-291 | feat(gateway): ruta de cierre de sesión en todos los dispositivos hacia Cuentas`.

**Resultado (15-oct-2026):** FirebaseAuthGlobalFilterTest 70 y VersionedYamlTest 3: 73 pruebas, 0 fallos, `BUILD SUCCESS`. Antes de agregar la ruta, las 4 pruebas nuevas `logoutAllRoute_*` fallaron con 404 (`Status expected:<204 NO_CONTENT> but was:<404 NOT_FOUND>`).

---

## G-5 · Colección de Postman (local, con el emulador y Cuentas)

**Requisitos:** R11; CA-1.8.1, 1.8.2, 1.8.9 a 1.8.12; DES-02.

**Archivo:** `docs/cameia-gateway-perfil.postman_collection.json`. Variables de colección nuevas: `firebaseProjectId` = `demo-cameia`,
`logoutEmail`, `logoutPassword`, `logoutUid`, `logoutToken`. Reutiliza `baseUrl` y `emulatorUrl`. Las llamadas al emulador siguen el
patrón de la carpeta «Correo sin verificar (emulador)» (`accounts:signUp?key=fake-api-key`); las administrativas llevan
`Authorization: Bearer owner`.

**Carpeta nueva al final:** «Revocación de sesiones (local)». Cada petición al Gateway comprueba estado, `code`, `message`, `Content-Type`
con `charset=UTF-8` en los errores y `pm.response.responseTime < 2000`.

| # | Petición | Esperado |
|---|---|---|
| G-00 | `POST {{baseUrl}}/api/v1/users` (registro público, cuerpo válido con `prueba+{{$timestamp}}-g@ejemplo.test`) | 201; guarda `logoutUid`, `logoutEmail`, `logoutPassword` |
| G-01 | `POST {{emulatorUrl}}/identitytoolkit.googleapis.com/v1/projects/{{firebaseProjectId}}/accounts:update`, `{"localId":"{{logoutUid}}","emailVerified":true}` | 200 |
| G-01b | `POST {{emulatorUrl}}/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=fake-api-key` con correo y contraseña, `returnSecureToken: true` | 200; guarda `logoutToken` |
| G-02 | `GET {{baseUrl}}/api/v1/users/me`, `Bearer {{logoutToken}}` (CA-1.8.12) | 200 |
| G-03 | `POST {{baseUrl}}/api/v1/auth/logout-all` sin token (CA-1.8.2) | 401 `AUTH_REQUIRED` «Token de acceso requerido» |
| G-04 | Pre-request de G-05: espera 1100 ms (`setTimeout`), para que la revocación caiga en un segundo posterior al `iat` | — |
| G-05 | `POST {{baseUrl}}/api/v1/auth/logout-all`, `Bearer {{logoutToken}}` (CA-1.8.1, CA-1.8.2) | 204 |
| G-06 | `GET {{baseUrl}}/api/v1/users/me` con el **mismo** token (CA-1.8.9) | 401 `AUTH_REQUIRED` «Token de acceso inválido» |
| G-07 | Espera 1100 ms, nuevo `signInWithPassword` y `GET /api/v1/users/me` con el token nuevo | 200: una sesión nueva funciona |
| G-08 | `accounts:update` `{"localId":"{{logoutUid}}","disableUser":true}` y `GET /api/v1/users/me` con el token de G-07 (CA-1.8.10) | 401 `AUTH_REQUIRED` |
| G-09 | Segunda cuenta (registro, verificar, ingresar), luego `POST .../projects/{{firebaseProjectId}}/accounts:delete` `{"localId":"…"}` y `GET /api/v1/users/me` con su token (CA-1.8.11) | 401 `AUTH_REQUIRED` |
| G-10 | Tercera cuenta sin verificar (registro e ingreso sin G-01) y `POST /api/v1/auth/logout-all` | 403 `EMAIL_NOT_VERIFIED` |

**Cómo correrla** (anotarlo en la descripción de la carpeta):

1. `docker network create cameia-net` (una vez). En `cameia-gateway` (esta rama): `.env` con `FIREBASE_PROJECT_ID=demo-cameia` y
   `FIREBASE_AUTH_EMULATOR_HOST=cameia-firebase-emulator:9099`; `docker compose up --build -d`. El log debe mostrar «Permiso de lectura de
   usuarios de Firebase confirmado» (el emulador responde «usuario no encontrado» a la sonda).
2. En `cameia-cuentas` (rama `CM-291-…`): `.env` con el mismo proyecto y `FIREBASE_AUTH_EMULATOR_HOST=cameia-firebase-emulator:9099`;
   `docker compose up --build -d`.
3. `npx newman run docs/cameia-gateway-perfil.postman_collection.json --folder "Revocación de sesiones (local)" --reporters cli,junit --reporter-junit-export target/newman-revocacion.xml`.
4. Al terminar: `docker volume rm cameia-gateway_firebase-emulator-data` para no dejar usuarios de prueba.

Si una petición falla en Newman y no en las pruebas automáticas, falta una prueba automática: se agrega antes de corregir.
**Commit:** `CM-291 | test(gateway): colección de Postman de la revocación de sesiones`.

**Resultado (15-oct-2026):** carpeta de 19 peticiones. Newman contra la imagen Docker del Gateway, la de Cuentas y el emulador de Firebase: 19 peticiones, 50 comprobaciones, 0 fallos (`ejecucion/evidencia/CM-291/newman-gateway.log`). El log del Gateway mostró «Permiso de lectura de usuarios de Firebase confirmado». Recorrido real: sesión vigente 200 (G-02), cierre 204 (G-05), mismo token 401 (G-06), sesión nueva 200 (G-07b), cuenta deshabilitada 401 (G-08b), cuenta eliminada 401 (G-09d), correo sin verificar 403 (G-10b). Variables de colección agregadas además de las de la tarjeta: `deleteEmail`, `deleteUid`, `deleteToken`, `unverifiedEmail`, `unverifiedUid`, `unverifiedToken` (las cuentas de G-09 y G-10).

---

## G-6 · Documentación

- `docs/errores.md`:
  - Fila `AUTH_REQUIRED` «Token de acceso inválido»: origen «Firebase rechaza el token: firma o formato (`INVALID_ID_TOKEN`,
    `IllegalArgumentException`), vencido (`EXPIRED_ID_TOKEN`), sesión revocada (`REVOKED_ID_TOKEN`), cuenta deshabilitada
    (`USER_DISABLED`), cuenta eliminada (`USER_NOT_FOUND`) o de otro tenant (`TENANT_ID_MISMATCH`)»; pruebas: agregar las cuatro de G-3.
  - Fila nueva después de `EMAIL_NOT_VERIFIED`: `AUTH_UNAVAILABLE` | 503 | Servicio de autenticación no disponible |
    `FirebaseAuthGlobalFilter`: Firebase no pudo confirmar el token (no responde, tiempo agotado, cuota, permisos o claves públicas no
    descargables); la sesión se conserva | `FirebaseAuthGlobalFilterTest`: `revocationLookupFailure_returns503AuthUnavailable`,
    `authUnavailable_isLoggedAtErrorWithRequestId`.
- `docs/adr/0002-verificacion-de-sesiones-revocadas.md` (Estado, Contexto, Decisión, Consecuencias, Alternativas descartadas, como el
  ADR 0001): verificación con revocación en cada petición sin caché; 401 sin distinguir la causa (P-3 A) y causa en el log; 503
  `AUTH_UNAVAILABLE` (P-2 A); comprobación del permiso al arrancar (P-1 A); tiempos de espera; causa del 401 general de septiembre
  (permiso de lectura ausente) explicada sin citar tareas.
- `CLAUDE.md`:
  - §2, tabla de componentes: fila nueva `FirebaseRevocationPermissionCheck` (`config`): «En `@PostConstruct` lee un usuario inexistente;
    sin permiso (`PERMISSION_DENIED`, `UNAUTHENTICATED`) o con el proyecto equivocado, falla el arranque; ante una caída pasajera solo
    avisa»; fila `FirebaseConfig`: agregar «3 s de conexión y 5 s de lectura»; fila `FirebaseAuthGlobalFilter`: «valida el Bearer token
    con revocación (`401` si falta, está vacío, Firebase lo rechaza, la sesión fue revocada o la cuenta está deshabilitada o eliminada;
    `503 AUTH_UNAVAILABLE` si Firebase no puede confirmarlo; `403` si el correo no está verificado)».
  - §3, Caso A, paso 1: «firma, expiración, emisor y revocación».
  - §4, tabla de rutas: fila `/api/v1/auth/**` → `${CAMEIA_CUENTAS_URL}` → A.
  - §6: «La cuenta de servicio del Gateway necesita `roles/firebaseauth.viewer` para comprobar la revocación; sin él no arranca.»
- `docs/COMO-FUNCIONA.md`: solo si describe la verificación del token, agregar la revocación en la misma frase.
- `docs/bitacora-ia/02_sesiones-revocadas-en-el-gateway_prompt.md` y su fila en `docs/bitacora-ia/README.md`: el prompt armado de las
  decisiones P-1 a P-3 (con lo que respondió Paula), con enlace a la spec.

**Commit:** `CM-291 | docs(gateway): catálogo, ADR 0002, CLAUDE.md y bitácora de la revocación`.

**Resultado (15-oct-2026):** catálogo (`AUTH_REQUIRED` ampliado y `AUTH_UNAVAILABLE` nuevo), ADR 0002, `CLAUDE.md`, `COMO-FUNCIONA.md` (dos frases con la revocación) y bitácora 02. Sin pruebas propias; el cambio no toca código.

---

## G-7 · Verificación final (una vez, sobre la punta; sin commit)

1. `./mvnw.cmd clean verify` → `BUILD SUCCESS`; guardar en `ejecucion/evidencia/CM-291/verify-gateway.log`.
2. JaCoCo de `FirebaseAuthGlobalFilter`, `FirebaseRevocationPermissionCheck` y `FirebaseConfig`: ≥ 90 % de líneas y de ramas, con la
   razón de cada línea o rama sin cubrir.
3. Newman de G-5 con la salida pegada.
4. `git grep -n "CM-291" -- src` vacío; `git diff origin/develop -- pom.xml` vacío.
5. Tabla R1–R14 y tabla de atributos con resultados reales.
6. **Después de fusionar y desplegar** (antes, comprobar que el rol sigue en `gcloud projects get-iam-policy cameia-e245f`): en el log de la revisión nueva, la línea «Permiso de lectura de
   usuarios de Firebase confirmado»; CA-1.8.12 y CA-1.8.9 contra `https://api-staging.cameia.app` con una cuenta de prueba; 30 minutos
   sin 401 nuevos inesperados (`gcloud logging read` del Gateway); datos de prueba anotados para retirar.
