# CM-357 · Tiempo máximo de 120 s para `/api/v1/interviews/**` en el Gateway

| Campo | Valor |
|---|---|
| Tarea Jira | CM-357 (tarea de Backend, Sprint 4, 2 h, asignada a Paula Andrea Muñoz Delgado, «Por hacer»; un comentario de Paula del 2-nov; sin enlaces). Jira leído el 7-nov-2026 |
| Repositorio | `cameia-gateway`, base `origin/develop` = `ebb7e96` |
| Origen | Tarjeta GW-2 y requisitos REQ-ALN-13 y REQ-ALN-14 (spec §3.4) de la spec de CM-296, sin aprobar y fuera del Sprint 4. Esta spec es la única vigente para el tiempo máximo; de CM-296 no se toma nada más |
| Backlog | Sin historia propia: es un criterio técnico que sostiene HU-5.2 (responder la pregunta) y HU-5.8 (voz). Los criterios son los de la descripción de Jira, citados literalmente en §5 |
| Estado | **APROBADA por Paula (7-nov-2026) y ejecutada.** Decisiones D-1, D-2, D-5 y D-6 cerradas por Paula el 7-nov-2026; D-3 (balanceador de carga) en manos de DevOps y no bloquea el PR |

## 1. Objetivo

La ruta `cameia-entrevista` (`/api/v1/interviews/**`) espera la respuesta del servicio hasta 120 s; todas las demás rutas siguen esperando 30 s. Si se supera, el Gateway responde 504 `GATEWAY_TIMEOUT` (código que ya existe). Un valor inválido de las variables de tiempo (la de los 120 s o la global de 30 s) impide el arranque con un mensaje claro.

## 2. Revalidación contra `origin/develop` (`ebb7e96`)

Commits desde la base de CM-296 (`84d7e02`): CM-340 (`e8479eb`, admite `Idempotency-Key` en el CORS) y CM-320 (`ebb7e96`, flujo de despliegue). Ninguno toca el tiempo máximo ni las rutas; la tarjeta GW-2 sigue aplicando, con estas correcciones:

| # | Hecho comprobado | Ancla | Efecto en la tarjeta GW-2 |
|---|---|---|---|
| F-1 | El tiempo global es `spring.cloud.gateway.server.webflux.httpclient.response-timeout: ${GATEWAY_TIMEOUT_MS:30000}ms` | `src/main/resources/application.yml:96-98` | Se mantiene intacto |
| F-2 | La ruta `cameia-entrevista` tiene `id`, `uri: ${CAMEIA_ENTREVISTA_URL:http://cameia-entrevista:8080}` y `predicates: Path=/api/v1/interviews/**`, sin `metadata` | `application.yml` (ruta `cameia-entrevista`) | Se agrega `metadata.response-timeout` en esa ruta |
| F-3 | El prefijo de propiedades es `spring.cloud.gateway.server.webflux` (Spring Cloud Gateway 5.0.3, `spring-cloud.version 2025.1.3`) | `application.yml`, `pom.xml:29` | Las rutas ya están bajo ese prefijo; `metadata` va dentro de la ruta |
| F-4 | La clave de metadatos `response-timeout` existe en la versión del repo: `RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR = "response-timeout"` y `NettyRoutingFilter` la lee (`responseTimeoutAttr`, `routeResponseTimeout`) | jar `spring-cloud-gateway-server-webflux-5.0.3` (inspeccionado el 7-nov) | La trampa de la tarjeta queda resuelta; la prueba T2 la confirma en ejecución |
| F-5 | Un valor no numérico en `metadata.response-timeout` **no** falla al arrancar: la ruta lo interpreta al atender la petición | inferencia del código de `NettyRoutingFilter` (T0 lo comprueba) | Por eso hace falta la validación propia (REQ-TMO-04) |
| F-6 | El tiempo agotado llega al manejador como `ResponseStatusException` (que conserva el 504) o como `TimeoutException` en la cadena de causas, y ambos dan 504 `GATEWAY_TIMEOUT` | `GlobalErrorHandler.resolveStatus` y `networkFailureStatus` | No se cambia el manejador |
| F-7 | El cuerpo de error del Gateway es `{"code","message"}` y **no lleva `requestId`**; el identificador viaja en el encabezado de respuesta `X-Request-Id` y en el log | `GlobalErrorHandler.handle` y `resolveRequestId` | «504 con `requestId`» se prueba como encabezado `X-Request-Id` presente (H-2) |
| F-8 | `docs/errores.md` fila `GATEWAY_TIMEOUT` dice «superó `GATEWAY_TIMEOUT_MS`» | `docs/errores.md:23` | Se aclara el límite de la ruta de entrevistas |
| F-9 | Variables documentadas en `.env.example` (líneas 72-74), `docker-compose.yml` (línea 48) y `CLAUDE.md` (tabla de variables, línea 210) | esos archivos | Se agrega la variable nueva en los tres |
| F-10 | El despliegue a producción no fija `--timeout` de Cloud Run: Cloud Run usa su valor por omisión de 300 s. El comando fija `--ingress=internal-and-cloud-load-balancing`, es decir, hay un balanceador de carga delante | `.github/workflows/despliegue-continuo.yml:227-239` | Verificación en el entorno (V-ENT, H-3) |
| F-11 | `CLAUDE.md` del repo: «No se crea ninguna clase que no esté en la tabla de componentes o en una spec aprobada» | `CLAUDE.md` §2 | La clase nueva (`RouteTimeoutsValidator`) queda autorizada al aprobarse esta spec, y se agrega a esa tabla |
| F-12 | Sin cambios: la configuración de pruebas `application-test.yml` no fija tiempos; `GatewayErrorMappingTest` baja el global a 300 ms con `DynamicPropertySource` | `src/test/resources/application-test.yml`, `GatewayErrorMappingTest` | El patrón se reutiliza |

## 3. Hallazgos (clasificados)

| # | Hallazgo | Clase | Destino |
|---|---|---|---|
| H-1 | **La justificación del backlog cambió, el valor no.** La descripción de Jira habla de «hasta unos 95 s» por 3 intentos del modelo. La spec vigente de registrar la respuesta (CM-216, de Vela, sin aprobar) hace **una** llamada de hasta 30 s (`cameia.llm.timeout` = 30 s) y pone el reintento en manos de la persona; la voz (HU-5.8) pide 120 s en el Gateway, 130 s en la pantalla y 110 s en Entrevista (documento de Frontend del 21-oct). Los 120 s se mantienen: los fija el criterio de la tarea y la voz | Cambio de redacción, sin alternativa | Spec §4 y comentario de Jira al terminar |
| H-2 | «504 con `requestId`»: el cuerpo del Gateway no lo lleva; va en el encabezado `X-Request-Id`. Cambiar el cuerpo sería un cambio de contrato (Frontend) fuera del alcance («Contrato: sin cambio de formato») | Decisión de Paula (D-1) | Se prueba el encabezado |
| H-3 | Posible tope menor en el balanceador de carga: el Gateway de producción está detrás de `internal-and-cloud-load-balancing`; el tiempo máximo por omisión del servicio de backend de un balanceador externo es 30 s. Si aplicara, cortaría la respuesta a los 30 s aunque el Gateway espere 120 s | Verificación previa y, si falla, acción para DevOps (tarea que abre Paula) | D-3, V-ENT |
| H-4 | La validación de arranque que pide la tarea no existe en el repo y el mecanismo de metadatos no valida | Cambio sin alternativa | REQ-TMO-04 |
| H-5 | El valor global `GATEWAY_TIMEOUT_MS` inválido (`abc`) ya falla al arrancar por el enlace de `Duration`, pero con un mensaje técnico de Spring; `0` y negativos no se rechazan; y un valor enorme (el mayor `long`) arranca pero hace que Reactor falle con `ArithmeticException: long overflow` y el Gateway responda el 500 genérico en cada petición (medido con una petición real) | Corrección de código dentro del archivo que se toca | REQ-TMO-04 lo valida con el mismo tope y el mismo mensaje claro (D-6) |

## 4. Requisitos (EARS)

Equivalencias con la spec de origen: REQ-TMO-01 y 02 = REQ-ALN-13; REQ-TMO-03 = REQ-ALN-14.

- **REQ-TMO-01** La ruta `cameia-entrevista` debe declarar `metadata.response-timeout: ${GATEWAY_INTERVIEWS_TIMEOUT_MS:120000}` (milisegundos, entero), con un comentario de bloque en español que explique el porqué (una respuesta con modelo de lenguaje o con voz puede tardar más de 30 s).
- **REQ-TMO-02** Ninguna otra ruta debe declarar `response-timeout` y `spring.cloud.gateway.server.webflux.httpclient.response-timeout` debe seguir siendo `${GATEWAY_TIMEOUT_MS:30000}ms`; así las demás rutas conservan 30 s.
- **REQ-TMO-03** Cuando el servicio de entrevistas no responda dentro del tiempo de la ruta, el Gateway debe responder 504 con cuerpo `{"code":"GATEWAY_TIMEOUT","message":"El servicio destino no respondió a tiempo"}`, `Content-Type: application/json;charset=UTF-8` y el encabezado `X-Request-Id` presente; una respuesta que llegue antes de ese tiempo debe pasar íntegra (estado, encabezados y cuerpo).
- **REQ-TMO-04** Al arrancar, el Gateway debe validar `GATEWAY_INTERVIEWS_TIMEOUT_MS` y `GATEWAY_TIMEOUT_MS`: cada una, si está definida, debe ser un entero (solo dígitos, con signo opcional para poder rechazar los negativos) en milisegundos, entre 1 y 600000 (10 minutos; decisiones D-2 y D-6). Si no se cumple, incluida la variable definida y vacía (D-5), el arranque debe fallar con `IllegalStateException` cuyo mensaje, en español, nombre la variable y el valor recibido recortado a 20 caracteres: «<VARIABLE> debe ser un entero entre 1 y 600000 (milisegundos); valor recibido: '<valor>'», con `<VARIABLE>` igual a `GATEWAY_INTERVIEWS_TIMEOUT_MS` o `GATEWAY_TIMEOUT_MS`. No debe aplicarse ningún valor por omisión en silencio. Una variable ausente (no definida) toma el valor por omisión del YAML.
- **REQ-TMO-05** El Gateway no debe registrar el valor de ninguna otra propiedad al validar; el mensaje solo incluye la variable validada.
- **REQ-TMO-06** `docs/errores.md` debe decir, en la fila `GATEWAY_TIMEOUT`, que el límite es `GATEWAY_TIMEOUT_MS` (30 s) y, en `/api/v1/interviews/**`, `GATEWAY_INTERVIEWS_TIMEOUT_MS` (120 s); `.env.example`, `docker-compose.yml` y `CLAUDE.md` deben listar la variable nueva con su valor por omisión; la tabla de componentes de `CLAUDE.md` debe incluir `RouteTimeoutsValidator`.
- **REQ-TMO-07** La suite completa, `GatewayArchTest` y la cobertura (≥ 90 % de líneas y de ramas de lo nuevo) deben pasar.

## 5. Criterios de aceptación (literales de Jira) → requisito → prueba

| Criterio | Requisito | Prueba (nombre exacto) |
|---|---|---|
| «La ruta `/api/v1/interviews/**` espera hasta 120 s; las demás rutas siguen en 30 s.» | REQ-TMO-01, 02 | `RouteTimeoutConfigTest.interviewsRoute_shouldDeclare120000_byDefault`, `…otherRoutes_shouldNotDeclareResponseTimeout`, `…globalTimeout_shouldStayAt30000_byDefault` (lee la configuración real con `RouteLocator`) |
| «Con `GATEWAY_TIMEOUT_MS=300` y `GATEWAY_INTERVIEWS_TIMEOUT_MS=900`, un destino que responde a los 500 ms da 200 en la ruta de entrevistas y 504 `GATEWAY_TIMEOUT` en otra ruta; un destino a los 1000 ms da 504 en ambas.» | REQ-TMO-01 a 03 | `RouteTimeoutTest.interviews_shouldReturn200_whenDownstreamTakes500ms`, `…otherRoute_shouldReturn504_whenDownstreamTakes500ms`, `…interviews_shouldReturn504_whenDownstreamTakes1000ms`, `…otherRoute_shouldReturn504_whenDownstreamTakes1000ms` |
| «Una respuesta que llega antes de 120 s (p. ej. a los 95 s) pasa sin cortarse; si supera los 120 s, 504 con `requestId`.» | REQ-TMO-03 | Las mismas pruebas, a escala (500 ms < 900 ms ≈ «95 s < 120 s»; 1000 ms > 900 ms), más `…timeout_shouldCarryRequestIdHeader` y `…timeout_shouldKeepBodyShape`; la escala se justifica en §9 |
| «Un valor 0, negativo o no numérico en `GATEWAY_INTERVIEWS_TIMEOUT_MS` hace fallar el arranque con un mensaje claro.» (por D-2, D-5 y D-6 el criterio se amplía: también falla un valor mayor que 600000, la variable vacía y los mismos casos en `GATEWAY_TIMEOUT_MS`; Paula lo cambia en Jira) | REQ-TMO-04 | `RouteTimeoutsValidatorTest` (tabla de §7) y `RouteTimeoutStartupTest.context_shouldFail_whenInterviewsTimeoutInvalid` |
| «Verificado en el entorno que el tiempo máximo de solicitud del Gateway en Cloud Run es mayor que 120 s.» | — (verificación) | V-ENT (§11): salida de `gcloud run services describe` anotada en `docs/verificaciones/V-NN-tiempo-maximo-entrevistas.md` |
| «`docs/errores.md` del Gateway actualizado y Frontend avisado.» | REQ-TMO-06 | `DocsConsistencyTest` si existe en el repo; si no, revisión de la lista (§12) y aviso en el documento de Frontend de `comunicaciones/` (punto 3.5) |

## 6. Contrato

Sin cambio de formato ni de códigos: los mismos cuerpos y estados. Cambia solo el tiempo de espera de `/api/v1/interviews/**`: de 30 s a 120 s. Consumidor: `cameia-web`, que hoy espera 60 s en iniciar la entrevista y 30 s en enviar y reintentar una respuesta (comprobado en su código el 7-nov); corta antes que el Gateway, así que se le pide subir a 130 s (puntos 3.5 y 4.4 del documento `comunicaciones/07112026_v1_tono-motivador-sale-del-catalogo-para-frontend.md`). Sin ese cambio de Frontend la mejora no llega a la persona.

## 7. Validación de la variable (R2) — `GATEWAY_INTERVIEWS_TIMEOUT_MS` (y `GATEWAY_TIMEOUT_MS`)

| Valor de la variable | Resultado |
|---|---|
| ausente (no definida) | usa `120000` (`30000` para la global) |
| `""` (definida y vacía) | falla en las dos: «… debe ser un entero entre 1 y 600000 (milisegundos); valor recibido: ''». La expresión `${VAR:120000}` solo aplica el valor por omisión cuando la variable no existe; con la variable vacía la ruta recibiría un texto vacío y fallaría en cada petición (D-5). `docker-compose.yml` usa `${VAR:-valor}`, que sí aplica el valor por omisión con la vacía |
| `"1"`, `"900"`, `"120000"`, `"600000"` | válido para las dos variables |
| `"600001"`, `"3600000"` | falla en las dos («… debe ser un entero entre 1 y 600000 (milisegundos); valor recibido: '600001'») |
| `"0"`, `"-1"`, `"-120000"` | falla en las dos: «… debe ser un entero …; valor recibido: '0'» |
| `"abc"`, `"12.5"`, `"1e3"`, `"120 s"`, `"120000ms"` | falla (no es un entero solo de dígitos) |
| `" 900 "` (espacios) | falla (no se recortan; un espacio de más en una variable de entorno es un error de despliegue que debe verse) |
| `"9223372036854775807"` (long máximo) | falla en las dos (supera 600000). Sin tope, Reactor desbordaría al convertir el tiempo a nanosegundos y cada petición daría 500 (D-6) |
| `"9223372036854775808"` | falla (no cabe en un `long`) |
| `"<script>"`, texto de 10 000 caracteres | falla; el mensaje recorta el valor a 20 caracteres |

Longitud: no aplica a un texto libre; el recorte a 20 caracteres evita volcar un valor largo al log. Las dos variables tienen máximo de 600000 ms (D-2 y D-6).

## 8. Base de datos, errores, seguridad

- **Datos:** el Gateway no tiene base de datos: no aplica R4.
- **Errores (R3):** ningún código nuevo. Rutas que podrían llegar al genérico `INTERNAL_ERROR`: (a) el tiempo agotado, que sigue por `resolveStatus` → 504, probado con petición real en `RouteTimeoutTest`; (b) el valor inválido, que es un fallo de arranque (no hay petición a la que responder) con mensaje claro en el log de arranque.
- **Seguridad (R6):**

| ID / fila | Aplica | Cómo | Prueba |
|---|---|---|---|
| OWASP API4 (consumo de recursos) | sí | Subir de 30 a 120 s alarga cuánto puede retener una conexión un servicio lento (más conexiones abiertas); se limita a la ruta de entrevistas y se acompaña de `--max-instances=2` y el límite de abuso de DevOps ya vigente | `…otherRoute_shouldReturn504…` (el resto sigue en 30 s) |
| OWASP API8 (configuración) | sí | Valor inválido impide arrancar; sin valor por omisión silencioso | `RouteTimeoutsValidatorTest` |
| ASVS 16.x (manejo de errores y logs, la numeración exacta se contrasta con `matriz-asvs-nivel1.md` en T0) | sí | El 504 no muestra detalle interno; el log no registra valores ajenos | `…timeout_shouldKeepBodyShape` |
| ASVS 4.1.1 (charset) | sí | el 504 conserva `application/json;charset=UTF-8` | `…timeout_shouldKeepBodyShape` |
| Identidad (`X-User-*`), BOLA, asignación masiva | no cambia | no se toca el filtro de autenticación | pruebas existentes |

## 9. Casos borde (R5)

| Dimensión | Caso | Resultado | Prueba |
|---|---|---|---|
| Tiempo | respuesta justo antes del límite | pasa (a escala: 500 ms con límite 900 ms) | `…interviews_shouldReturn200…` |
| Tiempo | respuesta después del límite | 504 | `…interviews_shouldReturn504…1000ms` |
| Tiempo | la otra ruta con 500 ms | 504 (su límite es 300 ms) | `…otherRoute_shouldReturn504…500ms` |
| Tiempo | límite exacto | no se prueba (depende del reloj del sistema, sería inestable); se justifica por escrito |
| Escala | los 95 s y 120 s reales no se esperan en pruebas automáticas (sumarían minutos por prueba); se prueba la misma lógica a escala 1:100 (500 ms/900 ms/1000 ms) y la configuración real se lee de `application.yml` | — | `RouteTimeoutConfigTest` |
| Configuración | `GATEWAY_INTERVIEWS_TIMEOUT_MS` ausente | 120000 | `…byDefault`, `RouteTimeoutStartupTest.context_shouldKeepDefault_whenInterviewsTimeoutUndefined` |
| Configuración | cualquiera de las dos variables definida y vacía | el arranque falla con el mensaje claro | `RouteTimeoutsValidatorTest`, `RouteTimeoutStartupTest.context_shouldFail_whenInterviewsTimeoutEmpty` y `…whenGlobalTimeoutEmpty` |
| Configuración | `GATEWAY_TIMEOUT_MS` en el mayor `long` | el arranque falla con el mensaje claro, en vez de un 500 en cada petición | `RouteTimeoutStartupTest.context_shouldFail_whenGlobalTimeoutIsLongMax` |
| Configuración | `GATEWAY_TIMEOUT_MS=600000` (el máximo) | el Gateway atiende una petición real | `RouteTimeoutStartupTest.context_shouldServeRequests_whenGlobalTimeoutIsMaximum` |
| Configuración | los valores de §7 | según tabla | `RouteTimeoutsValidatorTest` (parametrizada) |
| Concurrencia | dos peticiones lentas a la vez | cada una cuenta su propio tiempo; ninguna afecta a la otra | `RouteTimeoutTest.interviews_shouldTimeOutIndependently_whenTwoSlowRequests` |
| Método / cuerpo | `POST` con cuerpo grande a la ruta con respuesta lenta | el tiempo de espera es de respuesta, no de envío; no se prueba aparte | justificado |
| Destino caído | conexión rechazada en la ruta de entrevistas | 503 `SERVICE_UNAVAILABLE` (sin cambio) | prueba existente de `GatewayErrorMappingTest`; se agrega una para esta ruta: `RouteTimeoutTest.interviews_shouldReturn503_whenDownstreamClosed` |
| Respuesta parcial | el destino responde encabezados y se cuelga en el cuerpo | el límite de respuesta de Reactor Netty (`responseTimeout`) cubre hasta la lectura completa del cuerpo; si ya se enviaron encabezados el cliente recibe un corte (la respuesta ya está comprometida y `GlobalErrorHandler` devuelve el error sin reescribir) | no se prueba (comportamiento de la librería); queda como límite conocido en `docs/errores.md` |
| Ataque | valor hostil en la variable | falla con el mensaje recortado | tabla §7 |
| Idempotencia | reintento de la persona tras el 504 | el Gateway no reintenta; la clave `Idempotency-Key` la gestiona Entrevista | sin cambio |

## 10. Atributos de calidad (R13)

| Atributo | Métrica | Límite | Cómo | Prueba / comando | Resultado |
|---|---|---|---|---|---|
| AC-0004 fiabilidad FIA-01 | un fallo conserva el último estado confirmado; el 503/504 llega como mensaje previsto | 100 % de los fallos de IA llegan a la pantalla con `code` | los 120 s dan margen a la respuesta de error del servicio de entrevistas | `RouteTimeoutTest` | Medido en el Gateway: pruebas en verde. El efecto en pantalla depende del balanceador de carga (D-3) y de Frontend: `PENDIENTE` punta a punta |
| AC-0002 desempeño DES-04 | 5xx propios < 1 %; cero turnos confirmados perdidos | no aumenta los 5xx | un corte a los 30 s de una respuesta válida deja de ocurrir | medición en el entorno (V-ENT) | `PENDIENTE`: no existe un endpoint que tarde más de 30 s (V-01, fila 5) |
| AC-0003 SEG-01 / SEG-04 | ASVS N1 aplicable; cero datos sensibles en logs | 100 % / 0 | §8; el mensaje de arranque no incluye datos | revisión + `RouteTimeoutsValidatorTest` | Medido: pruebas en verde; el mensaje de arranque no incluye datos ajenos |
| AC-0006 interacción | error con `code` | `GATEWAY_TIMEOUT` | sin cambio | `…timeout_shouldKeepBodyShape` | Medido: en verde |
| REST-0002 capacidad limitada | tiempos de espera definidos en toda llamada externa | cada ruta con su límite | metadata + global | `RouteTimeoutConfigTest` | Medido: en verde |
| MAN-01 cobertura | ≥ 90 % líneas y ramas de lo nuevo | 90 % | clase pequeña, todas las ramas con prueba | `target/site/jacoco/jacoco.csv` | Medido: 100 % de líneas y de ramas en `RouteTimeoutsValidator` |
| IOP-01 | contrato documentado | `docs/errores.md` | REQ-TMO-06 | revisión | Cumple: `docs/errores.md` actualizado |

## 11. Verificación en el entorno (V-ENT, solo lectura)

1. Tiempo máximo de solicitud de Cloud Run del Gateway en preproducción y en producción: `gcloud run services describe cameia-gateway --project=<proyecto> --region=us-east1 --format="value(spec.template.spec.timeoutSeconds)"` (los proyectos son `cameia-e245f` (preproducción) y `cameia-prod-2026` (producción); `cameia-staging` es el nombre de la red, no de un proyecto). Esperado: > 120 (300 por omisión). No se imprime ningún secreto.
2. Entrevista: el mismo valor del servicio `cameia-entrevista` (> 120).
3. Balanceador de carga (H-3): `gcloud compute backend-services list --project=<proyecto> --format="table(name,timeoutSec,loadBalancingScheme)"`. Resultado leído el 7-nov: 30 s en producción y en preproducción, con un único destino por balanceador (el Cloud Run del Gateway). Si ese valor corta las respuestas es lo que DevOps debe confirmar; se le propone subirlo a más de 120 s (D-3).
4. Petición lenta real: no existe en Entrevista un endpoint que tarde más de 30 s sin la IA; la comprobación de punta a punta queda para la prueba de la respuesta escrita (CM-216) con el modelo real. Mientras tanto la evidencia es 1–3 más las pruebas automáticas.
5. Resultado en `docs/verificaciones/V-01-tiempo-maximo-entrevistas.md` (R8).

## 12. Integración con otras CM del Gateway

CM-296 (Gateway) queda sin sprint; su tarjeta GW-2 no se ejecuta allí. Quien retome CM-296 debe quitar REQ-ALN-13 y 14 de su spec (se anota en el resumen y en el informe). Archivos compartidos: `application.yml` (otras CM del Gateway no previstas), `docs/errores.md`, `CLAUDE.md`. Un PR por CM; sin apilar.

## 13. Plan en commits

1. `CM-357 | feat(gateway): tiempo máximo de 120 s para las rutas de la entrevista`
2. `CM-357 | feat(gateway): validar al arrancar los tiempos máximos`
3. `CM-357 | test(gateway): tiempos por ruta, configuración y validación`
4. `CM-357 | docs(gateway): variable nueva, errores y verificación en el entorno`
5. `CM-357 | docs(gateway): especificación aprobada de la tarea`

## 14. Línea base verde

`./mvnw.cmd -B -ntp clean verify` sobre `origin/develop`. Los cambios locales de `mvnw.cmd` (finales de línea) son ruido: no se tocan ni cuentan.

## 15. Decisiones y preguntas

| # | Tipo | Asunto | Opciones y recomendación | Estado |
|---|---|---|---|---|
| D-1 | Decisión de Paula | «504 con `requestId`» | **A (recomendada):** el identificador va en el encabezado `X-Request-Id` (como todo error del Gateway hoy); sin cambio de contrato. **B:** agregar `requestId` al cuerpo de todos los errores del Gateway: cambia el contrato de Frontend y es otra tarea. Se avanza con A | **Cerrada: A (Paula, 7-nov-2026).** Encabezado `X-Request-Id`; el cuerpo no cambia |
| D-2 | Decisión de Paula | Máximo permitido de la variable | A: solo «entero > 0» como pide el criterio. **B:** tope de 600 000 ms (10 min) para evitar un descuido que retenga conexiones; añade una regla que el criterio no pide | **Cerrada: B (Paula, 7-nov-2026).** Tope de 600000 ms en `GATEWAY_INTERVIEWS_TIMEOUT_MS`; el de la global lo fija D-6. Paula ajusta el criterio en Jira y avisa al equipo en una línea |
| D-3 | Verificación / acción | Tiempo del balanceador y de Cloud Run | Si algún valor es ≤ 120 s: tarea de DevOps que abre Paula. Esta tarea no cambia infraestructura | **En manos de DevOps.** El balanceador de producción y el de preproducción tienen 30 s (leído el 7-nov). Se le propone a DevOps subirlo a más de 120 s por una tarea que abre Paula; si el valor no corta las respuestas, el cambio no hace daño |
| D-4 | Cambio | Dónde vive la validación | Clase `RouteTimeoutsValidator` en `config`, en lugar de `@ConfigurationProperties`; los metadatos de la ruta ya leen la variable y un enlace tipado duplicaría el valor | Aplicada |
| D-5 | Decisión de Paula | Variable definida y vacía | **A (recomendada):** el Gateway no arranca y dice por qué (regla de errores visibles). **B:** tratarla como ausente con código de configuración extra; un descuido de despliegue pasaría en silencio con el valor por omisión. Ningún entorno desplegado define las variables de tiempo (leído el 7-nov), así que A no impide ningún arranque hoy | **Cerrada: A (Paula, 7-nov-2026)** |
| D-6 | Decisión de Paula | Tope de `GATEWAY_TIMEOUT_MS` | Una petición real con el mayor `long` dio 500 genérico en cada petición (`ArithmeticException: long overflow`). **A (recomendada):** 600000 ms, igual que entrevistas y con el mismo mensaje. B: 3600000 ms (máximo de Cloud Run). C: solo evitar el desborde, sin proteger de conexiones colgadas | **Cerrada: A (Paula, 7-nov-2026).** Paula avisa al equipo en una línea |

## 16. Fuera de alcance

El resto de CM-296; cambios en `cameia-web`, `cameia-infra`, `cameia-metricas`; el cuerpo de error del Gateway; la voz; tiempos de Entrevista (`cameia.llm.timeout`, de CM-216); Jira.

## 17. Hechos, inferencias y decisiones

F-1 a F-4, F-6 a F-12: hecho (ruta o jar). F-5: inferencia (T0 la convierte en hecho). F-10: hecho para el flujo de producción; el de preproducción se confirma en V-ENT. H-3: hipótesis. D-1, D-2, D-5 y D-6: decisiones de Paula (7-nov-2026); D-3: en manos de DevOps.

## 18. Puerta de listo

`LISTA PARA EJECUTAR`: criterios con prueba; matriz de validación y casos borde con valores literales; sin ruta nueva al genérico; seguridad revisada; contraste con el código hecho (§2); línea base definida; integración con CM-296 anotada. Solo Paula aprueba.
