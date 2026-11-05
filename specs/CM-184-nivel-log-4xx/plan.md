# Plan — CM-184-nivel-log-4xx: diseño técnico

- **Spec de referencia:** `specs/CM-184-nivel-log-4xx/spec.md`
- **Fecha:** 19/09/2026
- **Flujo SDD:** Fase 3 — Plan técnico

---

## 1. Principios que no cambian

- Un solo archivo de producción: `GlobalErrorHandler` (`REQ-NF-LOG-01`).
- El cuerpo y el estado de la respuesta salen del `CATALOG` como hasta hoy (`REQ-LOG-04`).
- Lo que el cliente escribe no entra al log (`REQ-LOG-05`), igual que hoy no entra al cuerpo.
- Métodos de menos de 20 líneas y máximo 3 parámetros (`AGENTS.md` §7); mensajes de log y Javadoc
  en español, identificadores en inglés (`AGENTS.md` §2).

---

## 2. Cambio en `GlobalErrorHandler.handle` (`REQ-LOG-01` a `REQ-LOG-04`)

El estado se resuelve **antes** del log y el log se delega:

```java
        String requestId = resolveRequestId(exchange);
        HttpStatus status = resolveStatus(ex);
        // REQ-12 de CM-104-correcciones: el detalle va al log, nunca al cuerpo de la respuesta
        logFailure(requestId, status, ex);

        ErrorBody body = CATALOG.getOrDefault(status, DEFAULT_BODY);
```

`resolveStatus` no tiene efectos secundarios —lee la cadena de causas y devuelve un `HttpStatus`—,
así que adelantar su llamada no cambia el comportamiento. `handle` no crece: una línea se mueve y
otra se sustituye.

---

## 3. Dos métodos privados nuevos (`REQ-LOG-01` a `REQ-LOG-03`, `REQ-LOG-05`)

Van después de `resolveRequestId` y antes del Javadoc de `resolveStatus`, en el mismo orden en que
los llama `handle`.

```java
    private void logFailure(String requestId, HttpStatus status, Throwable ex) {
        if (status.is4xxClientError()) {
            logClientError(requestId, status, ex);
        } else {
            log.error("Fallo al procesar la solicitud [requestId={}]", requestId, ex);
        }
    }

    private void logClientError(String requestId, HttpStatus status, Throwable ex) {
        if (status == HttpStatus.NOT_FOUND) {
            log.info("Solicitud a una ruta inexistente [requestId={}]", requestId);
        } else {
            log.warn("Solicitud rechazada con error del cliente [requestId={}, estado={}, tipo={}]",
                    requestId, status.value(), ex.getClass().getSimpleName());
        }
    }
```

Decisiones de diseño:

| Decisión | Motivo |
|---|---|
| El mensaje del `5xx` es **idéntico** al actual | `GatewayErrorMappingTest.downstreamFailure_isLoggedWithRequestIdSentToDownstream` afirma sobre ese texto y no se toca (`REQ-NF-LOG-02`) |
| En `4xx` **no** se pasa `ex` al logger | Pasar la excepción es lo que imprime la traza, y la traza es lo que Cloud Logging clasifica como `ERROR` (spec §1.1) |
| Se registra `ex.getClass().getSimpleName()`, no `ex.getMessage()` | El nombre de la clase es texto del código; el mensaje de un `404` lleva la URL que escribió el cliente (`REQ-LOG-05`) |
| Dos métodos y no uno con tres ramas | Cada uno queda en 6 líneas y dos niveles de anidamiento (`AGENTS.md` §7) |
| `status.is4xxClientError()` en vez de comparar rangos a mano | Es la pregunta que hace el requisito, escrita igual |

---

## 4. Pruebas (`REQ-LOG-01` a `REQ-LOG-05`)

### 4.1 Clase nueva `GlobalErrorHandlerLoggingTest`

`src/test/java/tech/cameia/gateway/exception/GlobalErrorHandlerLoggingTest.java`, con
`@ExtendWith(OutputCaptureExtension.class)`. Es una prueba **unitaria**: instancia
`GlobalErrorHandler` y le pasa un `MockServerWebExchange`, sin levantar el contexto de Spring. Si al
ejecutarla se comprueba que la línea `INFO` no se imprime, se cambia a `@SpringBootTest` como
`GatewayErrorMappingTest` (`application-test.yml` deja `tech.cameia.gateway` en `DEBUG`).

| # | Prueba | Entrada | Aserciones | Requisito |
|---|---|---|---|---|
| 1 | `notFound_isLoggedAtInfoWithoutStackTrace` | `ResponseStatusException(404)` | contiene `Solicitud a una ruta inexistente [requestId=`; no contiene `ERROR`, ni `at org.`, ni `at java.` | REQ-LOG-02 |
| 2 | `clientError_isLoggedAtWarnWithoutStackTrace` | `ResponseStatusException(400)` | contiene `Solicitud rechazada con error del cliente [requestId=`, `estado=400` y `tipo=ResponseStatusException`; no contiene traza | REQ-LOG-03 |
| 3 | `serverError_isLoggedAtErrorWithStackTrace` | `RuntimeException` → `500` | contiene `ERROR`, `Fallo al procesar la solicitud [requestId=` y la traza | REQ-LOG-01 |
| 4 | `errorResponse_isUnchanged` | `404` | estado `404`, `Content-Type` JSON con `charset` y cuerpo exacto `{"code":"NOT_FOUND","message":"Recurso no encontrado"}` | REQ-LOG-04 |
| 5 | `clientErrorLog_doesNotLeakExceptionMessage` | `404` con mensaje `No static resource ../../etc/passwd for request 'http://externo'` | ese texto no aparece en la salida | REQ-LOG-05 |

El `X-Request-Id` se fija en la solicitud simulada para poder afirmar sobre un valor conocido, en
vez de sobre el UUID que generaría `resolveRequestId`.

### 4.2 Caso de extremo a extremo en `GatewayErrorMappingTest`

Se **añade** una prueba, no se modifica ninguna (`REQ-NF-LOG-02`): una petición a una ruta que no
existe debe responder `404` y dejar en el log la línea `INFO` con el `X-Request-Id` que devuelve la
respuesta, sin que aparezca `NoResourceFoundException`. Es la prueba que demuestra el efecto real
sobre el ruido de Cloud Logging, porque usa la excepción que Spring lanza de verdad.

---

## 5. Documentación a actualizar

| Archivo | Qué cambia |
|---|---|
| `AGENTS.md` §4 | Fila de `GlobalErrorHandler`: el nivel depende del estado |
| `AGENTS.md` §8 | Número de pruebas de la suite |
| `docs/COMO-FUNCIONA.md` §5.4 | La nota que hoy dice que todo error pasa por `ERROR` con traza |
| `specs/CM-104-correcciones/spec.md` | Nota de enmienda en el segundo bloque de `REQ-12` (Spec Anchored, `AGENTS.md` §0.1 fase 7) |
| `specs/NUMERACIONES.md` | `GW-TBD-27`, la carpeta del spec y sus rangos `REQ-LOG` / `T-NN` |

---

## 6. Riesgos

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| La prueba unitaria no ve la línea `INFO` porque otra prueba reconfiguró logback antes | Media | Se ejecuta la suite completa, no solo la clase nueva; si falla, se pasa a `@SpringBootTest` (plan §4.1) |
| Alguien vuelve a pasar `ex` al logger en el `4xx` "para tener más contexto" | Media | Las pruebas 1, 2 y 5 se ponen rojas: afirman que no hay traza ni mensaje |
| Un `5xx` deja de registrar la traza por un cambio posterior | Baja | Prueba 3 y la existente del `504` |
| Se pierde información útil de un `404` real del frontend | Baja | Queda la línea `INFO` con el `X-Request-Id`, que es la clave para correlacionar con el acceso HTTP de Cloud Run |
