# Spec — CM-14-verificacion-correo: el Gateway propaga el estado de verificación del correo

- **HU asociada:** CM-14 — Registro de usuario (continuación)
- **Sprint:** 1
- **Fecha:** 18/09/2026
- **Estado:** Fases 1 y 2 cerradas con el contrato de Cuentas y la decisión de Juan Vela del 18/09/2026
- **Flujo SDD:** Fase 1 — Requisitos EARS
- **Rama:** `CM-14-verificacion-correo` → `develop` (creada desde `CM-14-registro-usuario`)
- **Alcance:** **solo el Gateway.** La activación de la cuenta, el `403` y la tabla `cuenta` son de
  cameia-cuentas
- **Fuentes:**
  `../../cameia-cuentas/CONTRATO-GATEWAY-CM-14.md` (17/09/2026, de cameia-cuentas al Gateway),
  `specs/CM-14-Registro-usuario/spec.md` (`GW-TBD-17`),
  `AGENTS.md` §5, §6.1 y §6.4.

---

## 1. Contexto

`specs/CM-14-Registro-usuario/` dejó `GW-TBD-17` abierto en un punto: cuál es la ruta que un usuario
sin correo verificado sí puede usar. El contrato de Cuentas la fija:

```text
POST /api/v1/users/me/verification      → Caso A, cuerpo vacío
```

Cuentas decide la activación con el claim `email_verified` del token, **que hoy no llega**: el
contrato de cabeceras de `AGENTS.md` §6.4 no incluye ningún campo con ese dato, así que Cuentas lo
trata como no probado y responde `403` a todo el mundo.

El contrato planteaba dos salidas: que el Gateway propague el claim, o que el Gateway rechace con
`403`. **Decisión de Juan Vela, 18/09/2026: propagar** (`GW-TBD-26`). Cuentas ya implementa esa
opción, y es la única que funciona hoy: con la otra, Cuentas seguiría respondiendo `403` porque el
encabezado nunca llegaría.

### Lo que este spec NO hace

**No bloquea ninguna ruta.** La regla de "sin correo verificado no se puede hacer nada salvo la ruta
de verificación" es autorización dentro del Gateway (`AGENTS.md` §6.1, paso 3), afecta a todas las
rutas de Caso A y a las pruebas existentes, y el contrato la plantea como futura, no como acordada.
`GW-TBD-17` sigue abierto en esa parte. Quien la implemente debe exceptuar
`POST /api/v1/users/me/verification`: es justamente la ruta para salir de ese estado.

---

## 2. Requisitos funcionales — notación EARS

Numeración propia: `REQ-VER-NN`.

### REQ-VER-01 — El estado de verificación llega al microservicio

```
Cuando el sistema reenvía una solicitud de Caso A cuyo token trae el claim `email_verified`,
el sistema debe propagar su valor en la cabecera `X-User-Email-Verified`.
```

### REQ-VER-02 — Un valor falso también se propaga

```
Cuando el claim `email_verified` vale `false`,
el sistema debe propagar la cabecera con ese valor y no debe omitirla.
```

> Es la diferencia con `X-User-Plan`, que se omite cuando el claim falta. Aquí `false` es un dato,
> no una ausencia: Cuentas lo necesita para responder `403` con conocimiento de causa.

### REQ-VER-03 — Sin claim no se inventa un valor

```
Cuando el token no trae el claim `email_verified`,
el sistema no debe enviar la cabecera `X-User-Email-Verified`.
```

> El Gateway no interpreta claims (`REQ-08` de CM-104-correcciones). Para Cuentas, la ausencia de la
> cabecera significa verificación no probada, y responde `403`.

### REQ-VER-04 — El cliente no puede declararse verificado

```
Cuando el cliente envía la cabecera `X-User-Email-Verified`,
el sistema debe eliminarla antes de reenviar, tanto en Caso A como en Caso B.
```

> Sin esto, cualquiera activaría su cuenta enviando una cabecera. Es el mismo riesgo que `REQ-07`
> de CM-104-correcciones, y se resuelve igual: la cabecera entra en `IDENTITY_HEADERS`.

### REQ-VER-05 — La ruta de verificación es Caso A

```
Cuando llega una solicitud POST a `/api/v1/users/me/verification`,
el sistema debe exigir un ID Token de Firebase válido y reenviarla a cameia-cuentas
con las cabeceras de identidad de §6.4.
```

> No hace falta código ni ruta YAML nueva: `Path=/api/v1/users/**` ya la cubre y Caso A es el
> comportamiento por omisión. Se escribe para que quede con prueba y para que nadie la agregue a
> `PUBLIC_ROUTES` por descuido.

### REQ-VER-06 — Las respuestas `application/problem+json` pasan intactas

```
Cuando cameia-cuentas responde con tipo de contenido `application/problem+json`,
el sistema debe devolver al cliente el mismo estado, el mismo cuerpo y el mismo tipo de contenido.
```

> Precisión nueva del contrato de Cuentas sobre `REQ-REG-09`.

---

## 3. Requisitos no funcionales

### REQ-NF-VER-01 — Sin clases nuevas

```
El sistema debe implementar este spec dentro de `FirebaseAuthGlobalFilter`,
sin crear clases ni paquetes nuevos.
```

### REQ-NF-VER-02 — La suite corre sin credenciales

```
Cuando se ejecuta `docker compose run --rm verify`,
el sistema debe ejecutar las pruebas de este spec sin credenciales de Firebase ni de Google.
```

---

## 4. Clarificaciones

| ID | Pregunta | Estado | Respuesta | Quién decide |
|---|---|---|---|---|
| `GW-TBD-26` | ¿El Gateway propaga `email_verified` o rechaza con `403`? | ✅ Cerrado 18/09/2026 | **Propagar** en `X-User-Email-Verified`. Cuentas ya implementa esa opción y decide el `403` | Juan Vela |
| `GW-TBD-17` | ¿Qué puede hacer un usuario con `email_verified=false`? | 🟡 Parcial | **Cerrado:** la ruta permitida es `POST /api/v1/users/me/verification`. **Abierto:** si el Gateway bloquea el resto de rutas de Caso A, con qué código de error, y desde qué spec | Juan Vela |

---

## 5. Fuera de alcance

| Tema | Por qué |
|---|---|
| Bloquear rutas de Caso A sin correo verificado | `GW-TBD-17`, ver §1 |
| El `403` de Cuentas y la activación de la cuenta | cameia-cuentas |
| Que el claim `plan` respalde derechos o cuota | Sin spec de planes (contrato §4) |
| Enviar o reenviar el correo de verificación | Frontend y Firebase (`GW-TBD-16`) |

---

## 6. Criterio de terminado (DoD)

- [x] `email_verified=true` llega a Cuentas como `X-User-Email-Verified: true` (prueba de contrato) — `emailVerifiedClaim_isPropagatedToDownstream[1]`
- [x] `email_verified=false` llega como `false`, no se omite (prueba) — `emailVerifiedClaim_isPropagatedToDownstream[2]`; comprobado con una mutación: omitir el `false` pone roja esa prueba y `clientEmailVerifiedHeader_isReplacedByTokenClaim`
- [x] Sin el claim, la cabecera no se envía (prueba) — `tokenWithoutEmailVerifiedClaim_omitsHeader`
- [x] Un `X-User-Email-Verified` del cliente se reemplaza en Caso A y se borra en Caso B (prueba) — `clientEmailVerifiedHeader_isReplacedByTokenClaim` (un solo valor, el del token) y `publicRoute_dropsClientEmailVerifiedHeader`
- [x] `POST /api/v1/users/me/verification` sin token responde `401`; con token válido llega a Cuentas con el contrato de §6.4 (prueba) — `verificationRoute_withoutToken_returns401` y `verificationRoute_withValidToken_reachesAccountsWithIdentityContract`
- [x] Una respuesta `application/problem+json` llega al cliente con estado, cuerpo y tipo de contenido intactos (prueba) — `downstreamProblemJson_isReturnedUnchanged`
- [x] `docker compose run --rm verify` en verde, sin credenciales — `docker compose run --rm verify` 18/09/2026: `Tests run: 70, Failures: 0`, `BUILD SUCCESS`; `mvn clean compile test-compile -Xlint:all` sin advertencias
- [x] `AGENTS.md` §4, §5 y §6.4, y `docs/COMO-FUNCIONA.md`, incluyen la cabecera nueva — 18/09/2026
- [x] `specs/NUMERACIONES.md` con `GW-TBD-26` y el estado de `GW-TBD-17` — 18/09/2026
- [ ] Aviso a cameia-entrevista: §6.4 cambia para las tres partes a la vez
- [x] Bitácora IA del mismo día — `Entregables/18092026_BitacoraIA_Codigo_E2.md`
- [ ] Título del PR: `CM-14 | feat(gateway): propagar el estado de verificación del correo [IA-ASISTIDO]`
