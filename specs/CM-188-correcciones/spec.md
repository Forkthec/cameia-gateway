# Spec — CM-188-correcciones: el Gateway y el emulador de Firebase Auth no deben engañar a quien desarrolla

- **HU asociada:** CM-188 — correcciones sobre CM-189 (emulador en el compose) y CM-190 (arranque del Gateway con el emulador). No es una HU de negocio: es soporte de desarrollo local
- **Sprint:** 1
- **Fecha:** 24/09/2026
- **Estado:** Fases 1 y 2 cerradas con la decisión de Juan David Vela Coronado del 24/09/2026 (`GW-TBD-29`, `GW-TBD-30`)
- **Flujo SDD:** Fase 1 — Requisitos EARS
- **Rama:** `CM-188-correcciones` → `develop` (creada desde `develop` en `c0824c8`)
- **Alcance:** `FirebaseAuthGlobalFilter` (solo la clasificación del fallo de verificación), `FirebaseConfig`, el servicio `firebase-emulator` del `docker-compose.yml`, `docker/firebase-emulator/entrypoint.sh`, `.env.example`, `README.md` y la documentación
- **Fuentes:** revisión de los commits `3f76f4d` (CM-189) y `c0824c8` (CM-190) del 24/09/2026; `specs/CM-190-emulador-firebase-auth/`; `AGENTS.md` §4, §6 y §9

---

## 1. Contexto

La meta de CM-189 y CM-190 es que quien levante `cameia-cuentas` por primera vez no encuentre
usuarios distintos en la base de datos local y en Firebase: todos usan el emulador local. La
revisión encontró tres puntos en los que el emulador sí funciona, pero el Gateway responde algo
que **no dice la verdad** sobre el problema. Este spec corrige solo esos tres.

### 1.1 Problema 1 — Emulador caído se reporta como token inválido

Desde `bce4045` el filtro verifica con `verifyIdToken(idToken, true)`: el `true` comprueba la
revocación, y eso exige una llamada `getUser` al servidor de Auth **en cada petición de Caso A**.
En local ese servidor es el emulador.

- El servicio `app` del compose no declara `depends_on` sobre `firebase-emulator`: el Gateway puede
  recibir peticiones antes de que el emulador exista, o después de que se caiga.
- El fallo de red llega envuelto en `FirebaseAuthException`, y `isTokenRejection` trata **toda**
  `FirebaseAuthException` como token rechazado → `401 AUTH_REQUIRED` "Token de acceso inválido".
- Quien desarrolla renueva el token, vuelve a fallar y concluye que el problema es su usuario. El
  problema real es que el servidor de Auth no responde.

**Esto no es solo local.** En despliegue, un fallo de red hacia Google durante la comprobación de
revocación hoy también se reporta como `401`. La corrección cambia también ese comportamiento: un
fallo del servidor de Auth es un problema del Gateway, no del cliente, y le corresponde `503`, que
es lo que el Javadoc de `isTokenRejection` ya promete para las claves públicas.

### 1.2 Problema 3 — Dos IDs de proyecto que deben coincidir y nadie lo comprueba

- El emulador arranca con `FIREBASE_EMULATOR_PROJECT_ID` (por defecto `demo-cameia`).
- El Gateway verifica con `FIREBASE_PROJECT_ID` (en `.env.example`, `demo-cameia`).
- El token del emulador lleva `aud` e `iss` con el ID del emulador. Si alguien cambia solo una de
  las dos variables, **todos** los tokens dan `401` y nada dice que la causa sea el ID de proyecto.
- El mismo desajuste entre el Gateway y `cameia-cuentas` produce justo lo que CM-188 quiere evitar:
  Cuentas crea usuarios en un proyecto del emulador y el Gateway valida contra otro.
- Si `FIREBASE_PROJECT_ID` conserva el ID de un proyecto real mientras el emulador está activo, el
  Gateway arranca sin avisar y rechaza todo.

### 1.3 Problema 4 — Spring y el Admin SDK leen la variable del emulador de lugares distintos

- `FirebaseConfig` decide el modo emulador con el `Environment` de Spring, que además del entorno
  del proceso incluye propiedades del sistema (`-D`), argumentos (`--`) y archivos YAML.
- El Admin SDK **solo** lee la variable de entorno del proceso (verificado en CM-190 §1.1).
- Si la variable llega por `-D`, por argumento o por YAML (lo normal al arrancar desde el IDE), el
  Gateway entrega credenciales ficticias pero el SDK **no** entra en modo emulador: verifica contra
  Google con claves reales, rechaza todo token del emulador con `401` y, con credenciales ficticias,
  la comprobación de revocación contra Google falla también. Nada en el log lo explica.
- Al revés no hay riesgo de seguridad: la guardia de despliegue de CM-190 (`REQ-EMU-02`) sigue
  viendo la variable venga de donde venga, y eso se conserva.

### Lo que este spec NO hace

- No corrige el problema 2 ni el 5 de la revisión: quedan como advertencias en §6.
- No cambia la guardia de despliegue de CM-190 (`REQ-EMU-02`) ni su criterio de "despliegue".
- No cambia rutas, headers `X-User-*`, la firma OIDC ni el catálogo de códigos de error.
- No toca `cameia-cuentas` ni `cameia-web`.

---

## 2. Requisitos funcionales — notación EARS

Numeración propia: `REQ-EMC-NN`.

### REQ-EMC-01 — Un fallo al contactar el servidor de Auth no es un token inválido (problema 1)

```
Si la verificación del ID Token falla porque el servidor de Firebase Auth (real o emulador) no se
pudo contactar, entonces el sistema debe responder 503 con el código SERVICE_UNAVAILABLE del
catálogo, no debe responder 401 y no debe reenviar la petición al downstream.
```

### REQ-EMC-02 — Un token rechazado sigue siendo 401 (problema 1)

```
Si la verificación del ID Token falla por el token (firma, expiración, emisor, audience, formato,
revocado, usuario inexistente o deshabilitado), entonces el sistema debe seguir respondiendo 401 con
AUTH_REQUIRED, igual que hoy.
```

### REQ-EMC-03 — El log dice qué falló (problema 1)

```
Cuando ocurra REQ-EMC-01, el sistema debe registrar en el log, con el X-Request-Id de la petición,
que el servidor de Firebase Auth no respondió, sin copiar el detalle de la excepción en la respuesta
al cliente.
```

### REQ-EMC-04 — ~~El Gateway local espera al emulador~~ (retirado)

> **Retirado el 24/09/2026 por `GW-TBD-30` (b).** El Gateway **no** debe esperar al emulador cuando
> se usa un proyecto real, y Compose no puede condicionar un `depends_on` al valor de una variable.
> Por eso no se añade `depends_on`. Si el Gateway recibe peticiones antes de que el emulador esté
> listo, `REQ-EMC-01` y `REQ-EMC-03` lo convierten en un `503` con un log que lo explica, en lugar de
> un `401` engañoso. El número no se reutiliza.

### REQ-EMC-05 — En modo emulador, el ID de proyecto del Gateway debe ser de demostración (problema 3)

```
Mientras el sistema corra en modo emulador, si FIREBASE_PROJECT_ID no empieza por "demo-", el
sistema no debe arrancar, y el mensaje de error debe nombrar FIREBASE_PROJECT_ID y
FIREBASE_AUTH_EMULATOR_HOST.
```

### REQ-EMC-06 — El ID de proyecto se anuncia al arrancar (problema 3)

```
Cuando el sistema arranque en modo emulador, debe registrar en el log el ID de proyecto con el que
verificará los tokens, para que se pueda comparar con el del emulador y el de cameia-cuentas.
```

### REQ-EMC-07 — Una sola variable decide el ID de proyecto en local (problema 3)

```
Mientras FIREBASE_EMULATOR_PROJECT_ID no esté definida, el emulador debe arrancar con el valor de
FIREBASE_PROJECT_ID si éste empieza por "demo-", y con demo-cameia en cualquier otro caso.
```

> Así, cambiar `FIREBASE_PROJECT_ID` en `.env` cambia los dos a la vez, y la regla `demo-` de
> CM-189 se conserva: el emulador nunca arranca con el ID de un proyecto real. Decidido en
> `GW-TBD-30` (a).

### REQ-EMC-08 — El modo emulador lo decide lo mismo que ve el SDK (problema 4)

```
El sistema debe decidir si entrega credenciales ficticias leyendo FIREBASE_AUTH_EMULATOR_HOST de las
variables de entorno del proceso, que es la única fuente que lee el Admin SDK, y no del Environment
de Spring.
```

### REQ-EMC-09 — Variable fuera del entorno del proceso (problema 4)

```
Si FIREBASE_AUTH_EMULATOR_HOST está en el Environment de Spring con valor y no está en las variables
de entorno del proceso, entonces el sistema no debe arrancar, y el mensaje de error debe explicar
que el Admin SDK solo lee la variable de entorno del proceso.
```

> Decidido en `GW-TBD-29`: fallar el arranque. El camino recomendado es Docker, donde el compose
> entrega la variable como variable de entorno y este caso no ocurre (`REQ-EMC-11`).

### REQ-EMC-10 — La guardia de despliegue no se debilita (problema 4)

```
Mientras exista K_SERVICE o esté activo el perfil prod, el sistema no debe arrancar si
FIREBASE_AUTH_EMULATOR_HOST aparece en el Environment de Spring o en las variables de entorno del
proceso, aunque su valor esté vacío.
```

> Es `REQ-EMU-02` de CM-190 ampliado a las dos fuentes: la guardia sigue siendo la más conservadora.

### REQ-EMC-11 — El README recomienda Docker (problema 4)

```
El README.md debe recomendar ejecutar el Gateway con docker compose en lugar de hacerlo desde el IDE
o con Maven local, y debe explicar que fuera de Docker FIREBASE_AUTH_EMULATOR_HOST tiene que ser una
variable de entorno del sistema operativo con valor localhost:9099.
```

## 3. Requisitos no funcionales

### REQ-NF-EMC-01 — Nada cambia sin el emulador

Sin `FIREBASE_AUTH_EMULATOR_HOST` en ninguna fuente, el arranque y las respuestas son las de hoy,
salvo `REQ-EMC-01`, que también aplica al servidor real de Firebase.

### REQ-NF-EMC-02 — Reglas del repositorio

`filter` sigue sin importar `config` ni `exception` (`AGENTS.md` §3); métodos de 20 líneas como
máximo (§7); la suite existente no se pone roja (§8).

---

## 4. Clarificaciones — Fase 2

| ID | Pregunta | Recomendación | Estado |
|---|---|---|---|
| `GW-TBD-29` | Problema 4: si la variable del emulador está en Spring (`-D`, argumento, YAML) pero no en el entorno del proceso, ¿el Gateway falla el arranque o avisa y sigue con credenciales de Google? | **Fallar el arranque** (`REQ-EMC-09`): quien la pone quiere el emulador, y seguir con Google solo cambia el síntoma por un `401` sin explicación | ✅ Cerrado 24/09/2026: **falla el arranque**, y el `README.md` recomienda Docker, donde el caso no ocurre (`REQ-EMC-11`) |
| `GW-TBD-30` | Problema 3 y `REQ-EMC-04`: ¿se acepta (a) que el emulador tome `FIREBASE_PROJECT_ID` por defecto cuando empieza por `demo-`, y (b) que el Gateway espere al emulador también cuando se usa un proyecto real? | **Sí a las dos.** (a) deja una sola variable que cambiar; (b) el emulador sube siempre con `up` y tarda segundos | ✅ Cerrado 24/09/2026: (a) **sí** (`REQ-EMC-07`); (b) **no**: sin `depends_on`, `REQ-EMC-04` retirado |

---

## 5. Trazabilidad requisito → prueba

| Requisito | Prueba prevista |
|---|---|
| `REQ-EMC-01`, `REQ-EMC-03` | `FirebaseAuthGlobalFilterTest`: `FirebaseAuthException` con causa de red → `503`, sin petición en `MockWebServer`, log con `X-Request-Id` |
| `REQ-EMC-02` | `FirebaseAuthGlobalFilterTest`: las pruebas de `401` existentes, sin cambios, más una con `FirebaseAuthException` sin causa de red |
| `REQ-EMC-04` | Retirado |
| `REQ-EMC-07` | Manual con `docker compose config` y `docker compose up`, registrada en el PR |
| `REQ-EMC-11` | Revisión del `README.md` en el PR |
| `REQ-EMC-05`, `REQ-EMC-06` | `FirebaseConfigTest` |
| `REQ-EMC-08`, `REQ-EMC-09`, `REQ-EMC-10` | `FirebaseConfigTest` (con una fuente de variables del proceso sustituible) |
| `REQ-NF-EMC-01`, `REQ-NF-EMC-02` | Suite completa en verde y `GatewayArchTest` |

---

## 6. Advertencias — fuera de alcance (problemas 2 y 5 de la revisión)

> ⚠️ **Advertencia 1 — Los usuarios del emulador y los de `cameia-cuentas` se desincronizan.**
> El emulador solo exporta sus usuarios al apagarse de forma ordenada (`--export-on-exit`). Un
> apagado brusco (reinicio del equipo, cierre forzado de Docker Desktop, `docker kill`) pierde los
> usuarios creados desde el último arranque, mientras la base de datos de Cuentas los conserva.
> `docker compose down -v` en este repositorio borra los usuarios del emulador pero no los de
> Cuentas, y reiniciar la base de Cuentas deja usuarios huérfanos en el emulador. El emulador genera
> `uid` aleatorios: ningún dato semilla de Cuentas con `uid` fijos coincidirá. Hasta que otra HU lo
> resuelva, quien reinicie uno de los dos debe reiniciar el otro. Con la comprobación de revocación
> activa, el síntoma es un `401` para usuarios que Cuentas sí tiene.

> ⚠️ **Advertencia 2 — Arrancar el Gateway desde el IDE.** `cameia-firebase-emulator:9099` solo se
> resuelve dentro de la red `cameia-net`. Fuera de Docker el valor debe ser `localhost:9099`, y
> debe definirse como **variable de entorno** de la configuración de ejecución, no como `-D` ni en
> un YAML (ver `REQ-EMC-09`). El `README.md` ya lo dice y recomienda Docker (`REQ-EMC-11`); falta
> en `.env.example` y en `docs/DOCKER-LOCAL.md`.
