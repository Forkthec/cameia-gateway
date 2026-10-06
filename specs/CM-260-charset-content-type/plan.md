# Plan — CM-260: charset en las respuestas JSON del Gateway

Base: `origin/develop` `4c79b6e`. Un solo bloque, un solo PR (≈ 40 líneas de diff). Estado: pendiente de aprobación de Paula.

## 1. Enfoque

1. Medir primero (T1): una prueba desechable imprime el `Content-Type` real de las respuestas de actuator y confirma el defecto del `401`. Responde la pregunta 3 de la spec con salida real.
2. Corregir el filtro con una constante propia (D1).
3. Probar cada camino de `401` del filtro y cada estado del catálogo del manejador.
4. Fijar la regla de arquitectura (D2).

## 2. Archivos

| Acción | Ruta | Qué |
|---|---|---|
| Modificar | `src/main/java/tech/cameia/gateway/filter/FirebaseAuthGlobalFilter.java` | Constante `APPLICATION_JSON_UTF8`; línea 460 la usa; Javadoc de `writeUnauthorized` (líneas 436-450) dice que declara charset UTF-8 |
| Modificar | `src/test/java/tech/cameia/gateway/filter/FirebaseAuthGlobalFilterTest.java` | Aserción de `Content-Type` en los cinco tests de 401 (líneas 257, 291, 311, 328, 345) |
| Modificar | `src/test/java/tech/cameia/gateway/exception/GlobalErrorHandlerLoggingTest.java` | Prueba parametrizada por estado (junto a `errorResponse_isUnchanged`, línea 100) |
| Crear | `src/test/java/tech/cameia/gateway/arch/ContentTypeArchTest.java` | Regla `productionCodeDoesNotUseBareApplicationJson`, sin analizar las clases de prueba |
| No tocar | `GlobalErrorHandler.java`, `application*.yml`, `pom.xml`, `Dockerfile`, `.github/` | Fuera de alcance |

## 3. Reutiliza / por qué no se extrae

- `GlobalErrorHandler.APPLICATION_JSON_UTF8` ya hace lo necesario pero es privada y `AGENTS.md` §3 prohíbe que `filter` importe `exception`. Se repite la declaración de una línea (2 usos; la regla de tres pide extraer a la tercera). El 403 de CM-179 será el tercer uso **dentro del filtro**, que comparte la constante del filtro.
- `GlobalErrorHandlerLoggingTest.handle(...)` (método auxiliar existente) y `MockServerWebExchange` para la prueba parametrizada; no se crean dobles nuevos.

## 4. Decisiones técnicas y descartes

Ver spec §8 (D1–D3). Añadir ArchUnit no agrega dependencia: ya está en `pom.xml` y `GatewayArchTest` ya lo usa.

## 5. Riesgos

| Riesgo | Mitigación |
|---|---|
| `accessField(MediaType.class, "APPLICATION_JSON")` no detecta el acceso porque el compilador lo inlinea | `MediaType.APPLICATION_JSON` es un campo `static final` de tipo objeto: no se inlinea. La tarjeta T4 lo comprueba haciendo fallar la regla a propósito antes de dejarla (ver tarjeta) |
| Actuator responde sin charset (pregunta 3) | T1 lo mide; si falla, se detiene el bloque y se pregunta; no se improvisa |
| Prueba existente espera `application/json` exacto | Se buscó con `git grep`: ninguna; si aparece, T3 la ajusta |

## 6. Matriz de pruebas

Es la tabla de la spec §10. Orden: T1 → T2 → T3 → T4 → T5. T2 y T3 son independientes entre sí.

## 7. Bloques

Un solo bloque. Estimación honesta: 1 h (3 tarjetas de ≤ 15 min, 2 de ≤ 10 min). No hay dependencias con otras tareas; CM-179 bloque 3 se beneficia de que esta ya esté fusionada.
