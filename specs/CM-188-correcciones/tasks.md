# Tasks — CM-188-correcciones

- **Spec de referencia:** `specs/CM-188-correcciones/spec.md`
- **Plan de referencia:** `specs/CM-188-correcciones/plan.md`
- **Fecha:** 24/09/2026
- **Flujo SDD:** Fase 4 — Checkboxes de implementación
- **Rama:** `CM-188-correcciones` → `develop`

## Cómo usar esta lista

- Una tarea a la vez; marcar `[x]` antes de la siguiente.
- Al cerrar cada bloque de código: `docker compose run --rm verify` en verde, más un clean compile.

---

## Bloque 0 — Clarificaciones

- [x] T-00 · Cerrar `GW-TBD-29` y `GW-TBD-30` con el equipo y anotar la decisión en el spec §4 y en `NUMERACIONES.md` — 24/09/2026: `GW-TBD-29` falla el arranque y el README recomienda Docker; `GW-TBD-30` (a) sí, (b) no, `REQ-EMC-04` retirado

## Bloque 1 — Problema 1: emulador caído no es token inválido

- [x] T-01 · Reproducir con `firebase-admin` 9.10.0 el fallo con el emulador apagado y con un host inexistente; anotar clase, código y cadena de causas en el plan §1.1 (`REQ-EMC-01`) — 24/09/2026: cinco escenarios; los tres de red traen `IOException` en la cadena y los dos rechazos no traen causa (plan §1.1)
- [x] T-02 · `FirebaseAuthGlobalFilter`: `isAuthServerUnreachable` y `isTokenRejection` restringido (`REQ-EMC-01`, `REQ-EMC-02`) — 24/09/2026: la verificación pasa a `verifyIdToken(...)` para que `filter` siga en 20 líneas; el criterio es una `IOException` en la cadena de causas
- [x] T-03 · `FirebaseAuthGlobalFilter`: rama de `503` sin reason y log con `X-Request-Id` (`REQ-EMC-01`, `REQ-EMC-03`) — 24/09/2026: `authServerUnavailable` registra en `ERROR` con la causa, no en `WARN`, igual que `failClosed` de `OidcSigningGlobalFilter` (plan §1.2 corregido)
- [x] T-04 · `FirebaseAuthGlobalFilterTest`: causa de red → `503` sin reenvío; sin causa de red → `401` (`REQ-EMC-01`, `REQ-EMC-02`) — 24/09/2026: tres pruebas; suite 90 → 93, `BUILD SUCCESS`. Con la detección desactivada a propósito fallan exactamente las dos de `503`
- [x] ~~T-05 · `docker-compose.yml`: `depends_on` de `app` sobre `firebase-emulator` healthy (`REQ-EMC-04`)~~ — 24/09/2026: retirada junto con `REQ-EMC-04` (`GW-TBD-30` b)

## Bloque 2 — Problema 4: la variable se lee de donde la lee el SDK

- [x] T-06 · `FirebaseConfig`: `processVariable(String)` como punto de sustitución (`REQ-EMC-08`) — 24/09/2026: lee la fuente `systemEnvironment` en bruto y no `System.getenv` directamente, para poder sustituirla también en la prueba de contexto completo (plan §3 corregido); constructor con `ConfigurableEnvironment`
- [x] T-07 · `FirebaseConfig`: modo emulador por `processVariable`; guardia de despliegue con las dos fuentes (`REQ-EMC-08`, `REQ-EMC-10`) — 24/09/2026
- [x] T-08 · `FirebaseConfig`: guardia de desajuste Spring/proceso (`REQ-EMC-09`) — 24/09/2026: `rejectEmulatorOutsideProcessEnvironment()`, que también rechaza un valor distinto (spec `REQ-EMC-09` ampliado)
- [x] T-09 · `FirebaseConfigTest` y `FirebaseEmulatorStartupTest`: casos del plan §4 para `REQ-EMC-08` a `REQ-EMC-10` — 24/09/2026: tres unitarias y una de contexto completo; el caso positivo de contexto usa ahora una variable de entorno simulada. Con la guardia quitada a propósito fallan exactamente las tres de `REQ-EMC-09`

## Bloque 3 — Problema 3: un solo ID de proyecto

- [x] T-10 · `FirebaseConfig`: `rejectNonDemoProjectInEmulator()` y el ID de proyecto en el log de arranque; `init()` en 20 líneas o menos (`REQ-EMC-05`, `REQ-EMC-06`) — 24/09/2026: `isEmulatorMode()` compartido por la guardia y `resolveCredentials()`; `init()` queda en 11 líneas
- [x] T-11 · `FirebaseConfigTest`: proyecto `demo-` arranca; proyecto real con emulador falla (`REQ-EMC-05`) — 24/09/2026: tres pruebas (proyecto real con emulador falla, proyecto real sin emulador arranca, log con el ID). Con la guardia quitada a propósito falla exactamente la primera
- [x] T-12 · `entrypoint.sh` y servicio `firebase-emulator` del compose: resolución del ID de proyecto del plan §2.2 (`REQ-EMC-07`) — 24/09/2026: las dos variables pasan sin valor por defecto; el entrypoint imprime el ID elegido
- [x] T-13 · Comprobación manual del entrypoint con `demo-otro`, con un ID real y sin variables; anotar la salida para el PR (`REQ-EMC-07`) — 24/09/2026: sin variables → `demo-cameia`; `FIREBASE_PROJECT_ID=demo-otro` → `demo-otro`; ID real → `demo-cameia`; `FIREBASE_EMULATOR_PROJECT_ID=demo-forzado` gana; `FIREBASE_EMULATOR_PROJECT_ID` real → `ERROR` y salida 1

## Bloque 4 — Documentación y cierre

- [x] T-14a · `README.md`: Docker como forma recomendada; fuera de Docker, variable de entorno con `localhost:9099` (`REQ-EMC-11`) — 24/09/2026: aviso en "Ejecución local"; el paso 4 pasa de `docker run --env-file .env` (queda fuera de `cameia-net` y no alcanza al emulador) a `docker compose up --build -d`
- [x] T-14 · `.env.example`, `docs/DOCKER-LOCAL.md` (ID único, arranque desde el IDE y las dos advertencias del spec §6) — 24/09/2026: además, en `DOCKER-LOCAL.md`, el `503` con el emulador caído, la Emulator UI (puerto 4000) y la necesidad de volver a iniciar sesión tras reiniciar el emulador (hallazgo de T-17)
- [x] T-15 · `AGENTS.md` §4 y §9 — 24/09/2026: filas de `FirebaseConfig` y `FirebaseAuthGlobalFilter`, variables de §9 y número de pruebas de §8
- [x] T-16 · `docker compose run --rm verify` en verde; anotar el número de pruebas antes y después (`REQ-NF-EMC-01`, `REQ-NF-EMC-02`) — 24/09/2026: `mvn clean verify`, 90 → 100 pruebas (el "89" de la primera versión estaba desactualizado), `BUILD SUCCESS`, `GatewayArchTest` en verde
- [x] T-17 · Prueba manual de extremo a extremo con el emulador detenido (`503`) y en marcha (`200`); registrar la salida en el PR — 24/09/2026: gateway en Docker en modo emulador y un perfil de mentira. (1) token del emulador → `200` con `X-User-*`; (2) token basura → `401`; (3) emulador detenido → `503 SERVICE_UNAVAILABLE` y log `El servidor de Firebase Auth no respondió ... [requestId=e2e-caido]`; (4) emulador de vuelta con el token viejo → `401`: la importación mueve `validSince` y la comprobación de revocación lo trata como revocado; con un token nuevo → `200`; (5) `FIREBASE_PROJECT_ID` real con el emulador → el contenedor no arranca con el mensaje de `REQ-EMC-05`
- [ ] T-18 · Bitácora de IA (`AGENTS.md` §10), commit y PR hacia `develop` con `[IA-ASISTIDO]` al final del título — **solo con autorización expresa**
