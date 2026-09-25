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

**Un solo ID de proyecto.** Con el emulador, `FIREBASE_PROJECT_ID` debe empezar por `demo-`: si no,
el gateway se niega a arrancar, porque el emulador nunca emitiría tokens para otro proyecto. El
emulador toma ese mismo valor por su cuenta, así que para cambiar de ID basta con editar
`FIREBASE_PROJECT_ID`. `cameia-cuentas` debe usar **el mismo ID y el mismo emulador**; si no, crea
usuarios en un proyecto y el gateway los valida contra otro. El ID que usa cada uno sale en su log
de arranque: `docker compose logs firebase-emulator app | grep -i proyecto`.

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

## Emulador de Firebase Auth

El compose incluye `firebase-emulator`, un emulador de Firebase Auth que sustituye a un
proyecto real de Firebase en desarrollo local: no pide llave de service account ni cuenta de
Google, y los usuarios que se crean en él solo existen en tu máquina. Es **una sola instancia**
para todos los servicios de `cameia-net` y para el navegador.

Se levanta junto con el gateway (`docker compose up --build -d`). La primera vez construye la
imagen (Node más `firebase-tools`, unos 660 MB, un par de minutos). Para levantar solo el
emulador, por ejemplo para trabajar en otro servicio sin el gateway:

```bash
docker compose up --build -d firebase-emulator
docker compose ps        # STATUS debe mostrar "healthy"
curl http://localhost:9099/
```

| Desde dónde | Dirección |
|---|---|
| Tu equipo (navegador, `curl`) | `http://localhost:9099` |
| Otro contenedor de `cameia-net` | `http://cameia-firebase-emulator:9099` |
| Emulator UI (usuarios, claims, correo verificado) | `http://localhost:4000/auth` |
| ID de proyecto | El de `FIREBASE_PROJECT_ID` si empieza por `demo-`; si no, `demo-cameia`. `FIREBASE_EMULATOR_PROJECT_ID` lo fuerza (también debe empezar por `demo-`) |

Los puertos se publican solo en `127.0.0.1`: otros equipos de la red no pueden llegar al emulador.

**Si el emulador no responde**, las rutas protegidas del gateway responden `503 SERVICE_UNAVAILABLE`
y no `401`: el problema no es tu token. El log del gateway lo dice con
`El servidor de Firebase Auth no respondió al verificar el token`. El gateway no espera al
emulador al arrancar, así que los primeros segundos tras `docker compose up` pueden dar `503`.

### Qué se guarda y qué no

Los usuarios se guardan en el volumen `firebase-emulator-data` **al apagar de forma ordenada**
(`docker compose stop` o `docker compose down`) y se recuperan al volver a arrancar. Un apagado
brusco (`docker kill`, cerrar Docker a la fuerza, un corte de luz) **no guarda nada**: se pierde
lo creado desde el último apagado ordenado.

**Tras reiniciar el emulador hay que volver a iniciar sesión.** Al importar los usuarios, el
emulador mueve su `validSince` al momento del arranque, y el gateway comprueba la revocación del
token: todo token emitido antes del reinicio responde `401`. Pide un token nuevo con
`accounts:signInWithPassword` (o vuelve a iniciar sesión en el front-end). Comprobado el 24/09/2026.

Si al apagar no estás seguro de que se guardó, busca `Export complete` en
`docker compose logs firebase-emulator`. Un fallo al exportar aparece ahí como `Export failed`,
pero el contenedor termina igualmente con código 0.

### Empezar de cero

```bash
# Borrar todos los usuarios sin apagar nada
curl -X DELETE http://localhost:9099/emulator/v1/projects/demo-cameia/accounts

# Borrar también lo guardado (elimina todos los volúmenes de este compose,
# incluida la caché de Maven, que se vuelve a descargar)
docker compose down -v
```

Hazlo cuando la base de datos de un servicio y el emulador queden desalineados. Por ejemplo, si
borras la base de un servicio con `docker compose down -v` en su repositorio, el emulador
conserva el usuario y ese correo ya no se puede volver a registrar. Al revés, si vacías el
emulador y la base conserva sus filas, esos usuarios ya no pueden iniciar sesión.

> ⚠️ **Los usuarios del emulador y los de `cameia-cuentas` se desincronizan con facilidad**
> (advertencia 1 de `specs/CM-188-correcciones/spec.md`). Un apagado brusco pierde en el emulador
> los usuarios que la base de Cuentas sí conserva; `docker compose down -v` en un repositorio no
> toca los volúmenes del otro; y el emulador genera `uid` aleatorios, así que ningún dato semilla
> con `uid` fijos coincidirá. El síntoma es un `401` para un usuario que Cuentas sí tiene. Regla
> práctica: **si reinicias uno de los dos, reinicia el otro.**

### Diferencias con Firebase real

- **Los tokens no van firmados** (`alg: none`): el emulador sirve solo para desarrollo local y
  la verificación criptográfica real únicamente se ejercita en staging.
- **No envía correos.** El código de verificación de un correo se lee en
  `http://localhost:9099/emulator/v1/projects/demo-cameia/oobCodes`.
- **No aplica cuotas, límites de tasa ni dominios autorizados** (`authorizedDomains`).
- **Códigos de error de inicio de sesión:** con una contraseña incorrecta o un usuario que no
  existe, el emulador devuelve `auth/wrong-password` y `auth/user-not-found`. Según la
  documentación de Firebase, un proyecto real con la protección contra enumeración de correos
  activa devuelve `auth/invalid-credential` en ambos casos. No se ha comparado contra el
  proyecto real: si tu código distingue esos códigos, pruébalo también en staging.

### Arrancar el gateway fuera de Docker (no recomendado)

> ⚠️ Advertencia 2 de `specs/CM-188-correcciones/spec.md`. Se recomienda `docker compose`: el
> compose ya entrega todo con los valores correctos.

Si aun así lo arrancas desde el IDE o con Maven local:

- `FIREBASE_AUTH_EMULATOR_HOST` debe ser `localhost:9099`: `cameia-firebase-emulator` solo se
  resuelve dentro de `cameia-net`.
- Debe definirse como **variable de entorno** de la configuración de ejecución. Como `-D`,
  argumento `--` o entrada de un YAML, el Admin SDK de Firebase no la ve y el gateway se niega a
  arrancar con un mensaje que lo explica.

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
| firebase-emulator | 9099 | Emulador de Firebase Auth — solo accesible desde tu equipo (`127.0.0.1`) |
| firebase-emulator | 4000 | Emulator UI — solo accesible desde tu equipo (`127.0.0.1`) |
| cameia-perfil db | 5432 | PostgreSQL — solo para inspección con un cliente de BD |

cameia-perfil no expone el puerto 8082 al front-end; solo es accesible a través del gateway.
