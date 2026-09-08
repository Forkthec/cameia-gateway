# Arranque local: cameia-gateway + cameia-perfil

Guía para levantar el gateway y el microservicio de perfiles en Docker local, sin instalar
Java, Maven ni PostgreSQL.

---

## Prerrequisitos

- Docker Desktop instalado y corriendo.
- Tener clonados ambos repositorios:
  - `cameia-gateway` (este repo)
  - `cameia-perfil`
- Un archivo JSON de service account de Firebase descargado desde
  [Firebase Console → Configuración del proyecto → Cuentas de servicio](https://console.firebase.google.com/).

---

## Configuración inicial (una sola vez)

### 1. Crear la red compartida

```bash
docker network create cameia-net
```

Esta red permite que el gateway resuelva `cameia-perfil-app` por nombre de contenedor.
Solo se crea una vez; Docker la recuerda entre reinicios.

### 2. Configurar el .env del gateway

Copia el archivo de ejemplo y rellena los valores:

```bash
cp .env.example .env
```

Edita `.env` y completa al menos:

```env
FIREBASE_PROJECT_ID=nombre-de-tu-proyecto-en-firebase
FIREBASE_KEY_PATH=/ruta/absoluta/a/tu/cameia-firebase.json
```

> **Windows:** usa barras normales en la ruta, por ejemplo:
> `FIREBASE_KEY_PATH=C:/Users/tu-usuario/secretos/cameia-firebase.json`

---

## Arranque

### Paso 1 — Levantar cameia-perfil

Desde la carpeta del repositorio `cameia-perfil`:

```bash
docker compose up --build -d
```

Espera a que el contenedor esté healthy:

```bash
docker compose ps
```

La columna `STATUS` debe mostrar `healthy` antes de continuar.

### Paso 2 — Levantar cameia-gateway

Desde la carpeta de este repositorio (`cameia-gateway`):

```bash
docker compose up --build -d
```

El gateway queda disponible en **http://localhost:8080**.

---

## Verificación rápida

```bash
# Health del gateway (sin token)
curl http://localhost:8080/actuator/health

# Endpoint de perfil (requiere token Firebase válido)
curl -H "Authorization: Bearer <tu-token-firebase>" \
     http://localhost:8080/api/v1/profiles/me
```

El token Firebase lo obtiene la aplicación front-end tras hacer sign-in con Firebase Auth.
Para pruebas manuales puede usarse el SDK de Firebase Admin o las herramientas de la consola
de Firebase para generar tokens de prueba.

---

## Parar todo

```bash
# Desde cameia-gateway:
docker compose down

# Desde cameia-perfil:
docker compose down
```

Para eliminar también los datos de la base de datos de perfil:

```bash
docker compose down -v
```

---

## Ejecutar las pruebas del gateway

Las pruebas no necesitan la red compartida ni los servicios corriendo; usan mocks internos.

```bash
docker compose run --rm verify
```

Resultado esperado: `BUILD SUCCESS` con 10 pruebas en verde.

---

## Puertos

| Servicio | Puerto anfitrión | Descripción |
|----------|-----------------|-------------|
| cameia-gateway | 8080 | Punto de entrada HTTP — úsalo para todas las llamadas |
| cameia-perfil db | 5432 | PostgreSQL — solo para inspección con un cliente de BD |

cameia-perfil no expone el puerto 8082 al front-end; solo es accesible a través del gateway.
