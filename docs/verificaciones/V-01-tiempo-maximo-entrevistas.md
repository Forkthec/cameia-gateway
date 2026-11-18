# V-01 · Tiempo máximo de respuesta de `/api/v1/interviews/**`

Verificación en el entorno de que ningún tramo entre la persona y el servicio de entrevistas corta una respuesta antes de los 120 s que el Gateway espera en esa ruta. Todas las consultas son de solo lectura; no se imprime ningún secreto.

- **Fecha:** 7-nov-2026 (hora colombiana).
- **Cuenta usada:** la cuenta de `gcloud` del equipo (`forktech.team@gmail.com`).
- **Alcance:** Cloud Run del Gateway y de Entrevista, y balanceador de carga, en producción (`cameia-prod-2026`) y en preproducción (`cameia-e245f`), región `us-east1` en ambos.

## Resultado

| # | Qué se midió | Comando (solo lectura) | Salida real | Veredicto |
|---|---|---|---|---|
| 1 | Tiempo máximo de solicitud de Cloud Run, Gateway en producción | `gcloud run services describe cameia-gateway --project=cameia-prod-2026 --region=us-east1 --format="value(spec.template.spec.timeoutSeconds)"` | `300` | Cumple: 300 s > 120 s |
| 2 | Ídem, Entrevista en producción | el mismo comando con `cameia-entrevista` | `300` | Cumple: 300 s > 120 s |
| 3 | Tiempo máximo del servicio de backend del balanceador de producción | `gcloud compute backend-services list --project=cameia-prod-2026 --format="table(name,timeoutSec,loadBalancingScheme)"` | `cameia-gateway-backend-production  30  EXTERNAL_MANAGED` | **Hallazgo:** el valor configurado es 30 s. No se afirma que corte la respuesta (ver abajo) |
| 4 | Tiempo máximo de solicitud de Cloud Run, Gateway y Entrevista en preproducción | los mismos comandos con `--project=cameia-e245f` | `300` y `300` | Cumple: 300 s > 120 s |
| 4b | Tiempo máximo del servicio de backend del balanceador de preproducción | `gcloud compute backend-services list --project=cameia-e245f --format="table(name,timeoutSec,loadBalancingScheme)"` | `cameia-gateway-backend-staging  30  EXTERNAL_MANAGED` | **Hallazgo:** 30 s, igual que producción |
| 4c | A qué apunta el balanceador | `gcloud compute backend-services list` y `gcloud compute network-endpoint-groups list` en ambos proyectos | Un solo destino por balanceador: un grupo `SERVERLESS` hacia el Cloud Run `cameia-gateway` (`cameia-gateway-neg-production` y `cameia-gateway-neg-staging`) | Hecho: el balanceador está en el camino de la persona hacia el Gateway |
| 4d | Variables de tiempo definidas en el Gateway desplegado | lectura de las variables de entorno del servicio, filtradas por `GATEWAY_*TIMEOUT*` | Ninguna definida en preproducción ni en producción | Cumple: ningún entorno deja la variable vacía, así que el rechazo de la vacía no impide el arranque |
| 5 | Petición lenta real de punta a punta (más de 30 s) | — | No se ejecutó: Entrevista no tiene hoy un endpoint que tarde más de 30 s sin el modelo de lenguaje | `PENDIENTE`: queda para la prueba de la respuesta escrita con el modelo real |

## Lectura de las filas 3 y 4b (qué se sabe y qué no)

- **Hecho:** el servicio de backend del balanceador (`cameia-gateway-backend-production`, esquema `EXTERNAL_MANAGED`) tiene `timeoutSec = 30`.
- **No se sabe, y no se afirma:** si ese valor se aplica cuando el destino es un servicio de Cloud Run. Ni la documentación consultada de memoria ni esta lectura lo prueban; solo lo prueba una petición real que pase por el balanceador y dure más de 30 s.
- **Hecho añadido:** el único destino de cada balanceador es el Cloud Run del Gateway (fila 4c), así que ese tiempo está en el camino de toda petición de la persona.
- **Riesgo si se aplica:** el Gateway esperaría 120 s, pero el balanceador cortaría a los 30 s y la persona vería un corte de conexión, no el 504 previsto. La mejora de esta tarea no llegaría a la pantalla.
- **Acción:** `PENDIENTE (DevOps)`. Proponerle a DevOps subir el tiempo del servicio de backend a más de 120 s en producción y preproducción, y que confirme si el valor actual corta las respuestas (mensaje listo en `comunicaciones/07112026_v1_tiempo-maximo-del-gateway-para-devops.md`). Esta tarea no cambia infraestructura.

## Comprobaciones de la preparación (T0)

| Punto | Resultado |
|---|---|
| `application.yml` antes del cambio: ruta de entrevistas sin `metadata`, global en `${GATEWAY_TIMEOUT_MS:30000}ms` | Confirmado |
| El metadato `response-timeout` se aplica en ejecución | Confirmado con `RouteTimeoutTest`: con el global en 300 ms y el de la ruta en 900 ms, un destino que responde a los 500 ms da 200 en entrevistas y 504 en la otra ruta |
| El metadato llega a la ruta como texto (`"120000"`), no como número | Confirmado con `RouteTimeoutConfigTest` |
| Un valor inválido del metadato no falla al arrancar | Confirmado con el caso de la variable definida y vacía: antes del validador la aplicación arrancaba con el metadato `""`, que la ruta interpretaría en cada petición. El valor `abc` literal no se probó sin el validador |
| Variable definida y vacía | El marcador `${VAR:120000}` solo aplica su valor por omisión si la variable no existe. Por eso el validador rechaza la variable vacía con un mensaje claro |
| Numeración ASVS | `matriz-asvs-nivel1.md` no tiene una fila de manejo de errores o registros distinta de la 4.1.1 (`Content-Type` con `charset`), que el 504 conserva (`RouteTimeoutTest.timeout_shouldKeepBodyShape`). La spec mencionaba una fila «16.x» que no existe en la matriz |
| `DocsConsistencyTest` | No existe en el repositorio |
