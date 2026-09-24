# Spec — CM-190-emulador-firebase-auth: el Gateway arranca contra el emulador de Firebase Auth sin abrir la puerta en despliegue

- **HU asociada:** CM-190 — subtarea de CM-188. No es una HU de negocio: es soporte de desarrollo local
- **Sprint:** 1
- **Fecha:** 19/09/2026
- **Estado:** Fases 1 y 2 cerradas con la decisión de Paula Andrea Muñoz Delgado del 19/09/2026 (`GW-TBD-28`)
- **Flujo SDD:** Fase 1 — Requisitos EARS
- **Rama:** `CM-190-gateway-arranque-con-emulador-firebase-auth` → `develop` (creada desde `origin/develop`)
- **Alcance:** **solo `FirebaseConfig`**, más la variable en el compose, `.env.example` y la documentación. Ni filtros, ni rutas, ni firma OIDC
- **Fuentes:** el propio `FirebaseConfig.java`, `AGENTS.md` §4, §6, §7 y §9, y la guardia del perfil `local` de `FirebaseAuthGlobalFilter` (`CM-14`, `REQ-REG-02`)

---

## 1. Contexto

Hoy `FirebaseConfig.init` pide siempre `GoogleCredentials.getApplicationDefault()`. Sin un JSON de
service account ni `gcloud` configurado, el Gateway no arranca. Por eso, para desarrollar en local,
cada integrante necesita credenciales de un proyecto real de Firebase (hoy, las de un proyecto
personal), que no está bajo control del equipo y cuyos tokens no sirven contra staging.

La alternativa decidida es el **emulador de Firebase Auth**: un servicio local (un contenedor del
`docker-compose.yml`) que no exige credenciales y cuyos usuarios solo existen en la máquina.

### 1.1 Qué hace el Admin SDK con el emulador (verificado, no supuesto)

Verificado el 19/09/2026 con `firebase-admin` 9.10.0 (la versión de este repositorio), inspeccionando
el `.jar` y con un programa Java contra un emulador real en Docker:

- Si existe la variable de entorno `FIREBASE_AUTH_EMULATOR_HOST`, el SDK la detecta **por sí solo**
  leyendo el entorno del proceso: `FirebaseUserManager` envía las peticiones a
  `http://<host>/identitytoolkit.googleapis.com/...` y `FirebaseTokenVerifierImpl` cambia al modo
  emulador.
- En ese modo el SDK sustituye las credenciales por `EmulatorCredentials`, **pero**
  `FirebaseOptions.builder().build()` sigue exigiendo un objeto de credenciales no nulo.
- Sin llave ni `gcloud`, `GoogleCredentials.getApplicationDefault()` lanza `IOException`. Con
  credenciales ficticias, en cambio, el SDK crea usuarios, verifica un token del emulador, lee sus
  claims y rechaza un token basura.

Conclusión: la única pieza que falta en el Gateway son unas credenciales ficticias cuando el emulador
está configurado.

### 1.2 Por qué el despliegue exige una guardia

En modo emulador los tokens **no van firmados** (`alg: none`) y el SDK los acepta. Si
`FIREBASE_AUTH_EMULATOR_HOST` llegara a un despliegue, cualquiera podría fabricar un token y
suplantar a cualquier usuario: se anularía lo que la matriz ASVS registra como cumplido en `9.1.1`
(validar la firma del token) y `9.1.2` (nunca aceptar `none`).

El Gateway **no puede** neutralizar la variable ignorándola: el SDK la lee directamente del entorno
del proceso, sin pasar por Spring. La única defensa que controla el Gateway es **negarse a arrancar**
si la variable aparece donde no debe.

### 1.3 El patrón ya existe en el repositorio

`FirebaseAuthGlobalFilter` ya falla el arranque cuando el perfil `local` (que abre rutas públicas de
desarrollo) coincide con un despliegue: `K_SERVICE` definida (Cloud Run la inyecta) o perfil `prod`
activo. Este spec aplica el mismo criterio de "despliegue" a la variable del emulador.

### Lo que este spec NO hace

- No cambia ninguna ruta, filtro, respuesta HTTP ni la firma OIDC saliente.
- No cambia el comportamiento cuando la variable **no** está definida (`REQ-EMU-03`).
- No toca `cameia-cuentas` ni `cameia-web`: cada uno necesita su propio cambio, que pide DevOps a sus
  responsables por separado.

---

## 2. Requisitos funcionales — notación EARS

Numeración propia: `REQ-EMU-NN`.

### REQ-EMU-01 — Con el emulador configurado, el Gateway arranca sin credenciales de Google

```
Cuando FIREBASE_AUTH_EMULATOR_HOST esté definida con un valor no vacío y el sistema no corra en un
despliegue, debe inicializar Firebase con credenciales ficticias y no debe pedir las credenciales
por defecto de Google.
```

### REQ-EMU-02 — En un despliegue, la variable impide el arranque

```
Mientras FIREBASE_AUTH_EMULATOR_HOST esté definida —aunque su valor esté vacío— y exista K_SERVICE
o esté activo el perfil prod, el sistema no debe arrancar. El mensaje del error debe nombrar la
variable y explicar por qué.
```

> Se cuenta como "definida" aunque esté vacía: es el criterio más conservador, y evita que un valor
> en blanco deje pasar una configuración que el SDK podría interpretar de otra manera.

### REQ-EMU-03 — Sin la variable, nada cambia

```
Mientras FIREBASE_AUTH_EMULATOR_HOST no esté definida, el sistema debe pedir las credenciales por
defecto de Google exactamente como hasta ahora.
```

### REQ-EMU-04 — El compose puede pasar la variable sin obligar a definirla

```
El docker-compose.yml debe pasar FIREBASE_AUTH_EMULATOR_HOST al servicio app únicamente cuando esté
definida en el entorno o en el archivo .env; si no lo está, el contenedor no debe recibirla.
```

> Pasarla vacía por defecto sería activar sin querer la guardia de `REQ-EMU-02` y, peor, dejar una
> variable "definida pero vacía" dentro del contenedor.

## 3. Requisitos no funcionales

### REQ-NF-EMU-01 — Ningún secreto

Las credenciales ficticias son un literal sin valor real. No se versiona ninguna llave ni token
(`AGENTS.md` §7, punto 9 de "Lo que bloquea un PR").

### REQ-NF-EMU-02 — El cambio no altera ninguna respuesta HTTP

Sin la variable, el Gateway se comporta igual que antes; con ella, solo cambia de dónde salen las
credenciales de arranque.

---

## 4. Clarificaciones

| ID | Pregunta | Estado |
|---|---|---|
| `GW-TBD-28` | Si `FIREBASE_AUTH_EMULATOR_HOST` aparece en un despliegue, ¿el Gateway falla el arranque o la ignora? | ✅ Cerrado 19/09/2026: **falla el arranque.** Ignorarla no es posible (el SDK la lee del entorno por su cuenta), y aceptar tokens sin firma en producción es una suplantación de identidad |

---

## 5. Trazabilidad requisito → prueba

| Requisito | Prueba |
|---|---|
| `REQ-EMU-01` | `FirebaseConfigTest` (unitaria) y `FirebaseEmulatorStartupTest` (contexto completo) |
| `REQ-EMU-02` | `FirebaseConfigTest` (`K_SERVICE`, perfil `prod`, variable vacía) y `FirebaseEmulatorStartupTest` (contexto completo) |
| `REQ-EMU-03` | `FirebaseConfigTest` |
| `REQ-EMU-04` | Comprobación manual con `docker compose config` (registrada en el PR) |
| `REQ-NF-EMU-02` | Suite existente sin modificar, en verde |
