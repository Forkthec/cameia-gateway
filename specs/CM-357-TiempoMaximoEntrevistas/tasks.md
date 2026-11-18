# Tareas · CM-357 Tiempo máximo de 120 s para las entrevistas

Cada tarjeta se entiende sola. Rama: `CM-357-tiempo-maximo-entrevistas` desde `develop` actualizado. Antes de empezar: leer `CLAUDE.md` del repo, `git status` (los cambios de `mvnw.cmd` por finales de línea son ruido: no se tocan). Idioma: identificadores en inglés, documentación y mensajes en español. Comentarios del código autosuficientes (sin códigos de tarea ni referencias a otros archivos).

---

## T0 · Preparación y confirmaciones (sin commit)

1. `git fetch && git switch -c CM-357-tiempo-maximo-entrevistas origin/develop`.
2. Confirmar que `application.yml` tiene la ruta `cameia-entrevista` sin `metadata` y el global en `${GATEWAY_TIMEOUT_MS:30000}ms`.
3. Confirmar F-5 de la spec: arrancar con `metadata.response-timeout: abc` en una prueba descartable y anotar que **no** falla al arrancar (si fallara, el validador sigue siendo necesario por el mensaje claro y por `0`/negativos; anótalo en la verificación).
4. Revisar `docs/matriz-asvs-nivel1.md` (o el nombre real en `cameia-infra` leído solo) y poner la numeración exacta de la fila de errores/logs en `docs/verificaciones/V-NN`.
5. Buscar si existe `DocsConsistencyTest` (`Glob **/DocsConsistency*`).
**Si algo no cuadra:** detenerse y avisar a Paula; no improvisar.

## T1 · Configuración de la ruta (commit 1)

Archivo: `src/main/resources/application.yml`. En la ruta con `id: cameia-entrevista`, debajo de `predicates`, agregar:

```yaml
          # Una respuesta que usa el modelo de lenguaje o la voz puede tardar
          # más que los 30 s globales; esta ruta espera hasta 120 s (milisegundos).
          # Las demás rutas conservan el tiempo global.
          metadata:
            response-timeout: ${GATEWAY_INTERVIEWS_TIMEOUT_MS:120000}
```

Respetar la sangría de las otras rutas. No tocar `httpclient.response-timeout`. **Trampa:** el valor es un número entero de milisegundos, no `120000ms`. Comprobar: `./mvnw.cmd -B -ntp -DskipTests compile`.

## T2 · Validador de arranque (commit 2)

Archivo nuevo: `src/main/java/tech/cameia/gateway/config/RouteTimeoutsValidator.java` (paquete `config`, `@Component`, un método `@PostConstruct void validate()`; recibe `Environment` por constructor). Lógica (≤ 3 parámetros por método, métodos cortos):

- Para cada nombre en `{"GATEWAY_TIMEOUT_MS" → 30000, "GATEWAY_INTERVIEWS_TIMEOUT_MS" → 120000}` leer `environment.getProperty(nombre)`.
- Si es `null` (variable no definida): válido (usa el valor por omisión). Si es vacío (`isEmpty()`): inválido (decisión D-5).
- Si no, debe cumplir `^[+-]?\d+$` y, tras `Long.parseLong`, ser `> 0`; `NumberFormatException` también es inválido. No se recorta (`trim`). Las dos variables tienen un máximo de `600000` (decisiones D-2 y D-6 de Paula, 7-nov-2026).
- Si es inválido: `throw new IllegalStateException(...)` con el mensaje de la spec REQ-TMO-04: «<VARIABLE> debe ser un entero entre 1 y 600000 (milisegundos); valor recibido: '<recorte>'», igual para las dos variables, con `recorte` = primeros 20 caracteres (por puntos de código).
- Sin logs del valor de otras propiedades. Javadoc en español que explique el porqué.
- Se agrega a la tabla de componentes de `CLAUDE.md` (T4).

**Trampa:** `Environment.getProperty("GATEWAY_TIMEOUT_MS")` lee variables de entorno y propiedades; en pruebas se usa `TestPropertySource`/`DynamicPropertySource` con ese mismo nombre. **Trampa 2 (T1 de la spec):** `${VAR:120000}` aplica el valor por omisión solo si la variable no existe; una variable definida y vacía produce un texto vacío y rompería la ruta en ejecución. Comprobado: ocurre, y la decisión D-5 de Paula es que la variable vacía no es válida y falla en el validador (la fila `""` de la spec §7 es «falla»). Comprobar: pruebas de T3.

## T3 · Pruebas (commit 3)

Paquete `tech.cameia.gateway.config` y `...routing` según la convención del repo (ver `GatewayErrorMappingTest`). Reutilizar su patrón de `@SpringBootTest(RANDOM_PORT)`, `MockWebServer` y `DynamicPropertySource`.

1. `RouteTimeoutsValidatorTest` (parametrizada, valores literales de la spec §7): `null`, `"1"`, `"900"`, `"120000"`, `"600000"` válidos en las dos variables; `"600001"`, `"3600000"` y `"9223372036854775807"` inválidos en las dos con el mensaje de rango; `""`, `"0"`, `"-1"`, `"-120000"`, `"abc"`, `"12.5"`, `"1e3"`, `"120 s"`, `"120000ms"`, `" 900 "`, `"9223372036854775808"`, `"<script>"`, texto de 10 000 caracteres inválidos. Afirmar el mensaje exacto y que el valor del mensaje no pasa de 20 caracteres. Cubre `GATEWAY_TIMEOUT_MS` y `GATEWAY_INTERVIEWS_TIMEOUT_MS`.
2. `RouteTimeoutConfigTest`: con el contexto real y sin propiedades de tiempo, la ruta de entrevistas tiene metadato `response-timeout` = `120000`, ninguna otra ruta lo tiene y el global es 30 000 ms.
3. `RouteTimeoutTest` (con `GATEWAY_TIMEOUT_MS=300` y `GATEWAY_INTERVIEWS_TIMEOUT_MS=900`; destino que demora con `MockResponse.setBodyDelay`/`setHeadersDelay`): los siete casos de la spec §5 y §9 (`interviews_shouldReturn200_whenDownstreamTakes500ms`, `otherRoute_shouldReturn504_whenDownstreamTakes500ms`, `interviews_shouldReturn504_whenDownstreamTakes1000ms`, `otherRoute_shouldReturn504_whenDownstreamTakes1000ms`, `timeout_shouldCarryRequestIdHeader`, `timeout_shouldKeepBodyShape`, `interviews_shouldTimeOutIndependently_whenTwoSlowRequests`, `interviews_shouldReturn503_whenDownstreamClosed`). El cuerpo del 504 es exactamente `{"code":"GATEWAY_TIMEOUT","message":"El servicio destino no respondió a tiempo"}` (copiar el mensaje literal de `GlobalErrorHandler`; si difiere, usar el del código y avisar).
4. `RouteTimeoutStartupTest.context_shouldFail_whenInterviewsTimeoutInvalid`: `SpringApplication.run` con la propiedad en `0` dentro de `assertThatThrownBy` y mensaje que contiene `GATEWAY_INTERVIEWS_TIMEOUT_MS`.

Pruebas de tiempo: sin `Thread.sleep` en el cuerpo de la prueba (el retraso va en el `MockResponse`); márgenes ≥ 200 ms entre el tiempo de respuesta y el límite, para que no sean inestables. Comprobar: `./mvnw.cmd -B -ntp -Dtest='RouteTimeout*Test' test`.

## T4 · Documentación (commit 4)

1. `docs/errores.md` fila `GATEWAY_TIMEOUT`: «El servicio destino no respondió dentro del límite de la ruta: `GATEWAY_TIMEOUT_MS` (30 s por omisión) o, en `/api/v1/interviews/**`, `GATEWAY_INTERVIEWS_TIMEOUT_MS` (120 s por omisión)». En la sección de límites conocidos: «si el destino ya envió los encabezados y se cuelga, el cliente recibe un corte, no un 504».
2. `.env.example` (junto a líneas 72-74), `docker-compose.yml` (junto a la línea 48) y `CLAUDE.md` (tabla de variables con valor por defecto y tabla de componentes con `RouteTimeoutsValidator`).
3. Si existe `DocsConsistencyTest`, ejecutarlo.
4. Sin ADR nuevo (no hay decisión de arquitectura): la decisión D-1 se anota en la bitácora si Paula la toma.

## T5 · Cierre y verificación en el entorno

1. `./mvnw.cmd -B -ntp clean verify` sobre la punta; informe de cobertura `target/site/jacoco/jacoco.csv` ≥ 90 % de líneas y ramas del validador.
2. V-ENT (spec §11, solo lectura, sin imprimir secretos): `gcloud run services describe` del Gateway y de Entrevista en preproducción y producción; anotar la salida real en `docs/verificaciones/V-NN-tiempo-maximo-entrevistas.md`. Si algún valor es ≤ 120 s, o no se puede consultar, **no se afirma**: queda `PENDIENTE (Paula o DevOps)`.
3. Mensaje a DevOps sobre el balanceador: `comunicaciones/07112026_v1_tiempo-maximo-del-gateway-para-devops.md` (ya redactado; Paula decide cuándo enviarlo).
4. Cuerpo del PR validado con `pr_policy.py`; abrirlo en Draft con `[IA-ASISTIDO]` al final del título; `Impacto: contrato` con su línea `Contrato:` («solo cambia el tiempo de espera de la ruta de entrevistas»).
5. Comentario en Jira CM-357 con Acción, Repositorio, Resultado, Evidencia, Decisión o bloqueo, Siguiente paso, Responsable. Mover la tarjeta a «En curso» al empezar y a «En revisión» solo con el PR abierto y el DoD cumplido.

---

## Resultados de la ejecución (7-nov-2026)

Evidencia completa en `ejecucion/evidencia/CM-357/` (`resumen.md` y `06-verify-final.log`).

| Tarjeta | Resultado |
|---|---|
| T0 | Línea base verde: 201 pruebas, 0 fallos. Metadato llega como texto `"120000"`. No existe `DocsConsistencyTest`. La matriz ASVS no tiene fila «16.x» |
| T1 | Ruta de entrevistas con su tiempo propio. `RouteTimeoutConfigTest` 3 de 3 |
| T2 | `RouteTimeoutsValidator`. La variable definida y vacía se **rechaza** (D-5) y las dos variables tienen tope de 600000 ms (D-2 y D-6; el segundo salió de una petición real que dio 500 con el mayor `long`) |
| T3 | Pruebas de validador, configuración, tiempos y arranque, en verde |
| T4 | Documentación: `docs/errores.md`, `.env.example`, `docker-compose.yml`, `CLAUDE.md`, `docs/COMO-FUNCIONA.md`, `docs/verificaciones/V-01-tiempo-maximo-entrevistas.md` y bitácora de IA |
| T5 | `clean verify`: 250 pruebas, 0 fallos. Cobertura de `RouteTimeoutsValidator`: 22 de 22 líneas y 12 de 12 ramas. V-ENT: Cloud Run del Gateway y de Entrevista en 300 s en producción y en preproducción; el balanceador de cada entorno tiene 30 s y su único destino es el Gateway (D-3, en manos de DevOps) |
