# Tasks — CM-190-emulador-firebase-auth

- **Spec de referencia:** `specs/CM-190-emulador-firebase-auth/spec.md`
- **Plan de referencia:** `specs/CM-190-emulador-firebase-auth/plan.md`
- **Fecha:** 19/09/2026
- **Flujo SDD:** Fase 4 — Checkboxes de implementación
- **Rama:** `CM-190-gateway-arranque-con-emulador-firebase-auth` → `develop`

## Cómo usar esta lista

- Una tarea a la vez; marcar `[x]` antes de la siguiente.
- Al cerrar el bloque de pruebas: `docker compose run --rm verify` en verde.

---

## Bloque 1 — Código

- [ ] T-01 · `FirebaseConfig`: constructor con `FIREBASE_PROJECT_ID` y `Environment`, campos `private final` (plan §1)
- [ ] T-02 · `rejectEmulatorInDeployment()` y su llamada al inicio de `init()` (`REQ-EMU-02`)
- [ ] T-03 · `resolveCredentials()`, `defaultCredentials()` y las credenciales ficticias (`REQ-EMU-01`, `REQ-EMU-03`)

## Bloque 2 — Pruebas

- [ ] T-04 · `FirebaseConfigTest` con los seis casos del plan §3
- [ ] T-05 · `GatewayStartupTest`: contexto completo con la variable y `K_SERVICE` (falla) y con la variable sola (arranca)
- [ ] T-06 · `docker compose run --rm verify` en verde; anotar el número de pruebas antes (79) y después

## Bloque 3 — Compose, ejemplos y documentación

- [ ] T-07 · `docker-compose.yml` (`REQ-EMU-04`) y `.env.example`; comprobar con `docker compose config` con y sin la variable
- [ ] T-08 · `AGENTS.md` §4 y §9; `docs/DOCKER-LOCAL.md`; `docs/COMO-FUNCIONA.md`
- [ ] T-09 · `specs/NUMERACIONES.md`: carpeta nueva, `GW-TBD-28` y siguiente número libre

## Bloque 4 — Extremo a extremo y cierre

- [ ] T-10 · Prueba de extremo a extremo con el emulador real (plan §3); registrar la salida en el PR
- [ ] T-11 · Commit, PR hacia `develop` con `[IA-ASISTIDO]` en el título, comentario de cierre en Jira
