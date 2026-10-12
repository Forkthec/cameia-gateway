# Tareas — CM-179-VerificacionCorreo, pieza 3 (cameia-gateway)

Estado: ejecutada el 11-oct-2026 (T-3.5 pendiente solo de la revisión de Paula, el cuerpo del PR y el push); spec aprobada por Paula el 11-oct-2026 y `LISTA PARA EJECUTAR` (sección 16 de la spec). Un PR. Se marca `[x]` solo con la salida real de las pruebas.

## Reglas para todas las tarjetas (quien ejecuta no lee la spec ni el plan)

- Base: `origin/develop` `53fbe30`. Las reglas del repo están en `CLAUDE.md` (en `develop`, `AGENTS.md` solo remite a él). Si una línea o un nombre de esta tarjeta no coincide con el código, **detenerse y reportar**.
- Identificadores en inglés; comentarios, Javadoc, logs y documentación en español; pruebas `escenario_condicion_resultado` en inglés como las existentes; sin `CM-NNN` ni rutas a otros archivos en comentarios.
- Prohibido: agregar dependencias o clases; tocar `application*.yml`, `pom.xml`, `GlobalErrorHandler`, `Dockerfile` o `.github/**`; registrar tokens, correos o uid; que `filter` importe `config` o `exception` (`GatewayArchTest`).
- Comandos: por tarjeta, `./mvnw.cmd -q -B "-Dtest=FirebaseAuthGlobalFilterTest" test`. Al final (T-3.5), una vez: `./mvnw.cmd -B clean verify` (o `docker compose run --rm verify`) y `target/site/jacoco/`.
- Commits: `CM-179 | <tipo>(gateway): <resultado>`, **sin** `[IA-ASISTIDO]`, con el trailer `Co-Authored-By` del modelo (`plan.md` §1).

## [x] T-3.0 · Preparar la rama — ≤ 10 min

**Resultado (11-oct-2026):** rama rebasada sobre `origin/develop` `53fbe30`; SHA y árbol anteriores guardados; sin remoto. `APPLICATION_JSON_UTF8` (línea 105) y el `flatMap` (línea 244) existían. Línea base: `clean verify` → 114 pruebas, 0 fallos, BUILD SUCCESS.

`git fetch`; guardar en `ejecucion/evidencia/CM-179/rama-gateway-antes-del-rebase.txt` de la raíz `cameia` el SHA y el árbol de la punta; confirmar que no tiene remoto (`git ls-remote --heads origin CM-179-verificacion-correo-gateway` vacío); `git rebase origin/develop`. Comprobar que existen `APPLICATION_JSON_UTF8` (línea 105) y el `flatMap` de la línea 244 de `FirebaseAuthGlobalFilter.java`. Si no, detenerse. **Línea base** (spec, sección 14): `./mvnw.cmd -B clean verify` → BUILD SUCCESS y 0 fallos (114 pruebas sobre `53fbe30`), salida en `ejecucion/evidencia/CM-179/linea-base-gateway.log`. Si no está en verde, detenerse y reportar.

## [x] T-3.1 · Pruebas del 403 (primero; deben fallar) — ≤ 30 min

**Resultado:** 12 casos nuevos (la prueba 1 son seis). En rojo fallan los que esperaba (1, 6 y 7) por la causa correcta: hoy el Gateway reenvía y la petición queda esperando respuesta; 2 y 5 caen solo arrastrados por la cola del `MockWebServer`.

- **Cubre:** REQ-EV-01 a 09. **Modificar:** `src/test/java/tech/cameia/gateway/filter/FirebaseAuthGlobalFilterTest.java`, junto a las pruebas de verificación de correo (línea 619 en adelante). Reutilizar `mockToken(uid, Map.of("email_verified", false))`, `when(firebaseAuth.verifyIdToken("<token>")).thenReturn(token)`, `mockDownstream.getRequestCount()` y `OutputCaptureExtension` (la clase ya la usa).
- **Pruebas nuevas:**
  1. `unverifiedEmail_onPrivateRoute_returns403WithoutReachingDownstream` — `@ParameterizedTest @CsvSource`: `GET,/api/v1/profiles/me`; `GET,/api/v1/users/me`; `PUT,/api/v1/profiles/123`; `POST,/api/v1/interviews`; `GET,/api/v1/users/me/verification`; `POST,/api/v1/users/me/verification/`. Con `Authorization: Bearer token-sin-verificar`: 403; `Content-Type` comparado como las pruebas del 401 de CM-260 (tipo `application/json` y charset `UTF-8`); cuerpo exacto `{"code":"EMAIL_NOT_VERIFIED","message":"Tu correo aún no está verificado."}` leído como `String` en UTF-8; `X-Request-Id` presente; `mockDownstream.getRequestCount()` igual al de antes.
  2. `unverifiedEmail_onVerificationRoute_passesThroughWithFalseHeader` — `POST /api/v1/users/me/verification`, token `false` → llega al destino con `X-User-Email-Verified: false`.
  3. `verifiedEmail_onPrivateRoute_passesThrough` — claim `true` en `GET /api/v1/profiles/me` → llega con `X-User-Email-Verified: true`.
  4. Claim ausente: ya lo cubre `tokenWithoutEmailVerifiedClaim_omitsHeader`; **no se duplica**, solo se comprueba que sigue en verde.
  5. `unverifiedEmail_onPublicRoute_isNotEvaluated` — `POST /api/v1/users` con `Authorization: Bearer token-sin-verificar` → llega al destino sin `Authorization` de Firebase ni `X-User-*`; `firebaseAuth.verifyIdToken` no se llama.
  6. `clientHeaderSayingVerified_doesNotOverrideFalseClaim` — claim `false` y cabecera del cliente `X-User-Email-Verified: true` en `GET /api/v1/profiles/me` → 403.
  7. `unverifiedRejection_isLoggedWithoutSensitiveData` — con `X-Request-Id: req-sin-verificar`: la salida contiene `req-sin-verificar` y `EMAIL_NOT_VERIFIED`, y no contiene `token-sin-verificar`, el correo ni el uid del token.
  8. `invalidOrMissingToken_stillReturns401` — sin `Authorization` y con un token que Firebase rechaza → 401 `AUTH_REQUIRED` (nunca 403).
- **Resultado esperado:** 1, 6 y 7 **fallan** (hoy se reenvía); 2, 3, 5 y 8 pasan. Anotar ambas salidas.
- **Trampa:** las rutas de la prueba 1 no deben encolar respuestas en `mockDownstream`: no tienen que llegar.
- **Verificación:** solo fallan las pruebas esperadas. Commit 2 de `plan.md`.

## [x] T-3.2 · La decisión en el filtro — ≤ 30 min

**Resultado:** `FirebaseAuthGlobalFilterTest`, `GatewayArchTest` y `OidcSigningFilterTest` en verde (50 pruebas en la primera, 0 fallos). Sin clases nuevas.

- **Cubre:** REQ-EV-01 a 09. **Modificar:** solo `src/main/java/tech/cameia/gateway/filter/FirebaseAuthGlobalFilter.java`.
- **Constantes** (junto a las demás `static final`, con Javadoc):
  ```java
  /** Única ruta que una cuenta con el correo sin verificar puede usar: es la forma de salir de ese estado. */
  static final String VERIFICATION_PATH = "/api/v1/users/me/verification";

  /** Código del rechazo por correo sin verificar; lo reserva el contrato del proyecto. */
  static final String EMAIL_NOT_VERIFIED_CODE = "EMAIL_NOT_VERIFIED";
  ```
- **`flatMap`** (línea 244):
  ```java
  // antes
  .flatMap(decodedToken -> chain.filter(withIdentity(exchange, decodedToken, requestId)));
  // después
  .flatMap(decodedToken -> shouldReject(exchange.getRequest(), decodedToken)
          ? writeEmailNotVerified(exchange, requestId)
          : chain.filter(withIdentity(exchange, decodedToken, requestId)));
  ```
- **Funciones privadas** (Javadoc en español en cada una):
  ```java
  /** El token ya lo aceptó Firebase: falta decidir si su correo permite usar esta ruta. */
  private boolean shouldReject(ServerHttpRequest request, FirebaseToken token) {
      return "false".equalsIgnoreCase(readEmailVerified(token)) && !isVerificationRoute(request);
  }

  /** Método y ruta exactos, sin normalizar: ninguna variante queda exceptuada. */
  private boolean isVerificationRoute(ServerHttpRequest request) {
      return HttpMethod.POST.equals(request.getMethod()) && VERIFICATION_PATH.equals(request.getPath().value());
  }

  private Mono<Void> writeEmailNotVerified(ServerWebExchange exchange, String requestId) {
      // El rechazo queda trazado con su identificador; nunca el token, el correo ni el uid
      log.warn("Solicitud rechazada por correo sin verificar [code={}, requestId={}]", EMAIL_NOT_VERIFIED_CODE, requestId);
      return writeError(exchange, HttpStatus.FORBIDDEN, EMAIL_NOT_VERIFIED_CODE, "Tu correo aún no está verificado.");
  }

  /** Escribe {@code {code,message}} con charset; code y message son siempre textos fijos, nunca datos de la petición. */
  private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String code, String message) {
      String body = """
              {"code":"%s","message":"%s"}""".formatted(code, message);
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponse().setStatusCode(status);
      exchange.getResponse().getHeaders().setContentType(APPLICATION_JSON_UTF8);
      DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
      return exchange.getResponse().writeWith(Mono.just(buffer));
  }
  ```
  `writeUnauthorized` (línea 460) conserva su `log.warn` y su Javadoc y termina con `return writeError(exchange, HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", message);`. Javadoc de la clase: un párrafo «Rechaza con 403 `EMAIL_NOT_VERIFIED` un token cuyo claim `email_verified` es `false`, salvo en `POST /api/v1/users/me/verification`; la ausencia del claim no bloquea». El Javadoc de `readEmailVerified` (línea 395) sigue siendo válido.
- **Trampas:** `readEmailVerified` devuelve `null` si el claim no existe y `"false".equalsIgnoreCase(null)` es `false`: la ausencia no bloquea. No definir otra constante de charset. No mover el `log.warn` del 401 a `writeError`.
- **Verificación:** `./mvnw.cmd -q -B "-Dtest=FirebaseAuthGlobalFilterTest,GatewayArchTest,OidcSigningFilterTest" test` en verde completo (incluidas `unauthorizedResponse_includesRequestId` y las de charset de CM-260). Commit 3.

## [x] T-3.3 · Documentación del repo — ≤ 20 min

**Resultado:** `CLAUDE.md` (3 líneas + fila de pendientes), `specs/NUMERACIONES.md`, `docs/errores.md` y una frase en `docs/DOCKER-LOCAL.md`.

- **Cubre:** REQ-EV-10. **Modificar:** `CLAUDE.md`, `specs/NUMERACIONES.md`, `docs/errores.md`. **No tocar** `AGENTS.md`, `docs/COMO-FUNCIONA.md` (no menciona la regla) ni `docs/estandar-backend.md` (lo alinea CM-296).
- **`CLAUDE.md`** (`develop`): fila de `X-User-Email-Verified` (línea 129) → «claim `email_verified` del token | Sí, en Caso A; `false` solo llega a `POST /api/v1/users/me/verification`, porque en las demás rutas privadas el Gateway responde `403`; se omite si el claim no existe». Viñeta de la línea 134 → «El Gateway rechaza con `403` `{"code":"EMAIL_NOT_VERIFIED","message":"Tu correo aún no está verificado."}` (charset UTF-8) toda ruta de Caso A cuando `email_verified` es `false`, salvo `POST /api/v1/users/me/verification` (método y ruta exactos); el claim ausente no bloquea. cameia-cuentas vuelve a comprobar en la activación». Quitar la fila de `GW-TBD-17` de la tabla de pendientes (línea 232). Buscar con `git grep -n "GW-TBD-17\|no bloquea" CLAUDE.md` cualquier otra mención y alinearla. Si la tabla de componentes describe `FirebaseAuthGlobalFilter`, agregar «`403` si el correo no está verificado».
- **`specs/NUMERACIONES.md`:** línea 50 (`GW-TBD-17`) → «✅ Cerrado: el Gateway rechaza con `403` los tokens con `email_verified=false`, salvo la ruta de verificación (CM-179, CA-1.2.13)». Línea 59 (`GW-TBD-26`) → «🔁 Sustituido por `GW-TBD-17`: el Gateway propaga y además rechaza (CM-179)».
- **`docs/errores.md`:** fila `| EMAIL_NOT_VERIFIED | 403 | Tu correo aún no está verificado. | FirebaseAuthGlobalFilter: token aceptado por Firebase con el claim email_verified en false, en una ruta de Caso A distinta de POST /api/v1/users/me/verification | FirebaseAuthGlobalFilterTest: unverifiedEmail_onPrivateRoute_returns403WithoutReachingDownstream, clientHeaderSayingVerified_doesNotOverrideFalseClaim |` (con el formato de código de las demás filas).
- **Trampa:** las specs históricas `specs/CM-14-*` no se reescriben.

## [x] T-3.4 · Postman con el emulador — ≤ 30 min

**Resultado:** carpeta `V-00` a `V-03` corrida con Newman contra el JAR empaquetado y el emulador: 4 peticiones, 9 aserciones, 0 fallos. Se usó autenticación de carpeta (`{{idToken}}`) porque la colección trae una global con `{{firebaseToken}}` que el emulador rechaza con 401.

- **Cubre:** P7 del estándar; SEG-02. **Modificar:** `docs/cameia-gateway-perfil.postman_collection.json` y, si existe, su guía (`docs/DOCKER-LOCAL.md` u otra; buscar con `git grep -n -i postman docs README.md`).
- **Carpeta «Correo sin verificar (emulador)»**, con el compose del Gateway y su emulador (`docs/DOCKER-LOCAL.md`):
  1. `V-00 · alta en el emulador`: `POST http://localhost:9099/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake-api-key` con correo y contraseña sintéticos (`prueba+{{$timestamp}}@ejemplo.test`) → guarda `idToken` (nace con `email_verified=false`).
  2. `V-01 · 403 ruta privada`: `GET {{baseUrl}}/api/v1/profiles/me` con `Bearer {{idToken}}` → 403, cuerpo exacto y `charset=UTF-8`.
  3. `V-02 · la ruta de verificación no da 403`: `POST {{baseUrl}}/api/v1/users/me/verification` → cualquier estado distinto de 403 y 401 (si Cuentas no está levantado, el Gateway responde 503 `SERVICE_UNAVAILABLE`, lo que prueba que reenvió).
  4. `V-03 · 401 sin token`: `GET {{baseUrl}}/api/v1/profiles/me` sin `Authorization` → 401 `AUTH_REQUIRED`.
- Verificar con el emulador real antes de escribir la URL de `signUp`: si el emulador del compose usa otro puerto o proyecto, usar el de `docker-compose.yml`. Commit 4 (con T-3.3).

## [~] T-3.5 · Verificación única, revisión y entrega — ≤ 30 min

- `./mvnw.cmd -B clean verify` con salida real en `ejecucion/evidencia/CM-179/verify-gateway.log`; cobertura de `FirebaseAuthGlobalFilter` sobre lo nuevo (las dos ramas de `shouldReject` y de `isVerificationRoute`, y `writeError`) ≥ 90 % de líneas y de ramas; Newman de la carpeta nueva, con salida guardada.
- **Lista de verificación** de la spec (sección 15, G-01 a G-10) fila por fila, con la salida real en `ejecucion/evidencia/CM-179/resumen.md` (sección del Gateway), y la tabla «regla R1–R14 → evidencia» según la sección 13 de la spec. No se marca `cumple` lo que no se ejecutó.
- `/simplify`, `/code-review high`, `/security-review` (autenticación y autorización por función). Autochequeo: ¿algún log con token, correo o uid? ¿algún comentario con `CM-NNN`?
- Cuerpo del PR en `ejecucion/cuerpos/` de la raíz con la plantilla del repo, validado con `.github/scripts/pr_policy.py` (`Impacto: contrato` con su línea `Contrato:`; PR en **Draft** con `Control humano: pendiente`). Título: `CM-179 | feat(gateway): rechazar con 403 los tokens con el correo sin verificar [IA-ASISTIDO]`. Qué es mecánico (extracción de `writeError`, documentación, Postman) y qué importa revisar (`shouldReject`, `isVerificationRoute`, pruebas 1, 6 y 7); tabla «requisito o caso borde → prueba».
- **Push y PR solo con el sí de Paula.** Aviso a Frontend (403 `EMAIL_NOT_VERIFIED` en rutas privadas; aplicar RT-02-CA06) y a Testing (`Content-Type` sin espacio antes de `charset`).
