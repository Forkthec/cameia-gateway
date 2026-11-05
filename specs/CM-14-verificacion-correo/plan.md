# Plan — CM-14-verificacion-correo: diseño técnico

- **Spec de referencia:** `specs/CM-14-verificacion-correo/spec.md`
- **Fecha:** 18/09/2026
- **Flujo SDD:** Fase 3 — Plan técnico

---

## 1. Principios que no cambian

- Sin clases nuevas: todo ocurre en `FirebaseAuthGlobalFilter` (`REQ-NF-VER-01`).
- El Gateway no interpreta claims: propaga el valor tal como viene.
- Toda cabecera que solo el Gateway emite se borra si llega del cliente.

---

## 2. Cambios en `FirebaseAuthGlobalFilter` (`REQ-VER-01` a `REQ-VER-04`)

```java
/** Estado de verificación del correo, del claim email_verified. Se omite si el claim falta. */
static final String X_USER_EMAIL_VERIFIED = "X-User-Email-Verified";

static final String EMAIL_VERIFIED_CLAIM = "email_verified";

private static final Set<String> IDENTITY_HEADERS = Set.of(
        X_USER_ID, X_USER_EMAIL, X_USER_ROLES, X_USER_PLAN, X_USER_EMAIL_VERIFIED   // ← nueva
);
```

En `withIdentity`, junto a las demás cabeceras opcionales:

```java
setIfPresent(h, X_USER_EMAIL_VERIFIED, readEmailVerified(token));
```

`readEmailVerified` copia la forma de `readPlan`: lee el claim del mapa y lo convierte a texto, o
devuelve `null` si no existe.

- **Se lee del mapa de claims, no de `FirebaseToken.isEmailVerified()`.** El método tipado devuelve
  `false` cuando el claim no existe, y eso convertiría una ausencia en una afirmación
  (`REQ-VER-03`).
- `setIfPresent` ya omite valores nulos o en blanco, y `"false"` no es ninguno de los dos, así que
  `REQ-VER-02` sale sin código extra. La prueba lo fija para que nadie lo cambie.
- Meter la cabecera en `IDENTITY_HEADERS` cubre `REQ-VER-04` en los dos casos: `withIdentity` y
  `withoutIdentity` ya recorren ese conjunto.

**Nada que hacer para `REQ-VER-05` y `REQ-VER-06`:** Caso A es el comportamiento por omisión,
`Path=/api/v1/users/**` ya enruta la ruta de verificación, y `GlobalErrorHandler` solo actúa ante
excepciones, así que no toca una respuesta `4xx` del destino ni su tipo de contenido. Las dos se
demuestran con prueba.

---

## 3. CORS

`allowedHeaders` **no** cambia: `X-User-Email-Verified` la emite el Gateway, no el navegador
(`AGENTS.md` §6.4). Admitirla en CORS sería invitar al cliente a mandarla.

---

## 4. Pruebas

Todas en `FirebaseAuthGlobalFilterTest`, con `MockWebServer` y `FirebaseAuth` simulado.

| # | Prueba | Requisito |
|---|---|---|
| 1 | `emailVerifiedClaim_isPropagatedToDownstream` (claim `true`) | REQ-VER-01 |
| 2 | `emailVerifiedFalse_isPropagatedAndNotOmitted` | REQ-VER-02 |
| 3 | `tokenWithoutEmailVerifiedClaim_omitsHeader` | REQ-VER-03 |
| 4 | `clientEmailVerifiedHeader_isReplacedByTokenClaim` (el cliente manda `true`, el token dice `false`; el destino recibe un solo valor: `false`) | REQ-VER-04 |
| 5 | `publicRoute_dropsClientEmailVerifiedHeader` (registro, Caso B) | REQ-VER-04 |
| 6 | `verificationRoute_withValidToken_reachesAccounts` y `verificationRoute_withoutToken_returns401` | REQ-VER-05 |
| 7 | `downstreamProblemJson_isReturnedUnchanged` (estado, cuerpo y `Content-Type`) | REQ-VER-06 |

La prueba 4 es la importante: se afirma con `values(...)`, no con `getHeader(...)`, porque este
último solo devuelve el primer valor y no detectaría una cabecera duplicada.

---

## 5. Documentación a actualizar

| Archivo | Qué cambia |
|---|---|
| `AGENTS.md` §4 | La descripción del filtro nombra la cabecera nueva |
| `AGENTS.md` §5 | Fila de `POST /api/v1/users/me/verification` |
| `AGENTS.md` §6.4 | Fila de `X-User-Email-Verified` en el contrato, con su regla de omisión |
| `AGENTS.md` §8 | Número de pruebas |
| `docs/COMO-FUNCIONA.md` | Tabla de lo que recibe el microservicio |
| `specs/NUMERACIONES.md` | `GW-TBD-26` y estado de `GW-TBD-17` |
| `specs/CM-14-Registro-usuario/` | T-00k cerrada con la ruta; `GW-TBD-17` apunta aquí |

---

## 6. Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| Un microservicio cree que la ausencia de la cabecera significa "verificado" | Media | El contrato de Cuentas ya dice lo contrario (`403`). Aviso a Entrevista: §6.4 cambia para las tres partes |
| Alguien "simplifica" a `isEmailVerified()` y convierte la ausencia en `false` | Media | La prueba 3 se pone roja de inmediato |
| El claim llega como texto (`"true"`) en vez de booleano | Baja | Se propaga tal cual, sin interpretar; Cuentas decide |
