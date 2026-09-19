# Spec — CM-184-nivel-log-4xx: el nivel del log depende del estado HTTP

- **HU asociada:** CM-184 — Bajar el nivel de log de los errores `4xx` esperados
- **Sprint:** 1
- **Fecha:** 19/09/2026
- **Estado:** Fases 1 y 2 cerradas con la decisión de Juan Vela del 19/09/2026 (`GW-TBD-27`)
- **Flujo SDD:** Fase 1 — Requisitos EARS
- **Rama:** `CM-184-nivel-log-4xx` → `develop` (creada desde `origin/develop`)
- **Alcance:** **solo `GlobalErrorHandler`.** Ni rutas, ni filtros, ni configuración de logging
- **Fuentes:**
  `19092026_arquitectura_solicitud-vela-gateway-nivel-log-4xx.md` (19/09/2026, de Paula Andrea
  Muñoz Delgado —DevOps/Tester— a Backend),
  `specs/CM-104-correcciones/spec.md` (`REQ-11`, `REQ-12`),
  `AGENTS.md` §4, §7 y §8.

---

## 1. Contexto

`GlobalErrorHandler.handle` registra **toda** excepción con `log.error(mensaje, requestId, ex)`
antes de calcular el estado (`GlobalErrorHandler.java:92`; el estado se resuelve en la línea 94).
Pasar `ex` al logger imprime la traza completa, así que un `404` de un escáner de internet deja en
el log la misma huella que una caída real del Gateway.

Cifras verificadas por DevOps el 19/09/2026, 19:25 UTC, proyecto GCP `cameia-e245f`, con
`gcloud logging read` sobre los últimos 7 días:

| Medida | Valor |
|---|---|
| Entradas `severity>=ERROR` en los cuatro servicios | 447 |
| De ellas, del Gateway | 410 |
| De ellas, `NoResourceFoundException` (rutas inexistentes) | 402 — el **90%** del total |

El origen es tráfico esperado de internet: 290 de las 397 del primer corte llegaron con `Host`
igual a la IP del balanceador (`8.233.62.211`), pidiendo `index.php`, `favicon.ico`,
`html/admin/config.php`, `remote/login` y similares. No es un defecto de CAMEIA.

### 1.1 Por qué lo que importa es la traza, no el nivel

Los logs del Gateway salen como texto plano por stdout. DevOps observó que las líneas de cabecera
(`ERROR 1 --- ... Fallo al procesar la solicitud`) llegan a Cloud Logging con severidad vacía
(`DEFAULT`) —200 de 200 en su muestra—, mientras que las entradas con `severity=ERROR` son **las
trazas de pila** —40 de 40 en su muestra—. Spring Boot 4.1.1 no trae formato estructurado de Google
Cloud (solo `ecs`, `gelf` y `logstash`).

Consecuencia para este spec: **lo que elimina el ruido en Cloud Logging es dejar de imprimir la
traza en los `4xx`**. El nivel `INFO`/`WARN`/`ERROR` sigue siendo útil para leer el log y filtrar
por texto, pero no se afirma que corresponda a la severidad de Cloud Logging, y el criterio de
terminado no lo usa como tal.

### 1.2 Por qué importa

- Un error real queda enterrado: la revisión `cameia-cuentas-00004` no arrancó el 19/09/2026 a las
  14:09 UTC y su error convivía con más de 400 entradas de escáneres.
- Hoy no existe ninguna alerta ni métrica basada en logs (`gcloud monitoring policies list` y
  `gcloud logging metrics list` devuelven `[]`). DevOps va a crear una sobre `severity>=ERROR`, y
  solo sirve si `ERROR` significa error real.

### 1.3 El patrón ya existe en el repositorio

`FirebaseAuthGlobalFilter` registra un rechazo esperado en una sola línea, sin traza y con el
`X-Request-Id`:

```java
log.warn("Solicitud rechazada por autenticación [requestId={}]", requestId);
```

Este spec aplica la misma idea a `GlobalErrorHandler`.

### Lo que este spec NO hace

**No cambia ninguna respuesta HTTP.** Ni el estado, ni el cuerpo, ni el `CATALOG`, ni
`resolveStatus`, ni `networkFailureStatus`. Un cliente no puede notar la diferencia: el cambio se
ve solo en el log.

---

## 2. Requisitos funcionales — notación EARS

Numeración propia: `REQ-LOG-NN`.

### REQ-LOG-01 — Un error del servidor conserva su traza

```
Mientras el sistema responde un error con estado 5xx, debe registrar la excepción original,
con su traza y su `X-Request-Id`, a nivel ERROR.
```

> Es lo que hay que investigar. El texto del mensaje no cambia
> (`"Fallo al procesar la solicitud [requestId={}]"`) para que la prueba existente del `504`
> siga en verde sin tocarla.

### REQ-LOG-02 — Una ruta inexistente deja una sola línea

```
Cuando ninguna ruta coincide con la URL (estado 404), el sistema debe registrar una sola línea
a nivel INFO con el `X-Request-Id`, sin traza, sin la URL y sin el mensaje de la excepción.
```

> `INFO` y no `WARN` (opción 1 de la solicitud, `GW-TBD-27`): los cerca de 400 `404` de escáneres
> que se acumulan en cinco días llenarían `WARN` y le quitarían valor como señal.

### REQ-LOG-03 — Otro error del cliente deja una línea con su estado

```
Mientras el sistema responde un error 4xx distinto de 404, debe registrar una sola línea a nivel
WARN con el `X-Request-Id`, el estado y el tipo de excepción, sin traza y sin el mensaje de la
excepción.
```

> Un `4xx` que no es un `404` sí merece atención: puede ser el frontend llamando mal. Se registra
> el **tipo** de la excepción (`getClass().getSimpleName()`), que es texto del código, no del
> cliente.

### REQ-LOG-04 — Ninguna respuesta cambia

```
El sistema no debe cambiar el estado ni el cuerpo de ninguna respuesta de error.
```

> `REQ-11` y el primer bloque de `REQ-12` de `CM-104-correcciones` quedan exactamente igual. El
> segundo bloque de `REQ-12` queda enmendado por este spec.

### REQ-LOG-05 — El log de un `4xx` no repite texto del cliente

```
Mientras el sistema registra un error 4xx, no debe incluir en el log la URL, la ruta, la query,
las cabeceras ni el mensaje de la excepción.
```

> Un `404` de Spring trae el mensaje `No static resource <lo que pidió el cliente> for request
> '<URL del cliente>'`. Es texto controlado por quien hace la petición: no entra al log sin
> control. Es la misma regla que `REQ-12` aplica al cuerpo de la respuesta, llevada al log.

---

## 3. Requisitos no funcionales

### REQ-NF-LOG-01 — Sin clases nuevas de producción

```
El sistema debe implementar este spec dentro de `GlobalErrorHandler`, sin crear clases ni
paquetes nuevos de producción y sin tocar la configuración de logging.
```

> `application.yml`, `application-prod.yml` y cualquier `logback*.xml` quedan como están: bajar el
> nivel global o añadir filtros es competencia de DevOps, no del código.

### REQ-NF-LOG-02 — Las pruebas existentes no se modifican

```
Cuando se ejecuta la suite completa, todas las pruebas anteriores a este spec deben pasar sin
haber sido modificadas.
```

> En particular `errorBodies_doNotLeakInternalDetail` y
> `downstreamFailure_isLoggedWithRequestIdSentToDownstream` de `GatewayErrorMappingTest`.

---

## 4. Clarificaciones

| ID | Pregunta | Estado | Respuesta | Quién decide |
|---|---|---|---|---|
| `GW-TBD-27` | ¿Qué nivel recibe cada estado: `404` en `INFO` y el resto de `4xx` en `WARN` (opción 1), todo `4xx` en `WARN` (opción 2), o no tocar el código y silenciar el grupo en Error Reporting (opción 3)? | ✅ Cerrado 19/09/2026 | **Opción 1.** Separa lo esperado (escáneres) de lo que merece atención y deja `WARN` utilizable como señal | Juan Vela |
| `GW-TBD-10` | Estados fuera del catálogo (`400`, `405`, `415` → cuerpo `INTERNAL_ERROR`) | Abierto, ver `CM-104-correcciones` | Fuera de alcance aquí: este spec no toca el `CATALOG` (`REQ-LOG-04`) | Juan Vela |

---

## 5. Fuera de alcance

| Tema | Por qué |
|---|---|
| El cuerpo `INTERNAL_ERROR` de un `400`, `405` o `415` | `GW-TBD-10`, tarea aparte. Este spec no toca el `CATALOG` |
| `OidcSigningGlobalFilter` (`log.error` de fallo de firma saliente) | Es un error real de servidor y debe seguir en `ERROR` |
| `FirebaseAuthGlobalFilter` (`log.warn` del `401`) | Ya cumple el patrón |
| Configuración de logging y filtros de Cloud Logging | Competencia de DevOps (`REQ-NF-LOG-01`) |
| Alertas y métricas sobre `severity>=ERROR` | Las crea DevOps después de este cambio |
| Otros repositorios | La solicitud es solo de `cameia-gateway` |

---

## 6. Criterio de terminado (DoD)

- [x] Un `5xx` sigue registrando la traza a nivel `ERROR` con su `X-Request-Id` (prueba) — `REQ-LOG-01`: `serverError_isLoggedAtErrorWithStackTrace`, y la existente `downstreamFailure_isLoggedWithRequestIdSentToDownstream` del `504` sigue en verde sin tocarla
- [x] Un `404` deja una sola línea `INFO` con el `X-Request-Id`, sin traza (prueba) — `REQ-LOG-02`: `notFound_isLoggedAtInfoWithoutStackTrace`
- [x] Un `4xx` distinto de `404` deja una sola línea `WARN` con estado y tipo, sin traza (prueba) — `REQ-LOG-03`: `clientError_isLoggedAtWarnWithoutStackTrace`
- [x] El estado y el cuerpo de un `404` no cambian: `{"code":"NOT_FOUND","message":"Recurso no encontrado"}` (prueba) — `REQ-LOG-04`: `errorResponse_isUnchanged`, que además fija el `charset` de ASVS 4.1.1
- [x] El mensaje de la excepción de un `404` no aparece en el log (prueba) — `REQ-LOG-05`: `clientErrorLog_doesNotLeakExceptionMessage`
- [x] Prueba de extremo a extremo en `GatewayErrorMappingTest`: una ruta inexistente deja la línea `INFO` con el `X-Request-Id` de la respuesta y el log **no** contiene `NoResourceFoundException` — `unknownRoute_isLoggedAtInfoWithoutStackTrace`, pidiendo `/index.php` como los escáneres reales
- [x] `docker compose run --rm verify` en verde, sin credenciales, con las pruebas anteriores intactas — `REQ-NF-LOG-02`: 19/09/2026, `Tests run: 79, Failures: 0, Errors: 0`, `BUILD SUCCESS`. En `develop` sin estos cambios eran 73
- [x] `AGENTS.md` §4 (fila de `GlobalErrorHandler`) y §8 (número de pruebas) actualizados — 19/09/2026
- [x] `docs/COMO-FUNCIONA.md` §5.4 con la regla de niveles — 19/09/2026; el aviso que decía que todo error sale en `ERROR` con traza queda tachado, no borrado
- [x] Nota de enmienda en el segundo bloque de `REQ-12` de `specs/CM-104-correcciones/spec.md` — 19/09/2026
- [x] `specs/NUMERACIONES.md` con `GW-TBD-27` y las numeraciones de este spec — 19/09/2026
- [x] Bitácora IA del mismo día (`AGENTS.md` §10) — `../../Entregables/19092026_BitacoraIA_Codigo_E2.md`
- [x] Respuesta a DevOps con el formato de la §6 de la solicitud — redactada en `specs/CM-184-nivel-log-4xx/tasks.md` (T-14); falta enviarla con el enlace del PR
- [ ] Título del PR: `CM-184 | fix(gateway): bajar el nivel de log de los errores 4xx esperados [IA-ASISTIDO]`
