# Plan — CM-113: Integración cameia-gateway ↔ cameia-perfil en Docker local

- **Fecha:** 07/09/2026
- **Flujo SDD:** Fase 2 — Diseño técnico
- **Spec de referencia:** `specs/CM-113-integracion-perfil/spec.md`

---

## Decisión de diseño: red Docker externa compartida

Cada repo conserva su propio `docker-compose.yml`. Se crea una red Docker externa (`cameia-net`)
que ambos composes declaran como `external: true`. Los contenedores se resuelven por nombre de
contenedor (ej. `cameia-perfil-app`).

**Alternativa descartada:** un compose raíz que orqueste los dos repos. Requeriría rutas
relativas frágiles entre repos y rompe la independencia de despliegue de cada microservicio.

---

## Archivos que cambian

### cameia-gateway

| Archivo | Cambio |
|---------|--------|
| `docker-compose.yml` | Agregar servicio `app`; agregar red `cameia-net` (external); agregar red `cameia-gateway-net` (interna) |
| `docs/DOCKER-LOCAL.md` | Nuevo — instrucciones de arranque para el dev de front (REQ-10) |

### cameia-perfil

| Archivo | Cambio |
|---------|--------|
| `docker-compose.yml` | Agregar red `cameia-net` (external) al servicio `app`; declarar la red como external |

---

## Diseño del servicio `app` en cameia-gateway/docker-compose.yml

```yaml
app:
  build:
    context: .
    dockerfile: Dockerfile
  image: cameia-gateway:local
  container_name: cameia-gateway-app
  depends_on: []          # sin dependencias: no tiene BD propia
  environment:
    SPRING_APPLICATION_NAME: cameia-gateway
    SPRING_PROFILES_ACTIVE: local
    SERVER_PORT: 8080
    FIREBASE_PROJECT_ID: ${FIREBASE_PROJECT_ID}
    GOOGLE_APPLICATION_CREDENTIALS: /run/secrets/firebase-key
    CAMEIA_PERFIL_URL: http://cameia-perfil-app:8082
    CAMEIA_CUENTAS_URL: ${CAMEIA_CUENTAS_URL:-http://cameia-cuentas-app:8081}
    CAMEIA_ENTREVISTA_URL: ${CAMEIA_ENTREVISTA_URL:-http://cameia-entrevista-app:8083}
    LOG_LEVEL: INFO
  volumes:
    - ${FIREBASE_KEY_PATH}:/run/secrets/firebase-key:ro
  ports:
    - "${SERVER_PORT:-8080}:8080"
  healthcheck:
    test: ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health || exit 1"]
    interval: 10s
    timeout: 5s
    retries: 12
    start_period: 30s
  networks:
    - cameia-gateway-net
    - cameia-net
```

**Nota sobre la clave Firebase:** se monta como volumen de solo lectura desde la ruta que el
desarrollador define en `.env` (`FIREBASE_KEY_PATH`). Nunca se copia al imagen ni al repo.
El `.gitignore` ya excluye `*.json` de credenciales.

---

## Diseño de redes en cameia-gateway/docker-compose.yml

```yaml
networks:
  cameia-gateway-net:
    driver: bridge
  cameia-net:
    external: true
```

---

## Cambio en cameia-perfil/docker-compose.yml

Solo se añade la red `cameia-net` al servicio `app` existente y se declara como external.
No se toca ningún otro servicio (`db`, `verify`) ni ninguna variable de entorno.

```yaml
# en el servicio app — agregar:
    networks:
      - cameia-perfil-net
      - cameia-net          # ← nuevo

# al final del archivo — agregar:
  cameia-net:
    external: true
```

---

## .env.example del gateway

Variables nuevas que el desarrollador debe tener en su `.env` local:

```
FIREBASE_PROJECT_ID=tu-proyecto-firebase
FIREBASE_KEY_PATH=/ruta/absoluta/a/firebase-service-account.json
SERVER_PORT=8080
CAMEIA_CUENTAS_URL=http://cameia-cuentas-app:8081
CAMEIA_ENTREVISTA_URL=http://cameia-entrevista-app:8083
```

`CAMEIA_PERFIL_URL` no va en `.env.example` porque está hardcoded en el compose como
`http://cameia-perfil-app:8082` (nombre de contenedor fijo, no configurable por entorno).

---

## Flujo de arranque para el dev de front

```
# 1. Crear la red compartida (una sola vez)
docker network create cameia-net

# 2. Levantar cameia-perfil (desde su repo)
docker compose up --build -d

# 3. Levantar cameia-gateway (desde su repo, con .env configurado)
docker compose up --build -d
```

Gateway disponible en `http://localhost:8080`.
Perfil accesible únicamente a través del gateway.

---

## Seguridad

- La clave Firebase entra por volumen montado (`:ro`), nunca por variable de entorno ni copiada en imagen.
- El servicio `app` del gateway no expone la red `cameia-perfil-net`; solo comparte `cameia-net`.
- cameia-perfil no necesita abrir nuevos puertos al anfitrión para que el gateway lo alcance.
- El header `Authorization` es eliminado por el filtro antes de llegar a perfil (ya implementado, CA-04 del spec lo verifica en integración).
