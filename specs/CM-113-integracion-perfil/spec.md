# Spec — CM-113: Integración cameia-gateway ↔ cameia-perfil en Docker local

- **HU asociada:** CM-113 — Conectar el API Gateway con cameia-perfil para pruebas locales del frontend
- **Sprint:** 1
- **Fecha:** 07/09/2026
- **Estado:** propuesta pendiente de aprobación
- **Flujo SDD:** Fase 1 — Requisitos EARS
- **Fuentes:** `cameia-gateway/docker-compose.yml`, `cameia-perfil/docker-compose.yml`,
  `cameia-gateway/src/main/resources/application.yml` (ruta `/api/v1/profiles/**` ya declarada),
  `cameia-perfil/src/main/resources/application.yml` (puerto 8082)

---

## Contexto

El gateway tiene la ruta `/api/v1/profiles/**` → `${CAMEIA_PERFIL_URL}` declarada en `application.yml`
y tiene un `Dockerfile` multi-stage funcional, pero su `docker-compose.yml` solo incluye el servicio
`verify` (pruebas). cameia-perfil tiene el servicio `app` corriendo en el puerto 8082 pero en una red
Docker aislada. Ninguno de los dos puede hablar con el otro en desarrollo local.

El objetivo de esta HU es que el frontend pueda probar **todos los endpoints de cameia-perfil
llamando únicamente al gateway** (puerto 8080), con el token Firebase en el header `Authorization`.

---

## Requisitos EARS

### Infraestructura de red

**REQ-01** El sistema debe crear una red Docker externa llamada `cameia-net` que permita que
contenedores de distintos `docker-compose.yml` se descubran por nombre de contenedor.

**REQ-02** Cuando se ejecute `docker compose up` en cameia-gateway, el sistema debe unir el
contenedor `cameia-gateway-app` a la red `cameia-net`.

**REQ-03** Cuando se ejecute `docker compose up` en cameia-perfil, el sistema debe unir el
contenedor `cameia-perfil-app` a la red `cameia-net`.

### Servicio app del gateway

**REQ-04** El sistema debe exponer el servicio `app` del gateway en el puerto 8080 de la
máquina anfitriona, usando el `Dockerfile` existente.

**REQ-05** Cuando el servicio `app` del gateway arranque, el sistema debe configurar
`CAMEIA_PERFIL_URL=http://cameia-perfil-app:8082` para que el enrutamiento use el nombre
del contenedor de perfil dentro de la red compartida.

**REQ-06** El sistema debe publicar las variables de entorno mínimas necesarias para que el
gateway arranque: `FIREBASE_PROJECT_ID`, `GOOGLE_APPLICATION_CREDENTIALS`,
`CAMEIA_CUENTAS_URL`, `CAMEIA_ENTREVISTA_URL`, `CAMEIA_PERFIL_URL`, `SERVER_PORT`.

**REQ-07** El sistema debe incluir un `healthcheck` en el servicio `app` del gateway que
consulte `/actuator/health` cada 10 segundos.

### Conectividad gateway → perfil

**REQ-08** Cuando el frontend envíe `GET /api/v1/profiles/me` al gateway con un token Firebase
válido, el sistema debe enrutar la petición a `cameia-perfil-app:8082/api/v1/profiles/me`
con los headers `X-User-Id` y `X-User-Plan` propagados.

**REQ-09** Cuando el frontend envíe cualquier petición a `/api/v1/profiles/**`, el sistema
debe eliminar el header `Authorization` antes de reenviarla a cameia-perfil (ya implementado
en el filtro; este requisito verifica que no se rompa en el entorno Docker real).

### Documentación de arranque

**REQ-10** El sistema debe proveer instrucciones en `docs/DOCKER-LOCAL.md` del gateway que
expliquen al frontend cómo levantar ambos servicios en tres comandos, sin instalar Java ni Maven.

---

## Criterios de aceptación

| # | Dado | Cuando | Entonces |
|---|------|--------|----------|
| CA-01 | La red `cameia-net` no existe | Se ejecuta el comando de arranque descrito en REQ-10 | La red se crea automáticamente y ambos contenedores la usan |
| CA-02 | Los dos servicios están corriendo | El frontend llama `GET http://localhost:8080/api/v1/profiles/me` con token Firebase válido | Recibe 200 con el perfil del usuario |
| CA-03 | Los dos servicios están corriendo | El frontend llama sin token | Recibe 401 `AUTH_REQUIRED` desde el gateway, sin que la petición llegue a perfil |
| CA-04 | Solo perfil está corriendo (gateway apagado) | El frontend llama directo a `localhost:8082` | La conexión falla o da 404 (perfil no acepta el header Authorization de Firebase — no es su responsabilidad) |
| CA-05 | — | Se ejecuta `docker compose run --rm verify` en el gateway | BUILD SUCCESS, 10 pruebas en verde (sin regresiones) |

---

## Fuera de alcance

- Despliegue en ambiente compartido o producción.
- Rate limiting, circuit breaker, mTLS entre servicios.
- Conexión con cameia-cuentas o cameia-entrevista (no tienen servicio `app` aún).
- Modificar la lógica del filtro Firebase (ya implementada en CM-104).
