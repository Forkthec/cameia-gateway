# Plan · CM-335 Rechazos de protocolo del Gateway

Spec: `spec.md` (misma carpeta). Rama `CM-335-errores-de-protocolo-previsibles` desde `origin/develop`. Un PR; sin dependencias. **BLOQUEADO por la decisión D-1 de Paula** para cualquier código de producción; la opción A (recomendada) no escribe código de producción.

## 1. Enfoque (opción A)

1. Una prueba con sockets reales que fija lo que el Gateway responde hoy a los rechazos de Netty y de WebFlux.
2. Documentación: tabla en `docs/errores.md`, ADR 0003 y aviso a Frontend.

## 2. Reutilización

| Se reutiliza | Por qué |
|---|---|
| `TestFirebaseConfig`, perfil de prueba `test` y las propiedades de `GlobalCorsConfigTest` | Mismo contexto completo con puerto aleatorio, sin Firebase real |
| Estructura de `docs/errores.md` («Respuestas sin código») | Es el lugar previsto para esto |
| `RawHttpClient` | No existe en el Gateway; se crea una copia pequeña de pruebas (unas 60 líneas) porque `WebTestClient` y `java.net.URI` rechazan o normalizan las peticiones malas |

## 3. Archivos

| Acción | Ruta |
|---|---|
| crear | `src/test/java/tech/cameia/gateway/server/RawHttpClient.java` |
| crear | `src/test/java/tech/cameia/gateway/server/NettyDecodingRejectionsTest.java` |
| modificar | `docs/errores.md` |
| crear | `docs/adr/0003-rechazos-de-protocolo-del-gateway.md` |
| modificar | `docs/bitacora-ia/README.md` y entrada nueva (siguiente número) |

No se tocan: código de producción, `application.yml`, rutas, filtros, `.github/`.

## 4. Commits

| Tarjeta | Commit |
|---|---|
| T1 | `CM-335 \| test(gateway): pruebas con sockets de los rechazos de protocolo` |
| T2 | `CM-335 \| docs(gateway): rechazos sin código documentados, ADR 0003 y bitácora` |

## 5. Riesgos

| Riesgo | Mitigación |
|---|---|
| El límite exacto de encabezado/URL cambia con la versión | La prueba usa 9000 bytes (por encima) y 7000 (por debajo); si cambia, falla y se revisa |
| La conexión se cierra antes de leer la respuesta (Netty cierra tras el rechazo) | `RawHttpClient` lee hasta el cierre y trata la falta de respuesta como fallo explícito de la prueba |
| Paula elige B o C | Se detiene este plan y se escribe el plan de B con el nombre real de los manejadores (`reactor.left.httpCodec`, `reactor.left.httpTrafficHandler`) |
