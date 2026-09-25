# Plan — CM-188-correcciones

- **Spec de referencia:** `specs/CM-188-correcciones/spec.md`
- **Fecha:** 24/09/2026
- **Flujo SDD:** Fase 3 — Diseño técnico
- **Decisiones:** `GW-TBD-29` y `GW-TBD-30` cerradas el 24/09/2026 (spec §4)

No se crea ninguna clase de producción nueva (`AGENTS.md` §4).

---

## 1. Problema 1 — `FirebaseAuthGlobalFilter` (`REQ-EMC-01` a `REQ-EMC-03`)

### 1.1 Verificar antes de codificar

Como en CM-190 §1.1, primero se comprueba **qué excepción lanza realmente** `firebase-admin` 9.10.0
cuando el servidor de Auth no responde, con el emulador apagado y con un host inexistente:

- clase de la excepción, su `ErrorCode` / `AuthErrorCode`, y la cadena completa de `getCause()`;
- si el fallo ocurre al pedir las claves (modo real) o en `getUser` (comprobación de revocación).

La hipótesis a confirmar es: `FirebaseAuthException` cuya cadena de causas contiene una
`IOException` (`ConnectException`, `UnknownHostException`, `SocketTimeoutException`). **El diseño
de 1.2 se ajusta al resultado**; no se escribe código con la hipótesis sin confirmar.

**Resultado (24/09/2026, T-01).** Programa Java con `firebase-admin` 9.10.0 y el classpath real del
proyecto, en `maven:3.9-eclipse-temurin-21`, con `verifyIdToken(token, false)` y `(token, true)`:

| Escenario | `ErrorCode` / `AuthErrorCode` | Cadena de causas |
|---|---|---|
| Emulador, host sin DNS (`--network none`) | `UNAVAILABLE` / `null` | `FirebaseAuthException` → `IOException` → `ExecutionException` → `UnknownHostException` |
| Emulador, puerto cerrado (`localhost:1`) | `UNKNOWN` / `null` | `FirebaseAuthException` → `IOException` → `HttpHostConnectException` (subclase de `ConnectException`) |
| Modo real sin red, token con `kid` | `UNKNOWN` / `CERTIFICATE_FETCH_FAILED` | `FirebaseAuthException` → `IOException` → `ExecutionException` → `UnknownHostException` |
| Emulador vivo, usuario inexistente | `NOT_FOUND` / `USER_NOT_FOUND` | Sin causa |
| Modo real, token sin `kid` | `INVALID_ARGUMENT` / `INVALID_ID_TOKEN` | Sin causa |

Hipótesis confirmada: todo fallo de red trae una `java.io.IOException` en la cadena y ningún rechazo
del token la trae. El criterio de 1.2 es "hay una `IOException` en la cadena", no el `ErrorCode`, que
varía (`UNAVAILABLE` o `UNKNOWN`). Además, en modo emulador el SDK consulta al emulador **también con
`checkRevoked=false`**: sin emulador, ningún token se verifica.

### 1.2 Clasificación en el filtro

`isTokenRejection(Throwable)` pasa a devolver `true` solo si el error es `IllegalArgumentException`,
o `FirebaseAuthException` **sin** una `IOException` en su cadena de causas. Se extrae un método
privado `isAuthServerUnreachable(Throwable)` que recorre las causas (mismo patrón que
`GlobalErrorHandler.resolveStatus`, sin importarlo: `filter` no importa `exception`).

- Rechazo del token → `401` como hoy (`REQ-EMC-02`).
- Servidor inalcanzable → el filtro registra `ERROR` con la causa y el `X-Request-Id` ("el servidor
  de Firebase Auth no respondió"), como `failClosed` de `OidcSigningGlobalFilter` (es un fallo del
  servidor, no del cliente), y devuelve `Mono.error(new ResponseStatusException(SERVICE_UNAVAILABLE))`
  **sin reason**, igual que `OidcSigningGlobalFilter` (`REQ-EMC-01`, `REQ-EMC-03`). El
  `GlobalErrorHandler` ya traduce eso a `{"code":"SERVICE_UNAVAILABLE",...}` sin filtrar detalle.
- En ambos casos el `flatMap` no corre y nada llega al downstream.

### 1.3 Compose — sin cambios

`REQ-EMC-04` quedó retirado (`GW-TBD-30` b): el servicio `app` **no** recibe `depends_on` sobre el
emulador, para que el Gateway no lo espere cuando se usa un proyecto real. El arranque simultáneo
queda cubierto por 1.2: mientras el emulador no responde, el Gateway da `503` y lo registra.

## 2. Problema 3 — ID de proyecto (`REQ-EMC-05` a `REQ-EMC-07`)

### 2.1 `FirebaseConfig`

- Nuevo método privado `rejectNonDemoProjectInEmulator()`, llamado en `init()` después de
  `rejectEmulatorInDeployment()`: si el modo emulador está activo (definido en §3) y
  `firebaseProjectId` no empieza por `demo-`, lanza `IllegalStateException` nombrando las dos
  variables (`REQ-EMC-05`).
- El `log.warn` actual de `resolveCredentials()` añade el ID de proyecto (`REQ-EMC-06`).
- `init()` debe seguir en 20 líneas o menos: las guardias van a un método `validateEnvironment()`
  si hace falta.

### 2.2 Emulador

- `docker-compose.yml`, servicio `firebase-emulator`: se pasa también
  `FIREBASE_PROJECT_ID: ${FIREBASE_PROJECT_ID:-}` y `FIREBASE_EMULATOR_PROJECT_ID` pasa a ser **sin
  valor por defecto** (paso directo), para que el entrypoint distinga "no definida".
- `entrypoint.sh`: orden de resolución `FIREBASE_EMULATOR_PROJECT_ID` → `FIREBASE_PROJECT_ID` si
  empieza por `demo-` → `demo-cameia`. La guardia `demo-*` existente se conserva sobre el resultado
  y se imprime el ID elegido (`REQ-EMC-07`).
- `.env.example`: la línea comentada de `FIREBASE_EMULATOR_PROJECT_ID` explica que normalmente no
  hace falta, porque sigue a `FIREBASE_PROJECT_ID`.

## 3. Problema 4 — de dónde se lee la variable (`REQ-EMC-08` a `REQ-EMC-10`)

CM-190 eligió el `Environment` de Spring por comodidad de las pruebas (su plan §1). Se conserva la
capacidad de probar sin tocar el entorno del proceso leyendo la fuente **`systemEnvironment`** del
`ConfigurableEnvironment`, que en ejecución envuelve `System.getenv()`:

```java
private String processVariable(String name)  // mapa en bruto de "systemEnvironment", nombre exacto
```

> **Cambio respecto a la primera versión del plan (24/09/2026).** Se proponía un método sustituible
> que devolviera `System.getenv(name)`. Servía para `FirebaseConfigTest`, pero no para
> `FirebaseEmulatorStartupTest`, que arranca el contexto real: su caso positivo pasa la variable como
> argumento `--`, lo que ahora falla por `REQ-EMC-09`, y no hay forma limpia de fijar una variable de
> entorno en la JVM de la prueba. Con la fuente `systemEnvironment`, la prueba la sustituye con
> `SpringApplication.setEnvironment` y el caso positivo se conserva. Se lee el mapa en bruto, con el
> nombre exacto, porque la búsqueda de Spring es flexible con mayúsculas y separadores y el SDK no.
> El constructor pasa de `Environment` a `ConfigurableEnvironment`, que Spring inyecta igual.

- **Modo emulador** = `processVariable(EMULATOR_HOST_VARIABLE)` con valor no vacío (`REQ-EMC-08`).
  `resolveCredentials()` y `rejectNonDemoProjectInEmulator()` usan este criterio.
- **Guardia de despliegue** (`REQ-EMC-10`): la variable cuenta como presente si
  `environment.containsProperty(...)` **o** `processVariable(...) != null`.
- **Desajuste** (`REQ-EMC-09`): si `environment.getProperty(...)` tiene valor y no es igual a
  `processVariable(...)` (ausente, vacía u otra), `IllegalStateException` explicando que el SDK solo lee
  la variable de entorno del proceso y cómo definirla en el IDE.

Orden en `init()`: despliegue → desajuste → proyecto `demo-` → credenciales.

## 4. Pruebas

| Prueba | Tipo | Casos nuevos |
|---|---|---|
| `FirebaseAuthGlobalFilterTest` | Integración (`WebTestClient` + `MockWebServer`) | `FirebaseAuthException` con `ConnectException` en la causa → `503 SERVICE_UNAVAILABLE`, `MockWebServer` sin peticiones; `FirebaseAuthException` sin causa de red → `401` |
| `FirebaseConfigTest` | Unitaria (`MockEnvironment` + `processVariable` sustituido) | emulador con proyecto `demo-` arranca; emulador con proyecto real falla; variable solo en Spring falla (desajuste); variable solo en el proceso con `K_SERVICE` falla; variable en las dos sin despliegue usa credenciales ficticias; sin variable en ninguna, credenciales por defecto |
| `FirebaseEmulatorStartupTest` | Contexto completo | Ajustar a `processVariable` si sus casos dependían de propiedades de Spring |
| Suite y `GatewayArchTest` | Existentes | Sin cambios, en verde |

Manual, registrada en el PR: `docker compose config` (variables del emulador),
`docker compose up` con el emulador detenido a mano (`503`, no `401`), y el entrypoint con
`FIREBASE_PROJECT_ID=demo-otro` (el emulador arranca con `demo-otro`) y con un ID real (arranca con
`demo-cameia` y el Gateway se niega a arrancar por `REQ-EMC-05`).

## 5. Documentación

`README.md` (Docker como forma recomendada de ejecutar y, fuera de Docker, la variable del emulador
como variable de entorno con `localhost:9099`, `REQ-EMC-11`; hecho el 24/09/2026 al cerrar
`GW-TBD-29`); `AGENTS.md` §4 (filas de `FirebaseAuthGlobalFilter` y `FirebaseConfig`) y §9; `docs/DOCKER-LOCAL.md`
(ID de proyecto único y las dos advertencias del spec §6); `.env.example`; `specs/NUMERACIONES.md`.

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| La causa de red no es una `IOException` en la cadena y el `503` nunca se activa | §1.1 se hace primero; si la forma real es otra, se ajusta el plan antes del código |
| Clasificar como red un rechazo real y abrir la puerta | En ningún caso se reenvía: `503` también corta la petición. El peor caso es un código incorrecto, no un acceso |
| `System.getenv` hace frágiles las pruebas | Todas pasan por `processVariable`, sustituible como `defaultCredentials()` |
| El cambio a `503` en despliegue sorprende a quien monitorea | Se anota en el PR: antes esos fallos se escondían como `401` |
