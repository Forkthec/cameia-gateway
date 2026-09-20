# Plan — CM-190-emulador-firebase-auth

- **Spec de referencia:** `specs/CM-190-emulador-firebase-auth/spec.md`
- **Fecha:** 19/09/2026
- **Flujo SDD:** Fase 3 — Diseño técnico

---

## 1. Cambios en `FirebaseConfig` (`REQ-EMU-01` a `REQ-EMU-03`)

Es la única clase de producción que cambia. No se crea ninguna clase nueva.

- **Inyección por constructor** (`AGENTS.md` §7): `FirebaseConfig(@Value("${FIREBASE_PROJECT_ID}") String, Environment)`,
  con ambos campos `private final`. Hoy el ID de proyecto entra por un campo con `@Value`. Sin
  `FIREBASE_PROJECT_ID` el contexto sigue sin arrancar (`missingFirebaseProjectId_startupFails`).
- **`Environment` y no `System.getenv`:** la variable se lee del `Environment` de Spring, que incluye
  las variables de entorno del sistema. Así las pruebas pueden fijarla sin tocar el entorno del
  proceso, igual que `FirebaseAuthGlobalFilter` lee `K_SERVICE`.
- **`init()`** (máximo 20 líneas): llama primero a `rejectEmulatorInDeployment()`, y solo si
  `FirebaseApp` aún no existe construye las opciones con `resolveCredentials()`.
- **`rejectEmulatorInDeployment()`:** si `environment.containsProperty("FIREBASE_AUTH_EMULATOR_HOST")`
  y (`K_SERVICE` presente o perfil `prod` activo), lanza `IllegalStateException` con un mensaje que
  nombra la variable. `containsProperty` es verdadero aunque el valor esté vacío (`REQ-EMU-02`).
- **`resolveCredentials()`:** si la variable tiene un valor no vacío devuelve las credenciales
  ficticias; si no, `defaultCredentials()`.
- **`defaultCredentials()`:** una sola línea, `GoogleCredentials.getApplicationDefault()`. Existe como
  método aparte para que una prueba pueda sustituirla y comprobar `REQ-EMU-03` sin depender de que el
  equipo que corre la prueba tenga o no credenciales de Google.
- **Credenciales ficticias:** `GoogleCredentials.create(new AccessToken("emulador-sin-credenciales", null))`,
  el mismo objeto con el que se verificó el SDK (spec §1.1). El SDK las sustituye por
  `EmulatorCredentials`; el literal no es un secreto.

El criterio de "despliegue" (`K_SERVICE` o perfil `prod`) es el mismo de `FirebaseAuthGlobalFilter`.
Cada guardia queda en su clase porque `AGENTS.md` §3 prohíbe que `filter` importe `config`.

## 2. Compose y ejemplos (`REQ-EMU-04`)

- `docker-compose.yml`, servicio `app`: se añade `FIREBASE_AUTH_EMULATOR_HOST` **sin valor** (formato
  de paso directo): Compose lo copia del entorno o del `.env` solo si existe.
- `.env.example`: el camino por defecto pasa a ser el emulador (`FIREBASE_PROJECT_ID=demo-cameia` y la
  variable apuntando a `cameia-firebase-emulator:9099`); usar un proyecto real queda como alternativa
  explicada.

## 3. Pruebas

| Prueba | Tipo | Cubre |
|---|---|---|
| `FirebaseConfigTest` | Unitaria, sin contexto de Spring (`MockEnvironment`) | `REQ-EMU-01`, `-02`, `-03` |
| `FirebaseEmulatorStartupTest` | Contexto completo con `FirebaseConfig` activo | `REQ-EMU-01`, `-02` |
| Suite existente | Sin modificar | `REQ-NF-EMU-02` |

Casos de `FirebaseConfigTest`: (1) con la variable y sin despliegue, inicializa con las credenciales
ficticias y no consulta las de Google; (2) con la variable y `K_SERVICE`, lanza; (3) con la variable y
el perfil `prod`, lanza; (4) con la variable **vacía** y `K_SERVICE`, lanza; (5) sin la variable usa las
credenciales por defecto; (6) con la variable vacía y sin despliegue, usa las credenciales por defecto.
Cada prueba borra el `FirebaseApp` estático al terminar, porque el SDK lo guarda a nivel de proceso.

Prueba de extremo a extremo, **manual y registrada en el PR** (no forma parte de la suite): emulador
real en Docker, el Gateway en su contenedor con la variable, y un servicio de perfil de mentira que
devuelve las cabeceras que recibe. Se comprueba que un token del emulador atraviesa el Gateway y llega
con `X-User-*` correctas, y que un token basura y la ausencia de token dan `401`.

## 4. Documentación

`AGENTS.md` §4 (fila de `FirebaseConfig`) y §9 (variables), `docs/DOCKER-LOCAL.md` (prerrequisitos y
uso del emulador), `docs/COMO-FUNCIONA.md` (la línea sobre la clave local), `.env.example`, y
`specs/NUMERACIONES.md` (carpeta nueva y `GW-TBD-28`).

## 5. Riesgos

| Riesgo | Mitigación |
|---|---|
| La variable llega a un despliegue y el Gateway acepta tokens sin firma | `REQ-EMU-02`: el arranque falla; probado a nivel de clase y de contexto completo |
| Alguien confunde el emulador con Firebase real y ve un comportamiento distinto | `docs/DOCKER-LOCAL.md` lista las diferencias (tokens sin firma, sin correos, códigos de error de inicio de sesión) |
| La matriz ASVS (`cameia-infra`) dice que no hay fuente de claves configurable | Queda **pendiente de DevOps** actualizar `9.1.1`, `9.1.2` y `9.1.3` con esta guardia, fuera de este repositorio |
