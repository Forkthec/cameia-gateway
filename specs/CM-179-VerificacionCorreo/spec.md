# Spec — CM-179-VerificacionCorreo (Gateway): 403 a todo token con el correo sin verificar

- **Tarea:** CM-179 · Subtarea «HU-1.2 – Backend: verificación de correo», pieza 3 (la parte del Gateway). La spec de toda la tarea está en `cameia-cuentas/specs/CM-179-VerificacionCorreo/spec.md`; aquí están los requisitos que ejecuta el Gateway y sus decisiones.
- **Repositorio y base:** `cameia-gateway`, rama `CM-179-verificacion-correo-gateway`, `origin/develop` `53fbe30` (11-oct-2026).
- **Backlog vigente:** `09102026_01_Backlog_v6.xlsx`, hoja `HE-01`: CA-1.2.13 (principal), CA-1.2.6 (el control de Cuentas), RT-02 (CA03, CA04 y CA06).
- **Estado:** revalidada el 11-oct-2026 contra el backlog v6 y `develop`; **aprobada por Paula el 11-oct-2026** junto con la spec de Cuentas. Ya no está bloqueada: CM-260 está «Finalizado» y la constante `APPLICATION_JSON_UTF8` existe en `FirebaseAuthGlobalFilter.java:105`.
- **Puerta de listo: `LISTA PARA EJECUTAR`** (11-oct-2026; sección 16). Lista de verificación en la sección 15. En `develop`, `AGENTS.md` solo remite a `CLAUDE.md`, que tiene las reglas del repo.
- **Atributos de calidad:** seguridad (AC-0003: SEG-01, SEG-02, SEG-04), interacción (AC-0006: código estable), mantenibilidad (MAN-01), interoperabilidad (IOP-01: catálogo de errores).
- **Por qué es urgente:** es la «segunda barrera» que piden Testing y Frontend tras el defecto crítico DF-003 (`comunicaciones/recibidas/10102026_v1_aviso-testing-hu13-hu12.md` y `respuesta-df003-reenvio-verificacion.md`). Frontend ya bloquea en el cliente (`cameia-web` PR #70); el servidor todavía no.

## 1. Contexto

Hoy `FirebaseAuthGlobalFilter` verifica el ID Token y reenvía con `X-User-Email-Verified` igual al claim `email_verified`, **incluido `false`**, sin bloquear (`FirebaseAuthGlobalFilter.java:244`; `CLAUDE.md` del Gateway, líneas 129, 134 y 232, y `specs/NUMERACIONES.md`, `GW-TBD-17` «🟡 Parcial» y `GW-TBD-26` «propagar»). CA-1.2.13 dice: «Gateway (FirebaseAuthGlobalFilter): ID Token válido con email_verified=false → 403 Forbidden, sin reenviar la petición a ningún microservicio. Cuerpo: `{"code":"EMAIL_NOT_VERIFIED","message":"Tu correo aún no está verificado."}`, con `Content-Type: application/json; charset=UTF-8`. Excepción: `POST /api/v1/users/me/verification`. Si el claim email_verified no existe, no se bloquea.» Cuentas mantiene su propio control en la ruta de activación (CA-1.2.6): defensa en profundidad.

## 2. Alcance

Dentro: la decisión de bloqueo en `FirebaseAuthGlobalFilter`, su respuesta 403, sus pruebas, una carpeta de Postman con el emulador, y la documentación del repo (`CLAUDE.md`, `specs/NUMERACIONES.md`, `docs/errores.md`). Fuera: sección 8.

## 3. Requisitos funcionales (EARS)

- **REQ-EV-01 (CA-1.2.13).** Cuando el Gateway reciba una petición a una ruta privada (Caso A) con un ID Token que Firebase acepta y cuyo claim `email_verified` sea `false`, debe responder `403` con el cuerpo exacto `{"code":"EMAIL_NOT_VERIFIED","message":"Tu correo aún no está verificado."}` y `Content-Type` `application/json` con `charset=UTF-8`, **sin reenviar** la petición a ningún microservicio y sin pedir token OIDC.
- **REQ-EV-02.** Cuando la petición sea exactamente `POST /api/v1/users/me/verification`, el Gateway debe reenviarla aunque `email_verified` sea `false`, con `X-User-Email-Verified: false`. Cualquier otra combinación queda bloqueada: `GET /api/v1/users/me/verification`, `POST /api/v1/users/me/verification/` (barra final), `POST /api/v1/users/me` y una variante con mayúsculas o codificada.
- **REQ-EV-03.** Cuando el token no traiga el claim `email_verified`, el Gateway no debe bloquear y debe omitir la cabecera `X-User-Email-Verified` (comportamiento actual).
- **REQ-EV-04.** Cuando `email_verified` sea `true`, el Gateway debe reenviar con `X-User-Email-Verified: true` (comportamiento actual).
- **REQ-EV-05.** Mientras la ruta sea pública (`POST /api/v1/users`, `POST /webhooks/wompi` y, con el perfil `local`, las de salud de `DEV_PUBLIC_ROUTES`), el Gateway no debe evaluar el claim, y la cabecera `X-User-Email-Verified` del cliente se sigue borrando.
- **REQ-EV-06.** Cuando el cliente envíe `X-User-Email-Verified: true` con un token cuyo claim es `false`, el Gateway debe decidir por el claim: 403 en una ruta privada.
- **REQ-EV-07.** Cuando rechace por correo sin verificar, el Gateway debe registrar en `WARN` «Solicitud rechazada por correo sin verificar [code=EMAIL_NOT_VERIFIED, requestId={}]», sin token, correo, uid ni cabecera `Authorization`.
- **REQ-EV-08.** La respuesta 403 debe llevar el encabezado `X-Request-Id` que ya fija el filtro.
- **REQ-EV-09.** Mientras el token sea inválido, ausente, vacío o con otro esquema, el Gateway debe seguir respondiendo `401 AUTH_REQUIRED`: el 403 solo se evalúa con un token ya aceptado por Firebase.
- **REQ-EV-10.** `CLAUDE.md` debe documentar la regla y cerrar `GW-TBD-17`; `specs/NUMERACIONES.md` debe cerrar `GW-TBD-17` y marcar `GW-TBD-26` como sustituida; `docs/errores.md` debe tener la fila de `EMAIL_NOT_VERIFIED`.

**Sobre el `Content-Type`.** El CA lo escribe `application/json; charset=UTF-8` y Spring lo emite `application/json;charset=UTF-8`. Son el mismo valor: HTTP permite espacios antes de un parámetro. Las pruebas comparan el tipo y el charset ya interpretados, como las del 401 de CM-260. El caso de Testing que compare el texto debe aceptar ambas formas; se avisa con el despliegue.

## 4. Decisiones

| # | Decisión | Porqué | Alternativas descartadas | Decisión humana |
|---|---|---|---|---|
| D1 | La comprobación va dentro del `flatMap` posterior a `verifyIdToken` (línea 244), antes de `withIdentity` | Es el único punto donde el token ya está verificado y todavía no se reenvía nada; los errores del destino no se confunden con un token inválido | Un filtro nuevo (otro orden de ejecución y una segunda lectura del claim); decidir en cada microservicio | Técnica; aprobada por Paula con la spec (11-oct-2026) |
| D2 | «Sin verificar» = el claim existe y su texto es `false`; la ausencia no bloquea | Lo dice CA-1.2.13; reutiliza `readEmailVerified` (línea 404), que ya distingue ausencia de `false` | Tratar la ausencia como `false` (contradice el CA) | CA-1.2.13 |
| D3 | La excepción es exacta (método y ruta, sin normalizar) | Una coincidencia por prefijo o normalizada abriría rutas a cuentas sin verificar | Prefijo `/api/v1/users/me/**` | Paula, 6-oct-2026 |
| D4 | Una función privada `writeError(exchange, status, code, message)` escribe el 401 y el 403 | Dos usos con la misma forma `{code,message}` y el mismo `Content-Type`; evita duplicar la escritura | Copiar `writeUnauthorized` | Técnica; aprobada por Paula con la spec (11-oct-2026) |
| D5 | El código es `EMAIL_NOT_VERIFIED`, reservado por el estándar (`estandar-estricto.md` §A.5) | Es el código del contrato y lo cita el CA | Un código nuevo | Estándar de Backend |
| D6 | El `requestId` va solo en el encabezado `X-Request-Id`, no en el cuerpo | Formato vigente del Gateway (D-1 cerrada el 11-oct-2026; ADR 0001 del Gateway) | Agregarlo al cuerpo | Paula, 11-oct-2026 |

## 5. Contrato

- Un `403` nuevo en toda ruta privada para tokens con `email_verified=false`, con el formato del Gateway `{code,message}` (no `ProblemDetail`).
- Consumidor: `cameia-web`, que ante este 403 cierra la sesión local y lleva a «Iniciar sesión» con el aviso de CA-1.3.7 (RT-02-CA06). Frontend confirmó el 10-oct que resuelve los errores del Gateway por `code` (`comunicaciones/recibidas/10102026_v1_confirmacion-code-problemdetail-cm298_1.md`, sección 3); falta que confirme que aplica RT-02-CA06 a este código (aviso A3 de la spec de Cuentas).
- No cambia nada para tokens verificados, sin el claim ni para las rutas públicas.
- **Coordinación con Frontend (acción A7 de la spec de Cuentas):** hoy `cameia-web` deja una sesión sin verificar después del registro y lleva a `/inicio` (`useRegister`, `develop` `4b3f915`). Con este 403, esa sesión no puede leer ni escribir nada privado. Frontend debe mostrar «Verifica tu correo» sin iniciar sesión (CA-1.1.1) y aplicar RT-02-CA06; conviene desplegar su corrección antes o junto con esta pieza.

## 6. Seguridad y calidad

- ASVS 4.1.1 (charset), 8.2.1 y 8.2.2 (autorización por función; identidad solo de lo que firma el Gateway); OWASP API5. El rechazo ocurre antes de firmar con OIDC y de reenviar.
- Sin datos sensibles en logs (REQ-EV-07). Rendimiento: una comparación de texto.
- **Riesgo conocido:** un token emitido antes de verificar conserva `email_verified=false` hasta que Firebase lo renueva (≤ 1 h). Tras verificar, la persona inicia sesión de nuevo (CA-1.2.7 y 1.2.14) y obtiene un token nuevo, así que el flujo previsto no lo sufre.

| Atributo | Métrica | Límite | Prueba |
|---|---|---|---|
| AC-0003 | SEG-01 (ASVS 4.1.1, 8.2.2) | 100 % | Charset y bloqueo en `FirebaseAuthGlobalFilterTest` |
| AC-0003 | SEG-02 | 100 % de las rutas privadas probadas | Prueba parametrizada de 6 rutas; cabecera falsificada; Newman con el emulador |
| AC-0003 | SEG-04 | 0 datos personales en el log | `OutputCaptureExtension` |
| AC-0006 | `code` estable | `EMAIL_NOT_VERIFIED` | Cuerpo exacto |
| IOP-01 | Catálogo de errores | Fila en `docs/errores.md` | Revisión |
| MAN-01 | Cobertura de lo nuevo | ≥ 90 % de líneas y de ramas | JaCoCo (el repo está en 86,3 % de ramas global; la meta es sobre lo tocado) |

## 7. Pruebas (resumen; el detalle está en `tasks.md`)

En `FirebaseAuthGlobalFilterTest`, con `mockToken(uid, claims)`, `mockDownstream` y `webTestClient`: `false` en seis rutas privadas (`GET /api/v1/profiles/me`, `GET /api/v1/users/me`, `PUT /api/v1/profiles/123`, `POST /api/v1/interviews`, `GET /api/v1/users/me/verification`, `POST /api/v1/users/me/verification/`) → 403, cuerpo y charset exactos, `X-Request-Id`, ninguna petición al destino; la ruta exceptuada con `false` pasa; `true` pasa; claim ausente pasa (prueba existente); ruta pública con token `false` pasa; cabecera del cliente `true` con claim `false` → 403; el log no contiene token, correo ni uid; 401 sin cambios. Las existentes `emailVerifiedClaim_isPropagatedToDownstream`, `clientEmailVerifiedHeader_isReplacedByTokenClaim` y `verificationRoute_withValidToken_reachesAccountsWithIdentityContract` siguen en verde: usan la ruta exceptuada. Newman con el emulador de Firebase Auth del compose (CM-189 y CM-190): un usuario sin verificar recibe 403 en una ruta privada y no recibe 403 en la de verificación.

## 8. Fuera de alcance

El control de Cuentas (CA-1.2.6) · la purga y su Job · la configuración del correo (CM-255) · el flujo del navegador · las rutas públicas · `docs/estandar-backend.md`, que comparten los cuatro repos y dice que `EMAIL_NOT_VERIFIED` es un código reservado: lo alinea CM-296 (acción A5 de la spec de Cuentas).

## 9. Preguntas

| # | Pregunta | Estado |
|---|---|---|
| 1 | ¿`GW-TBD-26` («propagar») queda sin efecto frente a CA-1.2.13? | Cerrada sin consulta: CA-1.2.13 es posterior y explícito |
| 2 | ¿CM-260 antes de esta pieza? | Cumplida: CM-260 está «Finalizado» |
| 3 | ¿Excepción exacta? | Paula, 6-oct-2026: sí (D3) |
| 4 | ¿Mismo texto que el 403 de Cuentas? | Cerrada sin consulta: un código, un mensaje |

## 10. Estimación

≈ 3 h, ≈ 300 líneas con pruebas, Postman y documentación, en un PR hacia `develop`.

## 11. Trazabilidad CA → requisito → prueba → Postman (R12)

| CA | Requisito | Prueba (`FirebaseAuthGlobalFilterTest`, T-3.1) | Postman |
|---|---|---|---|
| CA-1.2.13: 403 sin reenviar, cuerpo y charset | REQ-EV-01, 08 | `unverifiedEmail_onPrivateRoute_returns403WithoutReachingDownstream` | `V-01` |
| CA-1.2.13: excepción de la ruta de verificación | REQ-EV-02 | `unverifiedEmail_onVerificationRoute_passesThroughWithFalseHeader`; seis rutas cercanas en la prueba anterior | `V-02` |
| CA-1.2.13: el claim ausente no bloquea | REQ-EV-03 | `tokenWithoutEmailVerifiedClaim_omitsHeader` (existente) | — (el emulador siempre emite el claim) |
| CA-1.3.1: token verificado pasa | REQ-EV-04 | `verifiedEmail_onPrivateRoute_passesThrough` | — |
| RT-02-CA05: ruta pública | REQ-EV-05 | `unverifiedEmail_onPublicRoute_isNotEvaluated` | — |
| CA-1.2.6 (defensa del lado del Gateway) | REQ-EV-06 | `clientHeaderSayingVerified_doesNotOverrideFalseClaim` | — |
| Privacidad (SEG-04) | REQ-EV-07 | `unverifiedRejection_isLoggedWithoutSensitiveData` | — |
| RT-02-CA03: 401 sin token | REQ-EV-09 | `invalidOrMissingToken_stillReturns401` | `V-03` |
| Documentación | REQ-EV-10 | Revisión del diff de `CLAUDE.md`, `specs/NUMERACIONES.md` y `docs/errores.md` | — |

## 12. Matriz de entradas (R2)

| Entrada | Valor | Resultado |
|---|---|---|
| `Authorization` | ausente, vacío, otro esquema, token rechazado | 401 `AUTH_REQUIRED` (sin cambio) |
| Claim `email_verified` | `false` en ruta privada | 403 `EMAIL_NOT_VERIFIED` |
| Claim `email_verified` | `false` en `POST /api/v1/users/me/verification` exacto | Reenvía con `X-User-Email-Verified: false` |
| Claim `email_verified` | `true` | Reenvía con `true` |
| Claim `email_verified` | ausente | Reenvía sin la cabecera |
| Cabecera `X-User-Email-Verified` del cliente | cualquier valor | Se borra; decide el claim |
| Ruta | `GET` a la ruta de verificación, barra final, `POST /api/v1/users/me`, mayúsculas | No exceptuada: 403 con claim `false` |

## 13. Plan de evidencia por regla R1–R14

| Regla | Qué la demuestra |
|---|---|
| R1 | Identificadores y pruebas en inglés; Javadoc y log en español |
| R2 | Sección 12, cada fila con su prueba |
| R3 | El 403 tiene código propio; la escritura no copia ningún texto de la petición |
| R4 | No aplica: el Gateway no tiene base de datos |
| R5 | Rutas cercanas a la exceptuada, cabecera falsificada, claim ausente, token inválido |
| R6 | ASVS 4.1.1, 8.2.1, 8.2.2 (IDs por confirmar contra la matriz de `cameia-infra`, PENDIENTE de Backend); OWASP API5; `/security-review` sin hallazgos abiertos |
| R7 | Sin clases nuevas; `writeError` compartido; `GatewayArchTest` en verde |
| R8 | Javadoc de cada función nueva; `CLAUDE.md`, `NUMERACIONES.md`, `docs/errores.md` |
| R9 | Las ocho pruebas de T-3.1 y la suite completa |
| R10 | ≥ 90 % de líneas y de ramas en lo nuevo de `FirebaseAuthGlobalFilter` |
| R11 | Carpeta «Correo sin verificar (emulador)», `V-00` a `V-03`, corrida con Newman |
| R12 | Sección 11 |
| R13 | Tabla de la sección 6 |
| R14 | Sin push, PR ni merge sin el sí de Paula; sin `[IA-ASISTIDO]` en commits |

## 14. Línea base

`./mvnw.cmd -B clean verify` (o `docker compose run --rm verify`) sobre la rama rebasada → BUILD SUCCESS, 114 pruebas, 0 fallos (medido sobre `53fbe30` el 11-oct-2026, `analisis/revisiones/hallazgos-cobertura_11oct2026.md`, fila C-3). Si no está en verde, el ejecutor se detiene y lo reporta.

## 15. Lista de verificación de la pieza

| # | Qué | Cómo | Esperado |
|---|---|---|---|
| G-01 | Línea base | Sección 14 | 0 fallos |
| G-02 | 403 en seis rutas privadas sin reenviar | Prueba 1 de T-3.1 | 403, cuerpo exacto, charset UTF-8, `X-Request-Id`, cero peticiones al destino |
| G-03 | La ruta de verificación pasa | Prueba 2; `V-02` | Reenvío con `false`; con el emulador, distinto de 403 y de 401 |
| G-04 | Token verificado y claim ausente pasan | Prueba 3 y `tokenWithoutEmailVerifiedClaim_omitsHeader` | Reenvío con `true` o sin cabecera |
| G-05 | Ruta pública intacta | Prueba 5 | Sin evaluar el token |
| G-06 | Adversarial: cabecera falsificada | Prueba 6 | 403 |
| G-07 | Log sin datos sensibles | Prueba 7 | Solo `requestId` y `code` |
| G-08 | 401 sin cambios | Prueba 8 y `V-03`; pruebas de charset de CM-260 | 401 `AUTH_REQUIRED` |
| G-09 | 403 real de punta a punta | `V-00` y `V-01` con el emulador | 403 y cuerpo exacto |
| G-10 | Cobertura y arquitectura | `clean verify`; `GatewayArchTest` | ≥ 90 % en lo nuevo; en verde |

## 16. Puerta de listo (comprobada el 11-oct-2026)

1. CA → prueba → Postman y atributos con métrica: secciones 11 y 6. Sí.
2. Entradas, casos borde y seguridad: secciones 12, 6 y 13. Sí.
3. Contraste con `origin/develop` `53fbe30`: constante de charset (línea 105), `flatMap` (línea 244), `readEmailVerified` (línea 404), `writeUnauthorized` (línea 460) y las pruebas nombradas existen. Sí.
4. Integración: un PR propio, sin archivos compartidos con Cuentas. Sí.
5. Línea base verde: sección 14. Sí.
6. Lista de verificación: sección 15. Sí.
7. Ninguna duda que bloquee: sección 9. Sí.

**`LISTA PARA EJECUTAR`.**
