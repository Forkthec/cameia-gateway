# CM-340 · Tareas

## T1 · Admitir `Idempotency-Key` en el CORS, con pruebas

**Objetivo:** que el preflight de `Idempotency-Key` se acepte y quede vigilado. **Cubre:** REQ-CORS-01 a 07.

**Archivos:** modificar `src/main/resources/application.yml` y `src/test/java/tech/cameia/gateway/config/GlobalCorsConfigTest.java`. No tocar nada más.

**Paso 1, pruebas primero (deben fallar).** En `GlobalCorsConfigTest` agregar un método auxiliar y las pruebas. Esqueleto:

```java
/** Preflight de un POST con los encabezados pedidos, como lo hace un navegador. */
private WebTestClient.ResponseSpec preflight(String origin, String path, String requestHeaders) {
    return webTestClient.options()
            .uri(path)
            .header(HttpHeaders.ORIGIN, origin)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, requestHeaders)
            .exchange();
}
```

| Prueba | Entrada | Esperado |
|---|---|---|
| `preflight_idempotencyKey_isAllowed` (`@ParameterizedTest`, rutas `/api/v1/interviews/sessions` y `/api/v1/interviews/sessions/abc/answers`; encabezado `idempotency-key` y `Idempotency-Key`) | origen `https://cameia.app` | `Allow-Origin` = `https://cameia.app`; `Allow-Headers` contiene `idempotency-key` sin distinguir mayúsculas |
| `preflight_existingHeaders_stillAllowed` | `authorization,content-type,x-request-id` | `Allow-Origin` presente y los tres en `Allow-Headers` |
| `preflight_allFourWebHeaders_inOneRequest` | `authorization,content-type,idempotency-key,x-request-id` | `Allow-Origin` presente |
| `preflight_userIdentityHeaders_areRejected` (`@ValueSource`: `x-user-id`, `x-user-plan`, `x-user-email-verified`) | idem | sin `Allow-Origin` |
| `preflight_unknownHeader_isRejected` | `x-otro` | sin `Allow-Origin` |
| `preflight_idempotencyKeyFromUnknownOrigin_isRejected` | origen `https://evil.example.com`, `idempotency-key` | sin `Allow-Origin` |

Estilo del repo: método `escenario_condicion_resultado` en inglés, comentarios y Javadoc en español, sin `CM-NNN`.
Las tres pruebas de rechazo: ejecutar primero y fijar el estado real; la aserción que manda es `doesNotExist(ACCESS_CONTROL_ALLOW_ORIGIN)`.

**Paso 2, comprobar en rojo:** `./mvnw.cmd test -Dtest=GlobalCorsConfigTest`. Deben fallar `preflight_idempotencyKey_isAllowed` y `preflight_allFourWebHeaders_inOneRequest`. Si pasan, detenerse y reportar.

**Paso 3, el cambio** (`application.yml`, bloque `allowedHeaders`):

```yaml
                allowedHeaders:
                  - Authorization
                  - Content-Type
                  - X-Request-Id      # el cliente puede aportarlo (REQ-09)
                  - Idempotency-Key   # clave de repetición que elige el cliente; no es una identidad
                # Sin X-User-*: solo el Gateway las emite (REQ-NF-01)
```

**Paso 4:** `./mvnw.cmd test -Dtest=GlobalCorsConfigTest` en verde, luego `./mvnw.cmd test` completo, con `GatewayArchTest` en verde.

**Trampas:** no agregar `*`; no agregar `exposedHeaders` (decisión D-1 = A); no cambiar `allowedOrigins`, `allowedMethods`, `allowCredentials` ni `maxAge`; si una prueba existente se rompe sin causa clara, detenerse.

**Detenerse y reportar si:** el rechazo de `x-user-id` hoy deja pasar el origen, o las rutas de la prueba no existen en el Gateway de prueba (el CORS es global, debería responder igual).

## T2 · Documentación

**Archivos:** `CLAUDE.md` (línea 151) y `docs/COMO-FUNCIONA.md` (líneas 422 y 428).

- `CLAUDE.md`: añadir que la lista incluye `Idempotency-Key` (clave de repetición elegida por el cliente, sin valor de identidad) y que sigue sin `X-User-*`.
- `COMO-FUNCIONA.md` línea 422: «Cabeceras admitidas: `Authorization`, `Content-Type`, `X-Request-Id`, `Idempotency-Key`».
- Línea 428: indicar que el rechazo de `X-User-*` ahora lo vigila una prueba automática.
- No tocar las líneas 460, 513 y 568 (`GW-TBD-10`, sigue abierto).

**Verificación:** lectura del diff; sin comandos. Terminado: dos archivos de documentación, sin referencias a `CM-340` en comentarios de código.

## Cierre (una sola vez, sobre la punta)

`./mvnw.cmd clean verify`; cobertura de lo tocado con JaCoCo (aquí el cambio es de configuración: sin líneas Java nuevas, se reporta 100 % de las pruebas nuevas ejecutadas); comprobación en el entorno (spec §8).
