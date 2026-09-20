# Arranque local: cameia-gateway + cameia-perfil

Guía para levantar el gateway y el microservicio de perfiles en Docker local, sin instalar
Java, Maven ni PostgreSQL.

---

## Prerrequisitos

- Docker Desktop instalado y corriendo.
- Tener clonados ambos repositorios:
  - `cameia-gateway` (este repo)
  - `cameia-perfil`
- **Nada más.** Por defecto el gateway usa el emulador de Firebase Auth que trae el compose, así que
  no necesitas un proyecto de Firebase ni ninguna llave. Solo si prefieres un proyecto real de
  Firebase, necesitas además un JSON de service account descargado desde
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

Con el emulador no tienes que editar nada: `.env.example` ya trae estas dos líneas.

```env
FIREBASE_PROJECT_ID=demo-cameia
FIREBASE_AUTH_EMULATOR_HOST=cameia-firebase-emulator:9099
```

Con la variable `FIREBASE_AUTH_EMULATOR_HOST` definida, el gateway arranca sin llave y acepta los
tokens del emulador. **Esos tokens no van firmados**, por eso el gateway se niega a arrancar si
esa variable aparece en un despliegue (Cloud Run o perfil `prod`), aunque esté vacía.

Para usar un **proyecto real** de Firebase en su lugar, comenta la línea de
`FIREBASE_AUTH_EMULATOR_HOST` y completa:

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

### Obtener un token del emulador

Con el emulador no necesitas el front-end para probar una ruta protegida. Crea un usuario y pide
su token (el valor de `key` puede ser cualquier texto):

```bash
curl -s -X POST "http://localhost:9099/identitytoolkit.googleapis.com/v1/accounts:signUp?key=falsa" \
     -H "Content-Type: application/json" \
     -d '{"email":"dev@example.com","password":"Clave-De-Prueba-2026","returnSecureToken":true}'
```

La respuesta trae `idToken`: ese valor es el `<tu-token-firebase>` del comando anterior. Para volver
a pedirlo después, usa el mismo cuerpo contra `accounts:signInWithPassword`. En PowerShell escribe
`curl.exe`, porque `curl` es un alias de otro comando.

El gateway pasa al microservicio las cabeceras `X-User-Id`, `X-User-Email`,
`X-User-Email-Verified` y, si el usuario tiene el claim `plan`, `X-User-Plan`. Un token inválido,
vacío o ausente da `401`.

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
