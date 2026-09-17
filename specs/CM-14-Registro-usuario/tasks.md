# Tasks — CM-14-Registro-usuario

- **Spec de referencia:** `specs/CM-14-Registro-usuario/spec.md`
- **Plan de referencia:** `specs/CM-14-Registro-usuario/plan.md`
- **Fecha:** 15/09/2026 · **Actualizado:** 15/09/2026
- **Flujo SDD:** Fase 4 — Checkboxes de implementación
- **Rama:** `CM-14-registro-usuario` → `develop`
- **Sprint:** 1

## Cómo usar esta lista

- Cada tarea está pensada para **30 minutos o menos**. Si una te toma más, para y pregunta.
- Marca `[x]` **antes** de empezar la siguiente. Una tarea a la vez.
- Al final de cada bloque corre `docker compose run --rm verify`. **No pases al siguiente con la
  suite en rojo.**
- Entre paréntesis, los requisitos del spec que la tarea satisface.
- ⛔ = bloqueada por una clarificación del spec §5.

### Orden

**El Bloque 1 es la prioridad.** Ni el Bloque 1 ni el Bloque 2 tienen bloqueos: todas las
decisiones del Gateway están cerradas.

---

## Bloque 0 — Clarificaciones y avisos (no es código)

> No bloquea el Bloque 1. Las decisiones cerradas quedan con fecha; los avisos y las decisiones de
> otros equipos se marcan cuando haya respuesta escrita, no cuando se envíe el mensaje.

### Decisiones del Gateway

- [x] T-00a · Cerrar `GW-TBD-15`: Cuentas crea el usuario con Admin SDK; `POST /api/v1/users` es Caso B — Juan Vela, 15/09/2026 (spec C-1)
- [x] T-00b · Cerrar `GW-TBD-16`: verificación y recuperación las envía Firebase, disparadas desde el frontend; sin SMTP propio — Juan Vela, 15/09/2026 (spec C-6)
- [x] T-00c · Cerrar `GW-TBD-18` para el Gateway: rate limit en GCP y sin propagar IP — Juan Vela, 15/09/2026 (spec C-4)
- [x] T-00d · Cerrar `GW-TBD-19`: sin perfil `local` por defecto + guardia con `K_SERVICE` — Juan Vela, 15/09/2026 (spec C-7)
- [x] T-00e · Cerrar `GW-TBD-20`: convención `/api/v1/<prefijo>/health` en todos los microservicios — Juan Vela, 15/09/2026 (spec C-8)
- [x] T-00f · Cerrar `GW-TBD-23`: método + ruta exactos en Java, sin prefijo público ni ruta YAML propia. La tarea T-13 se omite — Juan Vela, 15/09/2026 (spec C-10)
- [x] T-00g · Registrar el ID confirmado de la HU y el sprint en la cabecera del spec (`GW-TBD-22`): CM-14, sprint 1. Sin renombrar carpeta ni rama — Juan Vela, 15/09/2026

### Verificaciones hechas

- [x] T-00h · Verificar si el límite de 100 cuentas/hora por IP aplica al Admin SDK: no aplica (*"without any throttling or rate limiting"*), y `createUser` no admite IP — 15/09/2026 ([Manage users (Admin)](https://firebase.google.com/docs/auth/admin/manage-users))
- [x] T-00i · Verificar que Cloud Run inyecta `K_SERVICE` en el contenedor — 15/09/2026 ([Container runtime contract](https://docs.cloud.google.com/run/docs/container-contract))
- [x] T-00j · Verificar que el Admin SDK no envía correos y que `sendEmailVerification` exige sesión — 15/09/2026 ([Email action links](https://firebase.google.com/docs/auth/admin/email-action-links), [Manage users (web)](https://firebase.google.com/docs/auth/web/manage-users))

### Decisiones de otros equipos (no bloquean el Gateway)

- [ ] T-00k · Definir la ruta exacta de verificación de correo, la única permitida con `email_verified=false` (`GW-TBD-17`). **Política ya decidida el 15/09/2026:** sin correo verificado no se puede hacer nada más. Queda para una sesión futura; al cerrarse entra como requisito nuevo del Gateway con su código de error
- [x] T-00l · Definir qué responde el registro ante un correo ya registrado: `409` con mensaje de usuario existente — Juan Vela, 15/09/2026 (`GW-TBD-21`, spec C-5)

### Avisos a enviar

- [ ] T-00m · **DevOps:** el rate limit con Cloud Armor necesita un Application Load Balancer delante del Gateway (el Domain Mapping previsto para `api.cameia.app` puede no admitirlo), ingress restringido al balanceador y clave del límite por IP de conexión, no `XFF_IP` (spec C-14)
- [ ] T-00n · **Entrevista:** su health debe pasar de `GET /health` a `GET /api/v1/interviews/health`; hasta entonces responde `404` a través del Gateway (spec C-8)
- [ ] T-00o · **Cuentas:** el registro llega sin `X-User-*` y debe quedar exento del contrato de entrada del Gateway, como el health; definir cómo `correo_verificado` pasa a `true`; valorar borrar cuentas sin verificar tras N días (spec C-6)
- [ ] T-00p · **Frontend:** tras el `201`, iniciar sesión y llamar a `sendEmailVerification`; ofrecer "reenviar correo"; la recuperación usa `sendPasswordResetEmail`. En plan Spark solo hay 150 correos de recuperación al día (spec C-6)
- [ ] T-00q · **Quien mantiene la HU:** la especificación técnica dice `createUserWithEmailAndPassword`, que corresponde al modelo descartado; corregirla (spec C-1)

---

## Bloque 1 — PRIORIDAD · Health v1 público solo en desarrollo

- [x] T-01 · Quitar `"/api/v1/users/health"` de `PUBLIC_PATHS` (cambio sin commit en la copia de trabajo, sin spec). Correr la suite: debe seguir en verde (spec C-9) — 16/09/2026: la entrada ya no estaba en `PUBLIC_PATHS` ni había cambios sin commit; nada que quitar
- [x] T-02 · Convertir `PUBLIC_PATHS` en `Set<PublicRoute>` con el `record` privado `PublicRoute(HttpMethod, String)` y `POST /webhooks/wompi`. **No tocar** la prueba de wompi: tiene que seguir en verde tal cual (REQ-REG-06, plan §2) — 16/09/2026
- [x] T-03 · Crear `DEV_PUBLIC_ROUTES` con las cinco rutas `GET /api/v1/<prefijo>/health` del plan §2. Solo versión 1 (REQ-REG-01, REQ-REG-03) — 16/09/2026
- [x] T-04 · Inyectar `Environment` en el constructor del filtro y calcular `devRoutesEnabled` con `matchesProfiles("local")`. La comprobación de ruta pública consulta la lista de desarrollo solo si ese valor es verdadero (REQ-REG-01, REQ-REG-02, plan §3.1) — 16/09/2026
- [x] T-05 · Guardia en el constructor: si `devRoutesEnabled` y `K_SERVICE` está definida, lanzar `IllegalStateException` con mensaje en español (REQ-REG-02, plan §3.3) — 16/09/2026
- [x] T-06 · Confirmar que `docker-compose.yml`, `.env.example` y `.vscode/launch.json` activan `local` (REQ-REG-02, plan §3.2) — 16/09/2026: `docker-compose.yml` y `.env.example` declaran `local`. **`.vscode/launch.json` no existe** (solo `settings.json`)
- [x] T-07 · Cambiar `application.yml` a `${SPRING_PROFILES_ACTIVE:}` y verificar que el contexto arranca sin perfiles cuando la variable no existe. Si no arranca, eliminar la línea en vez de dejarla vacía (REQ-REG-02, plan §3.2) — 16/09/2026: se deja `${SPRING_PROFILES_ACTIVE:}`. Comprobado en un contenedor sin la variable: `No active profile set, falling back to 1 default profile: "default"` y `GatewayStartupTest` 3/3 en verde
- [x] T-08 · Pruebas 1 y 4 del plan en una clase con perfiles `test,local`: las cinco rutas de health llegan sin token y sin `X-User-*`; `/health/` y `/health/x` responden `401` (REQ-REG-01, REQ-REG-03, REQ-REG-04) — 16/09/2026: `DevHealthRoutesTest`
- [x] T-09 · Pruebas 2 y 3 del plan: sin `local` el health responde `401` y `MockWebServer` no recibe nada; `POST` al health responde `401` con `local` (REQ-REG-02, REQ-REG-03) — 16/09/2026: `devHealth_withoutLocalProfile_returns401` y `DevHealthRoutesTest.devHealth_postMethod_returns401`
- [x] T-10 · Prueba 5 del plan: con `local` y `K_SERVICE` como propiedad, el contexto no arranca (REQ-REG-02) — 16/09/2026: `GatewayStartupTest.devRoutes_onCloudRun_failsStartup`, con Firebase simulado para que la guardia sea la única causa posible
- [x] T-11 · Actualizar `AGENTS.md` §4, §5, §6.2 y §9, y `docs/COMO-FUNCIONA.md`: método + ruta, lista de desarrollo, fin del perfil por defecto (plan §6) — 16/09/2026

**Cierre de bloque:** `docker compose run --rm verify` en verde, con las pruebas nuevas contadas. ✅ 16/09/2026 — Bloques 1 y 2 cerrados juntos: `Tests run: 47, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

---

## Bloque 2 — Registro de usuario

> `GW-TBD-23` cerrado: método + ruta exactos, sin ruta YAML propia.

- [x] T-12 · Agregar `new PublicRoute(HttpMethod.POST, "/api/v1/users")` a la lista base (REQ-REG-05) — 16/09/2026
- [x] T-13 · ~~Declarar la ruta YAML `cameia-cuentas-registration`~~ — **omitida**: `GW-TBD-23` decidió no crear ruta propia; `Path=/api/v1/users/**` ya cubre `/api/v1/users` (plan §4)
- [x] T-14 · Revisar `withoutIdentity`. Si no elimina `Authorization`, agregar `headers.remove(HttpHeaders.AUTHORIZATION)` (REQ-REG-07, plan §4) — 16/09/2026: no lo eliminaba; se agregó
- [x] T-15 · Prueba 6: `POST /api/v1/users` sin token llega a Cuentas; `takeRequest()` demuestra que no llegan `X-User-Id` ni `Authorization` enviados por el cliente (REQ-REG-05, REQ-REG-07) — 16/09/2026
- [x] T-16 · Prueba 7 parametrizada: `GET`, `PUT`, `PATCH`, `DELETE /api/v1/users` sin token → `401`, y `getRequestCount()` sin cambios (REQ-REG-06) — 16/09/2026
- [x] T-17 · Prueba 8: `MockWebServer` responde `409` y `422` con cuerpo JSON; el cliente recibe el mismo estado y cuerpo, no el catálogo del Gateway (REQ-REG-09) — 16/09/2026: `registration_downstreamError_isReturnedUnchanged`
- [x] T-18 · Verificar en Spring Cloud Gateway 5.0.3 el nombre exacto de las propiedades de wiretap. Prueba 9: leer `application*.yml` del classpath y fallar si alguna las activa (REQ-REG-08, plan §4) — 16/09/2026: `spring.cloud.gateway.server.webflux.httpclient.wiretap` y `...httpserver.wiretap`, ambas `false` por defecto según `spring-configuration-metadata.json` del jar 5.0.3. Prueba `VersionedYamlTest.versionedYaml_doesNotEnableWiretap`
- [x] T-19 · Pasar la fila de registro en `AGENTS.md` §5 a activa — 16/09/2026

**Cierre de bloque:** `docker compose run --rm verify` en verde. `wompiWebhook_withoutToken...` sin cambios. ✅ 16/09/2026 — misma ejecución; la prueba de wompi no se tocó.

---

## Bloque 3 — Dependencia con OIDC

- [x] T-20 · Cuando se implemente `specs/CM-104-correcciones-OIDC/`, incluir `POST /api/v1/users` en la prueba de contrato de `REQ-OIDC-01` (REQ-REG-10) — 16/09/2026, en la rama `CM-104-correcciones-OIDC`: `OidcSigningFilterTest.registration_isSignedLikeAnyOtherRoute`. También la guardia pendiente del plan §3.3: `local` y `prod` no arrancan juntos (`devRoutes_withProdProfile_failsStartup`)

---

## Bloque 4 — Cierre

- [x] T-21 · Revisar `git diff develop...HEAD`: ningún secreto, ninguna lista pública en YAML, ningún comando presentado como ejecutado sin haberlo hecho — 16/09/2026: sin secretos (el único `password` es un valor falso de prueba), ninguna lista pública en YAML
- [x] T-22 · Recorrer el DoD del spec §7 con evidencia real (salida de comando o nombre de prueba) — 17/09/2026: DoD del spec §7 marcado con evidencia; quedan sin marcar los avisos (T-00m a T-00q) y el título del PR hasta abrirlo
- [x] T-23 · Rellenar la bitácora de IA del mismo día (`AGENTS.md` §10) — 16/09/2026: `Entregables/16092026_BitacoraIA_Codigo_E2.md`, pendiente de revisión humana
- [ ] T-24 · Abrir el PR **solo con autorización expresa** (`AGENTS.md` §0, punto 1), con el título del spec §7 y el ID confirmado · *17/09/2026: autorizado por Juan Vela; incluye OIDC. Descripción lista en `Entregables/17092026_PR_CM-14-registro-usuario.md`. Se marca cuando el PR exista*

---

## Trazabilidad requisito → tarea

| Requisito | Tareas |
|---|---|
| `REQ-REG-01` | T-03, T-04, T-08 |
| `REQ-REG-02` | T-04, T-05, T-06, T-07, T-09, T-10 |
| `REQ-REG-03` | T-03, T-08, T-09 |
| `REQ-REG-04` | T-08 |
| `REQ-REG-05` | T-12, T-15 |
| `REQ-REG-06` | T-02, T-16 |
| `REQ-REG-07` | T-14, T-15 |
| `REQ-REG-08` | T-18 |
| `REQ-REG-09` | T-17 |
| `REQ-REG-10` | T-20 |
| `REQ-NF-REG-01` | T-08 a T-10, T-15 a T-18 |
| `REQ-NF-REG-02` | T-02, T-04 |
