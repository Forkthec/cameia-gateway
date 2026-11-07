# Plan · CM-357 Tiempo máximo de 120 s para las entrevistas

Spec: `spec.md` de esta carpeta. Base: `origin/develop` `ebb7e96`. Un PR, cuatro commits (el orden es el de lectura; cada uno compila).

## Qué mirar al revisar

| Archivo | Qué pide criterio | Qué es mecánico |
|---|---|---|
| `src/main/resources/application.yml` | Que `metadata.response-timeout` esté solo en la ruta `cameia-entrevista` y que el global siga en 30 s | El comentario de bloque |
| `config/RouteTimeoutsValidator.java` | Reglas de la tabla §7 de la spec y el recorte a 20 caracteres del valor en el mensaje | Javadoc |
| `RouteTimeoutsValidatorTest`, `RouteTimeoutConfigTest`, `RouteTimeoutTest`, `RouteTimeoutStartupTest` | Que los valores literales de la spec estén tal cual | El armado de `MockWebServer` |
| `docs/errores.md`, `.env.example`, `docker-compose.yml`, `CLAUDE.md` | Redacción del límite por ruta | Alta de la variable |
| `docs/verificaciones/V-NN-tiempo-maximo-entrevistas.md` | Salida real de `gcloud` (sin secretos) | — |

## Commits

| # | Título | Contenido |
|---|---|---|
| 1 | `CM-357 \| feat(gateway): tiempo máximo de 120 s para las rutas de la entrevista` | T1 |
| 2 | `CM-357 \| feat(gateway): validar al arrancar los tiempos máximos` | T2 |
| 3 | `CM-357 \| test(gateway): tiempos por ruta, configuración y validación` | T3 |
| 4 | `CM-357 \| docs(gateway): variable nueva, errores y verificación en el entorno` | T4 y T5 |

## Orden y dependencias

T0 (preparación, sin commit) → T1 → T2 → T3 → T4 → T5 (cierre). T3 puede escribirse junto con T1 y T2, pero se commitea después.

## Verificación

Mientras se construye: `./mvnw.cmd -B -ntp -Dtest='RouteTimeout*Test' test`. Al terminar, una sola vez sobre la punta: `./mvnw.cmd -B -ntp clean verify`, cobertura de lo tocado y la verificación en el entorno (V-ENT). No hay colección Postman en el Gateway: no aplica Newman.

## Riesgos

| Riesgo | Mitigación |
|---|---|
| Un balanceador con tope menor (H-3) hace inútil el cambio | V-ENT antes de dar por cerrada la tarea; si falla, tarea de DevOps que abre Paula |
| La clave de metadatos no se aplica en ejecución | `RouteTimeoutTest` lo prueba con tiempos reales a escala |
