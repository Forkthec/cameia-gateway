# Tasks — CM-14-verificacion-correo

- **Spec de referencia:** `specs/CM-14-verificacion-correo/spec.md`
- **Plan de referencia:** `specs/CM-14-verificacion-correo/plan.md`
- **Fecha:** 18/09/2026
- **Flujo SDD:** Fase 4 — Checkboxes de implementación
- **Rama:** `CM-14-verificacion-correo` → `develop`

## Cómo usar esta lista

- Una tarea a la vez; marcar `[x]` antes de la siguiente.
- Al cerrar el bloque: `docker compose run --rm verify` en verde.

---

## Bloque 1 — Propagar el estado de verificación

- [x] T-01 · Añadir las constantes `X_USER_EMAIL_VERIFIED` y `EMAIL_VERIFIED_CLAIM`, e incluir la cabecera en `IDENTITY_HEADERS` (REQ-VER-04, plan §2) — 18/09/2026
- [x] T-02 · Añadir `readEmailVerified` leyendo del mapa de claims, **no** de `isEmailVerified()`, y emitirla en `withIdentity` con `setIfPresent` (REQ-VER-01, REQ-VER-02, REQ-VER-03) — 18/09/2026
- [x] T-03 · Pruebas 1, 2 y 3 del plan: claim `true`, claim `false` y claim ausente (REQ-VER-01 a REQ-VER-03) — 18/09/2026: `emailVerifiedClaim_isPropagatedToDownstream` (parametrizada) y `tokenWithoutEmailVerifiedClaim_omitsHeader`
- [x] T-04 · Pruebas 4 y 5 del plan: la cabecera del cliente se reemplaza en Caso A y se borra en Caso B (REQ-VER-04) — 18/09/2026: `clientEmailVerifiedHeader_isReplacedByTokenClaim`, `publicRoute_dropsClientEmailVerifiedHeader`

## Bloque 2 — La ruta de verificación y el tipo de contenido

- [x] T-05 · Prueba 6 del plan: `POST /api/v1/users/me/verification` sin token → `401`; con token válido llega a Cuentas con el contrato de §6.4 (REQ-VER-05) — 18/09/2026
- [x] T-06 · Prueba 7 del plan: una respuesta `application/problem+json` del destino llega intacta en estado, cuerpo y `Content-Type` (REQ-VER-06) — 18/09/2026: `downstreamProblemJson_isReturnedUnchanged`

## Bloque 3 — Documentación y cierre

- [x] T-07 · `AGENTS.md` §4, §5, §6.4 y §8 (plan §5) — 18/09/2026
- [x] T-08 · `docs/COMO-FUNCIONA.md`: tabla de lo que recibe el microservicio (plan §5) — 18/09/2026
- [x] T-09 · `specs/NUMERACIONES.md` con `GW-TBD-26`; `GW-TBD-17` apunta a este spec; T-00k de `CM-14-Registro-usuario` cerrada con la ruta — 18/09/2026: `GW-TBD-26` cerrado; `GW-TBD-17` parcial; T-00k de CM-14 cerrada
- [x] T-10 · Recorrer el DoD del spec §6 con evidencia real — 18/09/2026: DoD del spec §6 con evidencia; quedan el aviso (T-12) y el título del PR
- [x] T-11 · Bitácora de IA del mismo día (`AGENTS.md` §10) — 18/09/2026
- [ ] T-12 · **Aviso a cameia-entrevista y a cameia-cuentas:** §6.4 suma `X-User-Email-Verified`; las tres partes lo cambian a la vez
- [ ] T-13 · Abrir el PR **solo con autorización expresa** (`AGENTS.md` §0, punto 1)

## Bloque 4 — Corrección de seguridad del token

- [x] T-14 · Activar la comprobación de tokens revocados mediante `verifyIdToken(idToken, true)` en el filtro de autenticación — 21/09/2026
- [x] T-15 · Crear y ejecutar una prueba específica que confirme que un token revocado recibe `401 AUTH_REQUIRED` y no llega al downstream — `revokedToken_returns401AndDoesNotReachDownstream`

---

## Trazabilidad requisito → tarea

| Requisito | Tareas |
|---|---|
| `REQ-VER-01` | T-02, T-03 |
| `REQ-VER-02` | T-02, T-03 |
| `REQ-VER-03` | T-02, T-03 |
| `REQ-VER-04` | T-01, T-04 |
| `REQ-VER-05` | T-05 |
| `REQ-VER-06` | T-06 |
| `REQ-NF-VER-01` | T-01, T-02 |
| `REQ-NF-VER-02` | T-03 a T-06 |
