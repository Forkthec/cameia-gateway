# Tasks — CM-113: Integración cameia-gateway ↔ cameia-perfil en Docker local

- **Fecha:** 07/09/2026
- **Flujo SDD:** Fase 3 — Checkboxes de implementación
- **Plan de referencia:** `specs/CM-113-integracion-perfil/plan.md`

---

## cameia-gateway

- [x] T-01 · Agregar servicio `app` al `docker-compose.yml` del gateway (REQ-04, REQ-05, REQ-06, REQ-07)
- [x] T-02 · Agregar redes `cameia-gateway-net` (interna) y `cameia-net` (external) al `docker-compose.yml` del gateway (REQ-01, REQ-02)
- [x] T-03 · Actualizar `.env.example` del gateway con las nuevas variables (REQ-06)
- [x] T-04 · Crear `docs/DOCKER-LOCAL.md` con instrucciones de arranque (REQ-10)

## cameia-perfil

- [x] T-05 · Agregar red `cameia-net` (external) al servicio `app` del `docker-compose.yml` de perfil (REQ-01, REQ-03)

## Validación

- [x] T-06 · Ejecutar `docker compose run --rm verify` en cameia-gateway → BUILD SUCCESS (CA-05) — 10/10 en verde
- [ ] T-07 · Verificar arranque manual: `docker network create cameia-net`, levantar perfil, levantar gateway, `curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/profiles/me`
