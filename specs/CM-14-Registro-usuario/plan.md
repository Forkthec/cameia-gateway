# Plan — CM-14-Registro-usuario: diseño técnico en el Gateway

- **Spec de referencia:** `specs/CM-14-Registro-usuario/spec.md`
- **Fecha:** 15/09/2026 · **Actualizado:** 15/09/2026, tras cerrar `GW-TBD-15`, `-16`, `-18`, `-19` y `-20`
- **Flujo SDD:** Fase 3 — Plan técnico
- **Estado:** listo para los Bloques 1 y 2. `GW-TBD-22` (CM-14, sprint 1) y `GW-TBD-23` (método + ruta
  exactos) cerrados el 15/09/2026

---

## 1. Principios que no cambian

- Abrir una ruta exige recompilar (`AGENTS.md` §5). **Ninguna lista pública vive en YAML**: Spring
  permite sobrescribir cualquier propiedad enlazada con una variable de entorno (relaxed binding), y
  eso violaría `REQ-REG-02`.
- Sin clases nuevas fuera del filtro: el emparejamiento se resuelve con un `record` privado anidado
  dentro de `FirebaseAuthGlobalFilter` (`REQ-NF-REG-02`).
- `filter` no importa `config` (ArchUnit `filterDoesNotImportConfig`).

---

## 2. Rutas públicas por método y ruta (`REQ-REG-03`, `REQ-REG-06`)

Hoy:

```java
private static final Set<String> PUBLIC_PATHS = Set.of("/webhooks/wompi");
...
return PUBLIC_PATHS.contains(path);
```

Propuesto:

```java
/** Entrada pública: método y ruta exactos. */
private record PublicRoute(HttpMethod method, String path) { }

private static final Set<PublicRoute> PUBLIC_ROUTES = Set.of(
        new PublicRoute(HttpMethod.POST, "/webhooks/wompi"),
        new PublicRoute(HttpMethod.POST, "/api/v1/users")          // Bloque 2
);

private static final Set<PublicRoute> DEV_PUBLIC_ROUTES = Set.of(
        new PublicRoute(HttpMethod.GET, "/api/v1/users/health"),
        new PublicRoute(HttpMethod.GET, "/api/v1/profiles/health"),
        new PublicRoute(HttpMethod.GET, "/api/v1/interviews/health"),
        new PublicRoute(HttpMethod.GET, "/api/v1/voice-service/health"),
        new PublicRoute(HttpMethod.GET, "/api/v1/audit/health")
);
```

- `record` da `equals`/`hashCode` por valor, así que `Set.contains(new PublicRoute(method, path))`
  sigue siendo una comparación exacta sin comodines.
- Pasar `/webhooks/wompi` a `POST` no cambia nada observable: su ruta YAML ya exige `Method=POST`. La
  prueba `wompiWebhook_withoutToken_reachesDownstreamWithoutIdentityHeaders` debe seguir en verde
  **sin tocarla**.
- Las rutas de la lista de desarrollo salen de la convención decidida en `GW-TBD-20`. Cada una cae
  dentro de la ruta YAML de su microservicio, así que no hace falta tocar `application.yml`.

---

## 3. Activación de la lista de desarrollo (`REQ-REG-01`, `REQ-REG-02`)

### 3.1 Cómo sabe el filtro que está en desarrollo

El filtro recibe `org.springframework.core.env.Environment` por constructor (no es del paquete
`config`) y calcula una sola vez:

```java
this.devRoutesEnabled = environment.matchesProfiles("local");
```

### 3.2 Fin del perfil por defecto (`GW-TBD-19`)

- `application.yml` pasa a `spring.profiles.active: ${SPRING_PROFILES_ACTIVE:}`.
- Quien necesita `local` ya lo declara: `docker-compose.yml` (`SPRING_PROFILES_ACTIVE: local`),
  `.env` y, a través de él, `.vscode/launch.json`. **Verificar los tres antes del cambio** y avisar en
  el PR: quien arranque desde el IDE sin `envFile` pierde los overrides de `application-local.yml`.
- `application-test.yml` no se ve afectado: las pruebas declaran `test` explícitamente.
- **Verificar al implementar** que `${SPRING_PROFILES_ACTIVE:}` con valor vacío deja el contexto sin
  perfiles activos y no falla el arranque. Si falla, eliminar la línea `spring.profiles.active` del
  YAML en vez de dejarla vacía.

### 3.3 Guardia de arranque

- Cloud Run inyecta `K_SERVICE` en todo contenedor de servicio: *"The name of the Cloud Run service
  being run"* ([Container runtime contract](https://docs.cloud.google.com/run/docs/container-contract),
  verificado el 15/09/2026).
- Si `devRoutesEnabled` es verdadero y `environment.getProperty("K_SERVICE")` no es nulo, el
  constructor del filtro lanza `IllegalStateException` con un mensaje en español. El contexto no arranca.
- `Environment` resuelve variables de entorno y propiedades con el mismo nombre, lo que permite probar
  la guardia pasando `K_SERVICE` como propiedad.
- Cuando exista el perfil `prod` de `specs/CM-104-correcciones-OIDC/`, la guardia también falla si
  `local` y `prod` están activos a la vez.

---

## 4. Registro (`REQ-REG-05` a `REQ-REG-10`)

| Punto | Diseño |
|---|---|
| Ruta YAML | **Sin cambios obligatorios.** `Path=/api/v1/users/**` ya coincide con `/api/v1/users` (en `PathPattern`, `/**` admite cero segmentos). Se demuestra con prueba. Sin ruta propia para el registro (`GW-TBD-23`) |
| Entrada pública | `new PublicRoute(HttpMethod.POST, "/api/v1/users")` en la lista base |
| `Authorization` del cliente | Revisar `withoutIdentity`: `AGENTS.md` §4 dice que en Caso B solo borra `X-User-*`. Si no elimina `Authorization`, agregar `headers.remove(AUTHORIZATION)` ahí mismo, lo que también protege `/webhooks/wompi` |
| Cuerpos en log | El wiretap de cliente y servidor queda sin declarar (default `false`). **Verificar el nombre exacto de ambas propiedades en Spring Cloud Gateway 5.0.3**: la documentación de filtros de cabeceras confirma el prefijo `spring.cloud.gateway.server.webflux.`, no el nombre de las de wiretap. Una prueba lee los YAML versionados y falla si aparecen en `true` |
| `X-Forwarded-*` | Sin cambios: no se configura `spring.cloud.gateway.server.webflux.trusted-proxies`, así que el Gateway no genera esas cabeceras (spec C-4) |
| Errores de Cuentas | `GlobalErrorHandler` es un `WebExceptionHandler`: solo actúa ante excepciones, y una respuesta `409`/`422` del destino no lo es. Se demuestra con prueba contra `MockWebServer` |
| OIDC | Nada que hacer aquí. La prueba de contrato de `REQ-OIDC-01` agrega esta ruta |

---

## 5. Pruebas

Todas con `MockWebServer` y `FirebaseAuth` simulado.

| # | Prueba | Perfil | Requisito |
|---|---|---|---|
| 1 | `devHealth_withLocalProfile_reachesDownstreamWithoutToken` (parametrizada con las cinco rutas) | `test,local` (clase propia) | REQ-REG-01, REQ-REG-04 |
| 2 | `devHealth_withoutLocalProfile_returns401` | `test` | REQ-REG-02 |
| 3 | `devHealth_postMethod_returns401` | `test,local` | REQ-REG-03 |
| 4 | `devHealth_trailingSlashOrSubpath_returns401` | `test,local` | REQ-REG-03 |
| 5 | `devRoutes_onCloudRun_failsStartup` (`SpringApplication.run` en `assertThatThrownBy`, con `K_SERVICE` como propiedad) | `local` | REQ-REG-02 |
| 6 | `registration_withoutToken_reachesAccountsWithoutClientCredentials` | `test` | REQ-REG-05, REQ-REG-07 |
| 7 | `usersRoot_nonPostWithoutToken_returns401` (parametrizada GET/PUT/PATCH/DELETE) | `test` | REQ-REG-06 |
| 8 | `registration_downstreamConflict_isReturnedUnchanged` (409 y 422, estado y cuerpo) | `test` | REQ-REG-09 |
| 9 | `versionedYaml_doesNotEnableWiretap` (lee los YAML del classpath, sin contexto) | — | REQ-REG-08 |

---

## 6. Documentación a actualizar

- `AGENTS.md` §4: la descripción del filtro menciona las dos listas y el emparejamiento por método.
- `AGENTS.md` §5 y §6.2: la ruta de registro pasa a activa; se documenta la lista de desarrollo.
- `AGENTS.md` §8: el número de pruebas sube.
- `AGENTS.md` §9: `SPRING_PROFILES_ACTIVE` deja de tener `local` por defecto.
- `docs/COMO-FUNCIONA.md`: los diagramas de `PUBLIC_PATHS` pasan a método + ruta.

---

## 7. Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| Quitar el default `local` rompe el arranque de alguien desde el IDE | Media | Avisar en el PR; `launch.json` con `envFile` ya lo cubre |
| `${SPRING_PROFILES_ACTIVE:}` vacío se comporta distinto de lo esperado | Baja | Verificación explícita en T-07 (§3.2) |
| Entrevista no alinea su health y el chequeo local responde `404` | Media | Aviso a Entrevista (spec C-8). No afecta a la suite |
| El registro se despliega sin Cloud Armor delante | Media | Aviso a DevOps (spec C-4); casilla en el DoD |
| Un health de desarrollo expone información interna | Baja | Los health existentes devuelven solo `{"status":"UP"}`; se revisa cada uno al alinearlo |
