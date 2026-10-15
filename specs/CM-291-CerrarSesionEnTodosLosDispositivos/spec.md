# Spec — CM-291: cerrar sesión en todos los dispositivos (Cuentas y Gateway)

- **Tarea Jira:** CM-291, subtarea de Backend de CM-194 («HU-1.8 Cerrar sesión», Sprint 3 en Jira). Responsable: Paula Andrea Muñoz Delgado.
- **Historia:** HU-1.8 del backlog v7 (`12102026_01_Backlog_v7.xlsx`, hoja HE-01, 12 criterios), que incluye la antigua HU-1.11.
- **Repositorios:** `cameia-cuentas` (endpoint que revoca las sesiones) y `cameia-gateway` (rechazo de los tokens revocados). La misma spec
  vive en los dos repositorios; el plan y las tarjetas de cada uno están junto a ella.
- **Base:** `origin/develop` de Cuentas `5170d2f` y del Gateway `1f7b8ee` (13-oct-2026).
- **Estado:** aprobada por Paula Andrea Muñoz Delgado el 13-oct-2026 (spec, plan y tarjetas). Puerta de listo en la sección 16.
- **Atributos de calidad:** seguridad (AC-0003: SEG-01, SEG-02, SEG-03, SEG-04), fiabilidad (AC-0004: FIA-01, FIA-02), desempeño
  (AC-0002: DES-02, DES-04), interacción (AC-0006), interoperabilidad y mantenibilidad (IOP-01, IOP-02, MAN-01, MAN-02), restricciones
  REST-0002 y REST-0003. Métrica y prueba de cada uno en la sección 11.

## 1. Objetivo

Que una persona con sesión iniciada pueda cerrar la sesión en todos sus dispositivos:

1. **Cuentas** expone `POST /api/v1/auth/logout-all`, que revoca en Firebase todas las sesiones del usuario que propaga el Gateway y
   responde `204 No Content`.
2. **El Gateway** verifica la revocación en cada petición privada (`verifyIdToken(token, true)`) y responde `401 AUTH_REQUIRED` a un
   token emitido antes de la revocación, de una cuenta deshabilitada o de una cuenta eliminada, sin reenviar nada al microservicio.
3. La activación de esa verificación **no puede repetir el fallo de septiembre** (todas las personas recibieron 401): la sección 3
   prueba su causa con datos reales y la spec la neutraliza.

Lo que pasa en el dispositivo actual (cerrar la sesión de Firebase, borrar el estado local, confirmación, «Atrás») es de Frontend
(CM-196) y no se construye aquí.

## 2. Fuentes leídas (vigentes)

| Fuente | Qué aporta |
|---|---|
| Backlog v7, HE-01, HU-1.8 (12 CA) y RT-02 | Requisitos y textos. Notas técnicas: endpoint provisional `POST /api/v1/auth/logout-all`, `revokeRefreshTokens(uid)`, `verifyIdToken(checkRevoked=true)`, riesgo de repetir el 401 general, CA-1.8.12 como regresión |
| Jira CM-291 | Decisión del PO del 9-oct (opción B): la tarea es el endpoint de Cuentas; la verificación del Gateway «va aparte, en el Sprint 3». Sin comentarios. Sprint 3 (heredado de CM-194) |
| Jira CM-194 | Tarea padre, «En curso», Sprint 3; subtareas CM-196 (Frontend, «En revisión») y CM-291. Un comentario del 5-oct, ya superado por la v6 (que fusiona HU-1.11) |
| Jira CM-232 (cerrada) | El 21-sep el Gateway activó `checkRevoked` y desde el 22-sep todas las peticiones a Perfil respondían 401; se retiró el 25-sep (PR #55). La causa anotada entonces (una carrera con `tokensValidAfterTime`) no se demostró |
| Código `origin/develop` | `FirebaseAuthGlobalFilter` (Gateway), `FirebaseConfig` (Gateway), `FirebaseUserDirectory` y `FirebaseUserDirectoryAdapter` (Cuentas), `BusinessExceptionHandler`, `ErrorCode`, `docs/errores.md` de los dos repos, colecciones de Postman |
| Google Cloud, proyecto `cameia-e245f` (13-oct, solo lectura) | Cuenta de servicio de cada servicio y sus roles (sección 3) |
| Firebase Admin SDK 9.11.0 (`RevocationCheckDecorator`, leído con `javap`) | Cómo decide el SDK que un token está revocado y qué excepciones lanza |
| `cameia-web` `origin/develop` (solo lectura) | Aún no llama al endpoint; su `src/features/auth/SPEC.md` ya cita `POST /api/v1/auth/logout-all` |
| Matriz ASVS de `cameia-infra` | 7.4.1 y 7.4.2 en `PENDIENTE` por falta de revocación |

## 3. Causa raíz del 401 general de septiembre (comprobada)

**Hechos.**

1. El 21-sep el commit `bce4045` cambió `firebaseAuth.verifyIdToken(idToken)` por `verifyIdToken(idToken, true)` (comentario de
   Paula en CM-232).
2. Con `true`, el SDK hace después de validar la firma una **segunda llamada de red**: `FirebaseUserManager.getUserById(uid)` (leído en
   `RevocationCheckDecorator.validateDisabledOrRevoked` del jar 9.11.0). Esa llamada es una lectura de usuarios de Identity Toolkit,
   que exige el permiso `firebaseauth.users.get`.
3. La cuenta de servicio del Gateway en Cloud Run es `cameia-gateway-run@cameia-e245f.iam.gserviceaccount.com`
   (`gcloud run services describe cameia-gateway`). En la política IAM del proyecto **no tiene ningún rol de Firebase Auth**; la de
   Cuentas, `cameia-cuentas-run@…`, tiene `roles/firebaseauth.admin` (`gcloud projects get-iam-policy cameia-e245f`, 13-oct).
4. Sin ese permiso, `getUserById` falla con un `FirebaseAuthException` de código `PERMISSION_DENIED` y sin código de autenticación.
5. `FirebaseAuthGlobalFilter.isTokenRejection` (`FirebaseAuthGlobalFilter.java:290`) trata **cualquier** `FirebaseAuthException`
   como token inválido y responde 401. Resultado: 401 para toda persona con un token válido, que es lo observado (usuarios antiguos y
   recién creados).

**La hipótesis anterior no se sostiene.** El SDK marca un token como revocado solo si `tokensValidAfterTimestamp > iat × 1000`
(comparación estricta). Al crear una cuenta, `tokensValidAfterTimestamp` es el segundo de creación y el token del primer ingreso
tiene un `iat` igual o posterior: no puede salir revocado. Además, la carrera no explicaría el 401 de usuarios antiguos.

**Clasificación:** inferencia justificada con hechos verificados (no se pudo leer la excepción original: el filtro solo registraba
«Solicitud rechazada por autenticación» sin la causa). La spec la convierte en hechos así:

| Paso | Cómo se demuestra |
|---|---|
| ANTES (código actual) | Prueba nueva que hoy falla: un `FirebaseAuthException(PERMISSION_DENIED, sin código de autenticación)` en la verificación responde **401** (`revocationLookupPermissionDenied_returns503AuthUnavailable` falla primero con 401) |
| DESPUÉS (código nuevo) | La misma prueba responde 503 `AUTH_UNAVAILABLE` y no cierra la sesión de nadie; el Gateway **no arranca** sin el permiso (sección 7.3), así que Cloud Run conserva la revisión anterior |
| En staging | DevOps concede el rol antes de fusionar el Gateway; tras desplegar, CA-1.8.12 (sesión vigente → 200) es la regresión |

## 4. Alcance

**Dentro.**

- Cuentas: `POST /api/v1/auth/logout-all` (controlador, caso de uso, operación nueva del puerto de Firebase y su adaptador, doble en
  memoria), OpenAPI, `docs/errores.md`, `CLAUDE.md` y colección de Postman.
- Gateway: ruta `/api/v1/auth/**` hacia Cuentas; verificación con revocación; clasificación de los fallos de Firebase (401 frente a
  503); comprobación del permiso al arrancar; tiempos de espera del Admin SDK; código nuevo en el catálogo; ADR; `CLAUDE.md`;
  colección de Postman.

**Fuera (con destino).**

| Qué | Destino |
|---|---|
| Cerrar la sesión de Firebase, borrar `lastUsedProfileId`, confirmación, «Cancelar», «Atrás», mensaje «Tu sesión expiró…» (CA-1.8.1 en su parte F, 1.8.3 a 1.8.8, RT-02-CA02) | Frontend, CM-196 |
| Llamar a `POST /api/v1/auth/logout-all` antes de `signOut` y qué mostrar si responde 503 | Frontend, aviso en el documento para Frontend (sección 14, A-2) |
| Rol `roles/firebaseauth.viewer` para la cuenta del Gateway | DevOps, por una tarea que abre Vela (sección 14, A-1) |
| Cuenta de staging deshabilitada y eliminada para CA-1.8.10 y CA-1.8.11 | DevOps la prepara y Testing ejecuta (nota técnica de HU-1.8) |
| Actualizar 7.4.1 y 7.4.2 en la matriz ASVS | DevOps, vía Vela (A-3) |
| Revocar al deshabilitar o eliminar una cuenta desde Cuentas | No hay operación de deshabilitar en el MVP; la purga ya borra la credencial, y un token de una credencial borrada sale rechazado por CA-1.8.11 |
| Extraer el identificador de Firebase a un objeto de valor (tercera repetición de la regla del largo) | CM-296, alineación del estándar (hallazgo H-4) |

## 5. Contrato

### 5.1 Cuentas: `POST /api/v1/auth/logout-all`

| Aspecto | Valor |
|---|---|
| Ruta y método | `POST /api/v1/auth/logout-all` (literal de CA-1.8.1 y CA-1.8.2; es la que `cameia-web` ya espera) |
| Identidad | `X-User-Id`, que pone el Gateway. Nada del cuerpo, la consulta ni la ruta decide qué sesiones se revocan |
| Cuerpo de la petición | Ninguno. Si llega uno, se ignora y no se lee, cualquiera que sea su `Content-Type` salvo `multipart/form-data` sin `boundary`, que hoy termina en el 500 genérico de todo el servicio (tarea aparte, D-25) |
| Éxito | `204 No Content`, sin cuerpo y sin `Content-Type` |
| Idempotencia | Repetir la llamada responde 204 otra vez y mueve el instante de revocación |
| Consumidor | `cameia-web` (CM-196) a través del Gateway |

### 5.2 Gateway

| Ruta | Destino | Caso |
|---|---|---|
| `/api/v1/auth/**` (nueva, id `cameia-cuentas-auth`) | `${CAMEIA_CUENTAS_URL}` | A (exige token; no entra en `PUBLIC_ROUTES`) |

Toda ruta de Caso A pasa a verificarse con revocación. Respuestas que escribe el filtro:

| Situación | Estado | Cuerpo |
|---|---|---|
| Token ausente, vacío o sin esquema Bearer | 401 | `{"code":"AUTH_REQUIRED","message":"Token de acceso requerido"}` (sin cambio) |
| Firebase rechaza el token: firma, formato, vencido, **revocado**, **cuenta deshabilitada**, **cuenta eliminada** | 401 | `{"code":"AUTH_REQUIRED","message":"Token de acceso inválido"}` (mismo cuerpo de hoy; decisión P-3) |
| Firebase no pudo confirmar el token (no responde, tiempo agotado, cuota, permisos, claves públicas no descargables) | 503 | `{"code":"AUTH_UNAVAILABLE","message":"Servicio de autenticación no disponible"}` (nuevo; decisión P-2) |
| Correo sin verificar | 403 | `EMAIL_NOT_VERIFIED` (sin cambio; también en `/api/v1/auth/logout-all`) |

Todas con `Content-Type: application/json;charset=UTF-8` y `X-Request-Id`. El cambio de contrato es **aditivo** (un código nuevo, 503);
se avisa a Frontend.

## 6. Requisitos (EARS)

### 6.1 Cuentas

- **REQ-291-01 (CA-1.8.1, CA-1.8.2).** Cuando Cuentas reciba `POST /api/v1/auth/logout-all` con un `X-User-Id` válido, Cuentas deberá
  revocar en Firebase todas las sesiones de ese usuario (`FirebaseAuth.revokeRefreshTokens(uid)`) y responder `204 No Content` sin
  cuerpo.
- **REQ-291-02 (CA-1.8.2; RT-01-CA06; OWASP API1 y API3).** Cuentas deberá tomar el usuario solo de `X-User-Id`. Si el cuerpo, la
  consulta o un encabezado distinto nombran a otro usuario (por ejemplo `{"firebaseUid":"uid-ajeno"}` o `?uid=uid-ajeno`), deberá
  ignorarlos y revocar solo las sesiones del usuario de `X-User-Id`.
- **REQ-291-03.** Si falta `X-User-Id`, Cuentas deberá responder `400` `IDENTITY_REQUIRED` con «La petición no incluye los datos que
  exige esta ruta.» y no llamar a Firebase.
- **REQ-291-04.** Si `X-User-Id` llega vacío, solo con espacios o con más de 128 caracteres, Cuentas deberá responder lo mismo que en
  REQ-291-03 y no llamar a Firebase.
- **REQ-291-05 (FIA-02).** Si Firebase responde que el usuario no existe (`USER_NOT_FOUND`), Cuentas deberá responder `204`: no queda
  ninguna sesión que revocar. Registra `INFO` con el `uid`.
- **REQ-291-06 (FIA-01).** Si Firebase no responde, agota el tiempo, falla de su lado o agota la cuota (`UNAVAILABLE`,
  `DEADLINE_EXCEEDED`, `INTERNAL`, `RESOURCE_EXHAUSTED`, o causa de E/S sin respuesta HTTP), Cuentas deberá responder `503`
  `DEPENDENCY_UNAVAILABLE` con «Ocurrió un error. Inténtalo de nuevo.», registrar `ERROR` con traza, `code` y `requestId`, y no
  responder 204.
- **REQ-291-07.** Si Firebase rechaza la revocación por permisos o configuración (`PERMISSION_DENIED`, `UNAUTHENTICATED` u otro código),
  Cuentas deberá responder `500` `INTERNAL_ERROR` con «Ocurrió un error. Inténtalo de nuevo.» y registrar el código del servicio en
  `ERROR`. Es la regla ya publicada de Cuentas para un defecto de despliegue (`docs/errores.md`, «Respuestas publicadas que difieren
  del estándar»), y Cuentas ya tiene `roles/firebaseauth.admin`.
- **REQ-291-08.** Ante `GET`, `PUT`, `PATCH` o `DELETE` sobre la ruta, Cuentas deberá responder `405` `METHOD_NOT_ALLOWED` con el
  encabezado `Allow: POST` (comportamiento ya existente del manejador).
- **REQ-291-09 (SEG-04).** Cuentas no deberá registrar el token, el correo ni el cuerpo de la petición; el log de éxito lleva solo el
  `uid` («Sesiones revocadas en Firebase para el usuario {uid}»).

### 6.2 Gateway

- **REQ-291-10 (CA-1.8.2, CA-1.8.9, CA-1.8.12; ASVS 7.4.1).** El Gateway deberá verificar todo token de Caso A con
  `firebaseAuth.verifyIdToken(token, true)`.
- **REQ-291-11 (CA-1.8.9).** Cuando Firebase responda que el token está revocado (`AuthErrorCode.REVOKED_ID_TOKEN`), el Gateway deberá
  responder `401` `AUTH_REQUIRED` «Token de acceso inválido» sin reenviar la petición.
- **REQ-291-12 (CA-1.8.10; ASVS 7.4.2).** Cuando Firebase responda que la cuenta está deshabilitada (`USER_DISABLED`), igual que
  REQ-291-11.
- **REQ-291-13 (CA-1.8.11; ASVS 7.4.2).** Cuando Firebase responda que la cuenta no existe (`USER_NOT_FOUND`), igual que REQ-291-11.
- **REQ-291-14.** Los demás rechazos del token (`INVALID_ID_TOKEN`, `EXPIRED_ID_TOKEN`, `TENANT_ID_MISMATCH` e
  `IllegalArgumentException`) siguen respondiendo `401` `AUTH_REQUIRED` «Token de acceso inválido».
- **REQ-291-15 (FIA-01; decisión P-2).** Cuando la verificación falle con un `FirebaseAuthException` cuyo código de autenticación no
  sea ninguno de REQ-291-11 a REQ-291-14 (incluido `null` y `CERTIFICATE_FETCH_FAILED`), el Gateway deberá responder `503`
  `AUTH_UNAVAILABLE` «Servicio de autenticación no disponible», no reenviar la petición y registrar `ERROR` con traza,
  `code=AUTH_UNAVAILABLE`, `requestId` y el `ErrorCode` del SDK.
- **REQ-291-16.** Cualquier otra excepción de la verificación (no `FirebaseAuthException` ni `IllegalArgumentException`) sigue
  llegando a `GlobalErrorHandler` (500 `INTERNAL_ERROR`), como hoy.
- **REQ-291-17 (SEG-04, trazabilidad).** Todo 401 del filtro deberá registrarse en `WARN` como
  `Solicitud rechazada por autenticación [requestId=<id>] [causa=<causa>]`, con `causa` = `TOKEN_MISSING` (sin token, vacío o sin
  Bearer), `TOKEN_MALFORMED` (`IllegalArgumentException`) o el nombre del `AuthErrorCode` (`REVOKED_ID_TOKEN`, `USER_DISABLED`,
  `USER_NOT_FOUND`, `INVALID_ID_TOKEN`, `EXPIRED_ID_TOKEN`, `TENANT_ID_MISMATCH`). Nunca el token, el `uid` ni el correo.
- **REQ-291-18 (CA-1.8.1, CA-1.8.2).** Cuando llegue `POST /api/v1/auth/logout-all` con un token válido y el correo verificado, el
  Gateway deberá reenviarla a Cuentas con los `X-User-*` del token, sin el `Authorization` de Firebase y firmada con OIDC, como
  cualquier ruta de Caso A.
- **REQ-291-19 (decisión P-1, evita repetir el 401 general).** Al arrancar con Firebase habilitado (fuera del perfil `test`), el
  Gateway deberá consultar `firebaseAuth.getUser("cameia-gateway-permission-probe")`:
  - `USER_NOT_FOUND` o un usuario encontrado → arranca y registra `INFO` «Permiso de lectura de usuarios de Firebase confirmado».
  - `PERMISSION_DENIED`, `UNAUTHENTICATED` o `CONFIGURATION_NOT_FOUND` (proyecto de Firebase equivocado) → **no arranca** (`IllegalStateException` con el mensaje «La cuenta de servicio del Gateway
    no puede leer usuarios de Firebase Auth: sin ese permiso cada petición respondería 503. Conceda roles/firebaseauth.viewer»).
  - Cualquier otro fallo (red, tiempo agotado, Firebase caído) → arranca y registra `WARN` «No se pudo comprobar el permiso de lectura
    de usuarios de Firebase [errorCode=<código>]»: una caída pasajera no debe impedir que escale.
- **REQ-291-20 (REST-0002, R6).** El Admin SDK del Gateway deberá usar 3000 ms de espera de conexión y 5000 ms de lectura (los mismos
  valores de Cuentas, `FirebaseConfiguration`). Hoy no tiene ninguno.

## 7. Reglas, decisiones y alternativas

### 7.1 Hechos y decisiones ya cerradas

| # | Tema | Resolución | Tipo y fuente |
|---|---|---|---|
| H-1 | Ruta | `POST /api/v1/auth/logout-all`, literal del CA. Es provisional en la nota técnica, pero `cameia-web` ya la cita y ninguna otra fuente la cambia | Hecho: backlog v7 CA-1.8.1/1.8.2; `cameia-web/src/features/auth/SPEC.md` |
| H-2 | Clave de Jira del Gateway | CM-291 cubre los dos repositorios (rama `CM-291-…` en cada uno) | Decisión de Paula Andrea Muñoz Delgado (13-oct-2026): CM-291 cubre Cuentas y Gateway; el 12-oct decidió no pedir otra clave al Product Owner |
| H-3 | Usuario inexistente en Firebase al revocar | 204 | Inferencia con precedente: `FirebaseUserDirectoryAdapter.deleteUser` ya da por hecha la operación ante `USER_NOT_FOUND`; el resultado que la persona pide (ninguna sesión abierta) se cumple y no revela si la cuenta existe |
| H-4 | Regla del identificador | Nulo, en blanco o > 128 → `IDENTITY_REQUIRED` usando `GetCurrentAccountService.MAX_FIREBASE_UID_LENGTH`, igual que `ActivateAccountService` | Precedente del repo. Es la tercera repetición: la extracción a un objeto de valor va a CM-296 (sección 4) para no refactorizar fuera del alcance |
| H-5 | Cuenta local | No se consulta la tabla `cuenta`: revocar no depende del estado local y nunca daña | Inferencia: CA-1.8.2 solo pide `revokeRefreshTokens` con el `uid` del token; leer la base añadiría una causa de 500 sin requisito |
| H-6 | Caché de la verificación de revocación | Ninguna: cada petición consulta a Firebase | Inferencia: CA-1.8.9 exige que el otro dispositivo pierda el acceso «en su siguiente uso»; una caché lo retrasaría. Costo medido en la sección 11 (DES-02) |
| H-7 | Mismo segundo | Un token emitido en el mismo segundo de la revocación sigue valiendo (el SDK compara `tokensValidAfterTimestamp > iat × 1000`) | Hecho del SDK. Sin efecto práctico: el dispositivo actual cierra su sesión de Firebase justo después del 204 |
| H-8 | Reintentos del SDK | El Admin SDK reintenta las llamadas de red hasta 4 veces con espera creciente; con 5 s de lectura, el peor caso de una petición con Firebase lento ronda los 25 s, y ese tiempo no lo acota `GATEWAY_TIMEOUT_MS` (30 s), que solo corre al reenviar al destino: la petición espera hasta ese tiempo con un hilo ocupado en `boundedElastic`. Se mide con DES-02 y DES-04 en staging | Hecho documentado en Cuentas (`docs/errores.md`); se deja escrito como límite conocido |

### 7.2 Decisiones de Paula

P-1, P-2 y P-3: **opción A, decididas por Paula Andrea Muñoz Delgado el 13-oct-2026**.

**P-1. Cómo se activa la verificación sin repetir el 401 general.** Decidida: A.

| Opción | Qué pasa |
|---|---|
| **A (recomendada)** | DevOps concede `roles/firebaseauth.viewer` a `cameia-gateway-run` **antes** de fusionar el Gateway. El Gateway comprueba el permiso al arrancar (REQ-291-19): si falta, la revisión nueva no arranca y Cloud Run sigue sirviendo la anterior, sin 401 ni 503 para nadie. Tras el despliegue, Backend corre CA-1.8.12 y CA-1.8.9 en staging |
| B | Un interruptor por variable de entorno (`GATEWAY_CHECK_REVOKED`). Permite apagar la verificación sin desplegar, pero cualquiera con acceso a la configuración puede desactivar ASVS 7.4.1 sin que se note; el `CLAUDE.md` del Gateway ya evita este patrón con la firma OIDC |
| C | Solo el rol IAM y revertir a mano si algo falla. Si el rol falta, cada petición responde 503 hasta revertir |

**P-2. Qué responde el Gateway si Firebase no puede confirmar el token.** Decidida: A.

| Opción | Qué pasa |
|---|---|
| **A (recomendada)** | 503 con el código nuevo `AUTH_UNAVAILABLE` y «Servicio de autenticación no disponible». Un código por causa: Frontend distingue «la sesión ya no vale» (401) de «inténtalo de nuevo» (503), y la sesión del usuario se conserva |
| B | 503 con el código existente `SERVICE_UNAVAILABLE` («Servicio destino no disponible»). No toca el catálogo, pero mezcla dos causas y el mensaje habla del servicio destino |
| C | 401 (lo de hoy). Una caída de Firebase cerraría la sesión de todas las personas: es el síntoma de septiembre |
| D | Aceptar el token sin comprobar la revocación. Un dispositivo perdido seguiría entrando mientras Firebase falle: incumple ASVS 7.4.1 |

**P-3. Qué código lleva el 401 de un token revocado, de una cuenta deshabilitada o de una eliminada.** Decidida: A.

| Opción | Qué pasa |
|---|---|
| **A (recomendada)** | El mismo `AUTH_REQUIRED` «Token de acceso inválido» de hoy; la causa exacta queda en el log (`causa=REVOKED_ID_TOKEN`, etc.). Frontend ya maneja el 401 de una sola forma (RT-02, CA-1.8.9 a 1.8.11 piden lo mismo: ir a «Iniciar sesión» sin datos) y no se revela a quien tenga el token si la cuenta fue deshabilitada o borrada |
| B | Un código por causa (`SESSION_REVOKED`, `ACCOUNT_DISABLED`, `ACCOUNT_NOT_FOUND`). Más preciso para depurar desde el cliente, pero ninguna pantalla lo usa, cambia el catálogo cerrado del Gateway y expone el estado de la cuenta |

### 7.3 Patrones y piezas

- Cuentas sigue el patrón de `GetCurrentAccountService` y `CurrentAccountController`: caso de uso con dependencias por constructor,
  controlador package-private, errores por el `BusinessExceptionHandler` existente. **No hay excepción ni código nuevo en Cuentas**:
  `IdentityRequiredException` y `DependencyUnavailableException` ya cubren los fallos.
- El adaptador reutiliza `unavailableOrRejection` (ya clasificado en `UntypedExceptionClassificationTest`).
- El Gateway agrega una sola clase, `FirebaseRevocationPermissionCheck` (paquete `config`), que corre en `@PostConstruct` para fallar
  **antes** de que el servidor escuche (un fallo en un `ApplicationRunner` llegaría después de abrir el puerto). Se descartó ponerla en
  `FirebaseConfig.init` porque `FirebaseConfigTest` arranca esa clase y haría una llamada de red real.
- Sin dependencias nuevas, sin migraciones, sin cambios en la base de datos.

## 8. Matriz de validación por campo (R2)

El endpoint no tiene cuerpo: la única entrada es `X-User-Id`.

| Campo | Caso | Valor literal | Resultado | Prueba |
|---|---|---|---|---|
| `X-User-Id` | Ausente | (sin encabezado) | 400 `IDENTITY_REQUIRED` «La petición no incluye los datos que exige esta ruta.»; Firebase no se llama | `logoutAll_shouldReturn400_whenIdentityHeaderMissing` |
| `X-User-Id` | Vacío | `""` | 400 `IDENTITY_REQUIRED` | `revokeAll_shouldRejectIdentity_whenBlank` (parametrizada) |
| `X-User-Id` | Solo espacios | `"   "` | 400 `IDENTITY_REQUIRED` | ídem |
| `X-User-Id` | Tabulador y salto | `"\t\n"` | 400 `IDENTITY_REQUIRED` | ídem |
| `X-User-Id` | Límite n−1 | 127 × `a` | 204 | `revokeAll_shouldRevoke_whenUidHasMaximumLength` (parametrizada 127 y 128) |
| `X-User-Id` | Límite n | 128 × `a` | 204 | ídem |
| `X-User-Id` | Límite n+1 | 129 × `a` | 400 `IDENTITY_REQUIRED`; Firebase no se llama | `revokeAll_shouldRejectIdentity_whenUidLongerThan128` |
| `X-User-Id` | Texto hostil | `' OR 1=1 --` | 204 (no hay SQL; Firebase responde `USER_NOT_FOUND`) y la respuesta no repite el valor | `logoutAll_shouldNotEchoIdentity_whenHostileValue`, Postman L-08 |
| Cuerpo | Campo de otro usuario | `{"firebaseUid":"uid-ajeno"}` con `X-User-Id: uid-propio` | 204; se revoca `uid-propio`, nunca `uid-ajeno` | `logoutAll_shouldRevokeHeaderUserOnly_whenBodyNamesAnotherUser`, Postman L-06 |
| Cuerpo | JSON mal formado | `{"x":` con `Content-Type: application/json` | 204 (el cuerpo no se lee) | `logoutAll_shouldIgnoreBody_whenMalformedJson` |
| Cuerpo | Tipo de contenido raro | `text/plain` `hola` | 204 | `logoutAll_shouldIgnoreBody_whenUnexpectedContentType` |
| Consulta | Otro usuario | `?uid=uid-ajeno&firebaseUid=uid-ajeno` | 204; se revoca el de `X-User-Id` | `logoutAll_shouldRevokeHeaderUserOnly_whenQueryNamesAnotherUser`, Postman L-07 |

El conteo de 128 es en unidades UTF-16: un `uid` de Firebase es ASCII, así que coincide con puntos de código (precedente de
`GetCurrentAccountService`). En el Gateway no hay campos nuevos: el token lo valida Firebase.

## 9. Base de datos (R4)

No aplica: la operación no lee ni escribe la base de Cuentas, y el Gateway no tiene base. Sin migración, sin columnas, sin
restricciones. La próxima migración libre de Cuentas sigue siendo `V8`, sin tocar.

## 10. Casos borde (R5)

| Grupo | Caso | Datos | Resultado esperado | Prueba o justificación |
|---|---|---|---|---|
| Presencia | Ver sección 8 | — | — | Sección 8 |
| Texto | Unicode, NFC, emoji | — | No aplica: el único texto es el `uid`, que no se normaliza ni se guarda | — |
| Números, fechas, enumerados | — | — | No aplica: no hay campos de esos tipos | — |
| Fechas | Mismo segundo de la revocación | Token con `iat` = segundo de revocación | Sigue valiendo (H-7) | Documentado; Postman espera 1,1 s antes de revocar para no confundirlo |
| Duplicados e idempotencia | Revocar dos veces | Dos `POST` seguidos, mismo `uid` | 204 y 204; dos llamadas a Firebase | `logoutAll_shouldReturn204Twice_whenRepeated` (E2E), Postman L-02 |
| Idempotencia (FIA-02) | 20 repeticiones | 20 `POST` | 20 × 204, sin otro efecto | `revokeAll_shouldHaveNoOtherEffect_whenRepeated20Times` |
| Estado | Usuario borrado en Firebase | `USER_NOT_FOUND` | 204 | `revokeSessions_shouldSucceed_whenUserNotFound` (adaptador), `revokeAll_shouldReturnNormally_whenUserDoesNotExist` |
| Estado | Cuenta local pendiente, activa o anonimizada | — | 204: no se consulta la base (H-5) | Justificado |
| Propiedad | Identidad en el cuerpo o en la consulta | Sección 8 | Solo `X-User-Id` | Sección 8 |
| Propiedad | Sin token en el Gateway | `POST /api/v1/auth/logout-all` sin `Authorization` | 401 `AUTH_REQUIRED`, Cuentas no recibe nada | `logoutAllRoute_withoutToken_returns401WithoutReachingDownstream` |
| Propiedad | `X-User-Id` falsificado por el cliente | Cliente envía `X-User-Id: uid-ajeno` con token de `uid-propio` | Cuentas recibe `uid-propio` | `logoutAllRoute_clientIdentityHeader_isReplacedByTokenUid` |
| Propiedad | Token revocado sobre el propio endpoint | Token viejo → `POST /api/v1/auth/logout-all` | 401: ya no hay sesión | Cubierto por `revokedToken_returns401WithoutReachingDownstream` (cualquier ruta A) |
| Propiedad | Correo sin verificar | Token `email_verified=false` | 403 `EMAIL_NOT_VERIFIED` | `logoutAllRoute_unverifiedEmail_returns403WithoutReachingDownstream` |
| Colecciones | — | — | No aplica: no hay listas | — |
| Concurrencia | Dos pestañas revocan a la vez | Dos `POST` simultáneos | 204 y 204 (la revocación de Firebase es idempotente; no hay estado local) | `logoutAll_shouldReturn204ForBoth_whenTwoConcurrentCalls` (E2E con dos hilos y `CountDownLatch`) |
| Falla parcial | Firebase revoca y la respuesta se pierde | — | El cliente reintenta y recibe 204 (idempotente) | Cubierto por la idempotencia |
| Dependencias (Cuentas) | Firebase caído o lento | `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `INTERNAL`, `RESOURCE_EXHAUSTED`, E/S sin respuesta | 503 `DEPENDENCY_UNAVAILABLE` | `revokeSessions_shouldThrowDependencyUnavailable_whenFirebaseFails` (parametrizada), `logoutAll_shouldReturn503_whenDirectoryUnavailable` |
| Dependencias (Cuentas) | Permiso o configuración | `PERMISSION_DENIED` | 500 `INTERNAL_ERROR` | `revokeSessions_shouldThrowIllegalState_whenFirebaseRejects`, `logoutAll_shouldReturn500_whenDirectoryRejects` |
| Dependencias (Gateway) | Firebase no confirma | `UNAVAILABLE`, `PERMISSION_DENIED`, `CERTIFICATE_FETCH_FAILED`, sin código | 503 `AUTH_UNAVAILABLE` | `revocationLookupFailure_returns503AuthUnavailable` (parametrizada) |
| Dependencias (Gateway) | Excepción ajena a Firebase | `IllegalStateException` | 500 `INTERNAL_ERROR` (sin cambio) | `firebaseUnexpectedFailure_isNotTreatedAsTokenRejection` (existente) |
| Dependencias (Gateway) | Arranque sin permiso | `PERMISSION_DENIED` en la sonda | No arranca | `check_shouldFailStartup_whenPermissionDenied` |
| Carga | Cuerpo grande | 50 KB de texto | 204: el cuerpo no se lee. El tope de tamaño de cuerpo sigue en la tarea pedida a Vela (pregunta 14 de CM-36) | Justificado; no se agrega prueba de carga |
| Carga | Método no admitido | `GET` | 405 `METHOD_NOT_ALLOWED`, `Allow: POST` | `logoutAll_shouldReturn405_whenGet`, Postman L-09 |

## 11. Atributos de calidad (R13)

| Atributo | Métrica | Límite | Cómo lo cumple | Prueba o medición |
|---|---|---|---|---|
| AC-0003 | SEG-01 (ASVS N1 aplicable) | 100 % | 7.4.1 y 7.4.2 pasan de `PENDIENTE` a cumplidos; 4.1.1 y 8.2.1 se mantienen | Sección 12 |
| AC-0003 | SEG-02 (tokens inválidos y accesos cruzados rechazados) | 100 % | Revocado, deshabilitado, eliminado, sin token y vencido → 401; identidad del cuerpo o del cliente ignorada | Pruebas del Gateway de la sección 10; Cuentas `…HeaderUserOnly…`; Postman G-06, G-08, G-09, L-06, L-07 |
| AC-0003 | SEG-03 (vulnerabilidades) | 0 críticas; sin dependencia nueva | Sin dependencias nuevas | `git diff origin/develop -- pom.xml` vacío |
| AC-0003 | SEG-04 (sin secretos ni datos personales en logs) | 0 | Logs con `uid`, `requestId` y causa; nunca token ni correo | `OutputCaptureExtension`: `revokedToken_isLoggedWithCauseWithoutToken`, `revokeSessions_shouldLogUidOnly` |
| AC-0004 | FIA-01 (conserva el último estado confirmado) | 100 % | Firebase caído: Cuentas 503 sin 204 falso; Gateway 503 sin cerrar sesiones; arranque sin permiso conserva la revisión anterior | `logoutAll_shouldReturn503_whenDirectoryUnavailable`, `revocationLookupFailure_returns503AuthUnavailable`, `check_shouldFailStartup_whenPermissionDenied` |
| AC-0004 | FIA-02 (repetir sin efectos extra) | 0 en 20 repeticiones | Revocación idempotente | `revokeAll_shouldHaveNoOtherEffect_whenRepeated20Times` |
| AC-0002 | DES-02 | p95 ≤ 2 s | Cuentas: una llamada a Firebase; Gateway: una lectura de usuario más por petición | Newman `pm.expect(pm.response.responseTime).to.be.below(2000)` en todas las peticiones de L y G; en staging, 20 peticiones `GET /api/v1/users/me` tras desplegar y p95 de `gcloud logging read` (latencia del Gateway) ≤ 2 s |
| AC-0002 | DES-04 (5xx propios < 1 %) | < 1 % | Los 503 solo aparecen si Firebase falla | En staging, 24 h después del despliegue: `gcloud logging read` del Gateway con `httpRequest.status>=500` / total < 1 % |
| AC-0006 | Errores identificables | `code` estable y mensaje en cada error | `AUTH_REQUIRED`, `AUTH_UNAVAILABLE`, `IDENTITY_REQUIRED`, `DEPENDENCY_UNAVAILABLE`, `METHOD_NOT_ALLOWED` | Controladores, filtro y Newman |
| IOP-01 | Contrato documentado | Ruta en OpenAPI | `@Operation` con 204, 400, 405, 500 y 503 | Newman L-10: con `API_DOCUMENTATION_ENABLED=true`, `/v3/api-docs` publica `paths['/api/v1/auth/logout-all'].post` con las respuestas 204, 400, 405, 500 y 503 |
| IOP-02 | Integración externa en un adaptador | 100 % | Firebase solo en `FirebaseUserDirectoryAdapter` (Cuentas) y en `filter`/`config` (Gateway) | `LayeredArchitectureTest`, `GatewayArchTest` |
| MAN-01 | Cobertura de lo nuevo o modificado | ≥ 90 % líneas y ramas | — | JaCoCo (sección 13) |
| MAN-02 | Adaptador probado en éxito, inválido, tiempo agotado y cuota | 4 casos | `revokeSessions`: éxito, `USER_NOT_FOUND`, `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`, `PERMISSION_DENIED` | `FirebaseUserDirectoryAdapterTest` |
| REST-0002 | Tiempos de espera y sin estado en memoria | — | 3 s / 5 s en el Admin SDK de los dos servicios; sin caché | `firebaseOptions_useConnectAndReadTimeouts` (Gateway) |
| REST-0003 | Datos personales mínimos | — | No se lee ni se guarda ningún dato personal | Revisión del diff |

## 12. Seguridad (R6)

**ASVS 5.0.0 nivel 1, matriz de `cameia-infra`:**

| ID | Requisito | Aplica | Cómo lo cumple | Prueba |
|---|---|---|---|---|
| 7.4.1 | Invalidar la sesión al cerrar sesión | Sí | `revokeRefreshTokens` + `verifyIdToken(token, true)` | `revokedToken_returns401WithoutReachingDownstream`; Postman G-05/G-06; staging |
| 7.4.2 | Terminar sesiones al deshabilitar o eliminar la cuenta | Sí | `USER_DISABLED` y `USER_NOT_FOUND` → 401 | `disabledUser_…`, `deletedUser_…`; Postman G-08/G-09 |
| 7.2.1 | Verificación del token en el servidor | Sí | Sin cambio; ahora con revocación | Pruebas existentes |
| 8.2.1 | Acceso por función restringido | Sí | La ruta nueva es Caso A | `logoutAllRoute_withoutToken_returns401WithoutReachingDownstream` |
| 8.2.2 | Anti-IDOR | Sí | Solo `X-User-Id` del Gateway | Sección 8 |
| 4.1.1 | `Content-Type` con charset | Sí | 401, 403 y 503 del filtro con `charset=UTF-8`; 204 sin cuerpo no lleva `Content-Type` | Aserciones de `Content-Type` en cada prueba del filtro |
| 1.2.4 | Consultas parametrizadas | No | No hay consultas | — |
| 15.3.1 | Solo los campos necesarios | Sí | 204 sin cuerpo | `logoutAll_shouldReturn204WithoutBody_whenIdentityPresent` |

**OWASP API Security Top 10, por endpoint (`POST /api/v1/auth/logout-all`):**

| Riesgo | Comprobación | Prueba |
|---|---|---|
| API1 BOLA | Solo el usuario de `X-User-Id` | `…HeaderUserOnly…` |
| API2 Autenticación | Caso A; tokens revocados, vencidos y de cuentas deshabilitadas o borradas → 401 | Sección 10 |
| API3 Propiedades | No acepta cuerpo | `…IgnoreBody…` |
| API4 Recursos | Sin cuerpo que procesar; tiempos de espera del Admin SDK | `firebaseOptions_useConnectAndReadTimeouts` |
| API5 Función | Cualquier usuario autenticado y verificado puede cerrar **sus** sesiones | Sección 10 |
| API6 Flujos sensibles | Idempotente; sin efectos al repetir | FIA-02 |
| API7 SSRF | Ninguna URL sale de la entrada | No aplica |
| API8 Configuración | El Gateway no arranca sin permiso; emulador prohibido en despliegue (existente) | `check_shouldFailStartup_whenPermissionDenied` |
| API9 Inventario | Ruta bajo `/api/v1/` documentada en OpenAPI y en el `CLAUDE.md` del Gateway | Newman L-10 |
| API10 Consumo inseguro | Se usan solo los códigos tipados del SDK; nunca su mensaje | Pruebas del adaptador y del filtro |

**Pruebas negativas de SEG-02:** token inválido (`invalidToken_returns401AndUtf8Charset`, con `INVALID_ID_TOKEN`), recurso ajeno con
un segundo usuario (Cuentas: `uid-propio` y `uid-ajeno`, solo se revoca el propio), identidad en el cuerpo ignorada (L-06).

## 13. Pruebas, cobertura y Postman (R9 a R11)

**Clases a medir (JaCoCo, ≥ 90 % de líneas y de ramas):**

- Cuentas: `RevokeAllSessionsService`, `SessionRevocationController`, `FirebaseUserDirectoryAdapter` (método `revokeSessions`).
- Gateway: `FirebaseAuthGlobalFilter`, `FirebaseRevocationPermissionCheck`, `FirebaseConfig`.

**Comandos:** `./mvnw.cmd clean verify` en cada repo (genera `target/site/jacoco/jacoco.csv`); Newman según el plan de cada repo.

**Postman.** Cuentas: carpeta nueva «Cerrar sesión en todos los dispositivos» (L-00 a L-10). Gateway: carpeta nueva «Revocación de
sesiones (local)» (G-00 a G-10) contra Gateway + Cuentas + emulador. Detalle en las tarjetas.

## 14. Dependencias y acciones para otras personas

| # | Acción | Para | Bloquea | Mientras no exista |
|---|---|---|---|---|
| A-1 | `roles/firebaseauth.viewer` a `cameia-gateway-run@cameia-e245f.iam.gserviceaccount.com` | **Hecho** por Paula Andrea Muñoz Delgado el 13-oct-2026, comprobado con `gcloud projects get-iam-policy`; se avisa a DevOps. Los roles de las cuentas de servicio no están en Terraform (tampoco el de Cuentas) | — | — |
| A-2 | Llamar a `POST /api/v1/auth/logout-all` antes de `signOut`; tratar el 503 de Cuentas (`DEPENDENCY_UNAVAILABLE`) y del Gateway (`AUTH_UNAVAILABLE`) como «inténtalo de nuevo» sin cerrar la sesión local de los demás | Frontend (CM-196), con Vela en copia | Nada del Backend | — |
| A-3 | Actualizar 7.4.1 y 7.4.2 de la matriz ASVS tras el despliegue | DevOps, vía Vela | Nada | — |
| A-4 | Cuenta de staging deshabilitada y eliminada para CA-1.8.10 y CA-1.8.11 | DevOps prepara, Testing ejecuta | La prueba de sistema | Postman local G-08 y G-09 |
| A-5 | Estimación honesta: Cuentas ≈ 4 h, Gateway ≈ 6 h con la prueba en staging (el backlog da 2 h a HU-1.8) | Vela, informado | Nada | — |

Las A-1 a A-5 se redactan en un solo documento por rol en `comunicaciones/` tras la aprobación de esta spec.

## 15. Lista de verificación de la CM

Quien ejecuta la corre completa antes de entregar; quien verifica la repite como piso.

| # | Qué | Cómo | Resultado esperado |
|---|---|---|---|
| V-01 | CA-1.8.1 (parte B) | `SessionRevocationEndToEndTest`, Postman L-01 y G-05 | 204 sin cuerpo |
| V-02 | CA-1.8.2 | `revokeAll_shouldRevokeCallerSessions_whenIdentityValid`; G-03 sin token | Revoca el `uid` del token; sin token 401 |
| V-03 | CA-1.8.9 | `revokedToken_returns401WithoutReachingDownstream`; G-06 | 401 `AUTH_REQUIRED`, Cuentas sin peticiones nuevas |
| V-04 | CA-1.8.10 | `disabledUser_returns401WithoutReachingDownstream`; G-08 | 401 |
| V-05 | CA-1.8.11 | `deletedUser_returns401WithoutReachingDownstream`; G-09 | 401 |
| V-06 | CA-1.8.12 (regresión) | `validToken_isVerifiedWithRevocationCheck`; G-02 y G-07; staging tras desplegar: `GET /api/v1/users/me` y `GET /api/v1/profiles/me` (ruta de Perfil, donde se vio el fallo de septiembre) con una sesión vigente | 200; ningún 401 nuevo en `gcloud logging read` del Gateway en los 30 min siguientes |
| V-07 | Causa de septiembre | `revocationLookupFailure_returns503AuthUnavailable` con `PERMISSION_DENIED` | Falla primero con 401 (antes) y pasa con 503 (después) |
| V-08 | Arranque sin permiso | `check_shouldFailStartup_whenPermissionDenied` | `IllegalStateException` con `roles/firebaseauth.viewer` |
| V-09 | R2 | Pruebas de la sección 8 | Todas en verde |
| V-10 | R3 | Ningún fallo previsible termina en 500, salvo REQ-291-07 (documentado) y REQ-291-16 | Pruebas de la sección 10 |
| V-11 | R4 | `git diff origin/develop --stat -- src/main/resources/db` vacío | Sin migración |
| V-12 | R6 | Secciones 12 y adversariales (abuso: 20 repeticiones; recurso ajeno; concurrencia; datos hostiles; falla a medias) | En verde |
| V-13 | R7 | `LayeredArchitectureTest`, `GatewayArchTest`, `UntypedExceptionClassificationTest` | En verde |
| V-14 | R8 | OpenAPI con 204/400/405/500/503; `docs/errores.md` de los dos repos; ADR 0002 del Gateway; `CLAUDE.md` de los dos | Revisado contra la spec |
| V-15 | R9 | `./mvnw.cmd clean verify` en los dos repos | `BUILD SUCCESS`, 0 fallos |
| V-16 | R10 | JaCoCo de las clases de la sección 13 | ≥ 90 % líneas y ramas |
| V-17 | R11 | Newman de las dos colecciones | 0 fallos |
| V-18 | R12 | Cada CA de B o F y B de HU-1.8 contra su requisito (sección 6), su prueba y su petición (secciones 8, 10 y 15) | Cada fila con evidencia |
| V-19 | R13 | Sección 11 con resultados reales | Ningún atributo sin medición |
| V-20 | R14 | Sin `[IA-ASISTIDO]` en commits, sin `CM-291` en comentarios de código, sin secretos | `git log` y `git grep -n "CM-291" -- src` vacío |

## 16. Puerta de listo

| Parte | Estado | Falta |
|---|---|---|
| Cuentas | **EJECUTADA** tras la aprobación de Paula (13-oct): no depende de P-1 a P-3 | Verificación final y revisión de código |
| Gateway | **EJECUTADA** tras la aprobación de Paula (13-oct) | P-1 a P-3 decididas (A). A-1 hecho el 13-oct (rol comprobado en la política IAM) |

## 17. Línea base

| Repo | Comando | Resultado (13-oct) |
|---|---|---|
| Gateway | `./mvnw.cmd -q -B clean verify` sobre `1f7b8ee` (copia aparte) | **PASA**: 130 pruebas, 0 fallos, 0 errores, salida 0 |
| Cuentas | `./mvnw.cmd clean verify` sobre `5170d2f` | **PASA** (tarjeta C-0): la salida está en `ejecucion/evidencia/CM-291/base-cuentas.log` |

## 18. Hechos, inferencias y decisiones

Cada fila de las secciones 3 y 7 lleva su tipo. Resumen: hechos verificados (secciones 2, 3.1 a 3.5, 5, H-1, H-7, H-8, 17); inferencias
justificadas (causa raíz, H-3, H-5, H-6); decisiones humanas (H-2 y P-1 a P-3 decididas por Paula).
