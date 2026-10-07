# Spec — CM-260: toda respuesta JSON del Gateway declara su charset (ASVS 4.1.1)

- **Tarea:** CM-260 · Error · Sprint 2 · sin padre · responsable: Paula Andrea Muñoz Delgado
- **Repositorio:** `cameia-gateway`, rama `CM-260-charset-content-type-gateway`, creada desde `origin/develop` (`4c79b6e`)
- **Estado:** spec **aprobada por Paula el 6-oct-2026**; las cuatro preguntas están cerradas (la 4, por RFC 9110, sin consulta)
- **Estimación:** ≈ 1 h (de la reserva del sprint). Un solo PR de unas 40 líneas de diff.
- **Atributos de calidad que toca:** seguridad (ASVS 4.1.1) y compatibilidad de contrato (el valor de un encabezado cambia).

## 1. Contexto

OWASP ASVS 5.0.0, requisito 4.1.1: toda respuesta HTTP con cuerpo debe llevar un `Content-Type` que declare el juego de
caracteres (por ejemplo `application/json; charset=utf-8`). Sin él, el cliente puede interpretar los bytes con otro charset.

El Gateway (WebFlux) escribe cuerpos JSON a mano en dos sitios y en ninguno más (búsqueda de `writeWith`, `setComplete` y
`setStatusCode` en `src/main/java`):

| Lugar | Qué escribe | `Content-Type` hoy |
|---|---|---|
| `GlobalErrorHandler.handle` (`exception/GlobalErrorHandler.java:105-107`) | `404`, `502`, `503`, `504`, `500` y el `401` de respaldo, con el catálogo cerrado | `application/json;charset=UTF-8` (constante privada `APPLICATION_JSON_UTF8`, líneas 77-78). **Correcto.** |
| `FirebaseAuthGlobalFilter.writeUnauthorized` (`filter/FirebaseAuthGlobalFilter.java:451-463`, línea 460) | `401 AUTH_REQUIRED` por token ausente, vacío, de esquema no Bearer o inválido | `MediaType.APPLICATION_JSON` → `application/json` **sin charset. Es el defecto.** |

Los demás cuerpos que salen del Gateway los escriben los microservicios y el Gateway los reenvía sin reescribirlos (hay una
prueba que lo fija: `FirebaseAuthGlobalFilterTest.downstreamProblemJson_isReturnedUnchanged`, línea 740). La pregunta 2 trata
si hay que verificar esos servicios. Los puntos de actuator (`health`, `info`) los escribe Spring Boot: la pregunta 3.

Un defecto previo del mismo tipo ya se corrigió solo en el manejador global; el filtro quedó fuera.

## 2. Alcance

Dentro: el `Content-Type` del `401` del filtro; el charset de las respuestas de actuator (`/actuator/**`, medido en T1: `application/vnd.spring-boot.actuator.v3+json` sin charset, decisión de Paula del 6-oct); una prueba por cada camino de `401` del filtro; una prueba que cubra los estados
del catálogo del manejador global; una regla de arquitectura que impida volver a usar `MediaType.APPLICATION_JSON` en código de producción.

Fuera: el `403 EMAIL_NOT_VERIFIED` de CM-179 (nacerá con el charset correcto usando la misma constante del filtro; se avisa en la
spec de esa tarea); cambios en Cuentas, Perfil o Entrevista; cualquier cambio de cuerpo, estado o código de error.

## 3. Requisitos funcionales (EARS)

| ID | Requisito |
|---|---|
| REQ-CS-01 | Cuando el filtro de autenticación responda `401` por **falta** del encabezado `Authorization`, el sistema debe fijar `Content-Type: application/json;charset=UTF-8`. |
| REQ-CS-02 | Cuando el filtro responda `401` por encabezado `Authorization` con **esquema distinto de `Bearer`**, el `Content-Type` debe ser `application/json;charset=UTF-8`. |
| REQ-CS-03 | Cuando el filtro responda `401` por **token vacío** (`Authorization: Bearer ` sin valor), el `Content-Type` debe ser `application/json;charset=UTF-8`. |
| REQ-CS-04 | Cuando el filtro responda `401` por **token inválido** (Firebase lanza `FirebaseAuthException` o `IllegalArgumentException`), el `Content-Type` debe ser `application/json;charset=UTF-8`. |
| REQ-CS-05 | Mientras el Gateway esté en ejecución, cada estado del catálogo de `GlobalErrorHandler` (`401`, `404`, `502`, `503`, `504`) y el estado por defecto (`500`) debe responder con `Content-Type: application/json;charset=UTF-8`. |
| REQ-CS-06 | El código de producción del Gateway no debe acceder a `MediaType.APPLICATION_JSON`; una prueba de arquitectura debe fallar si lo hace. |
| REQ-CS-08 | Cuando el Gateway responda una petición a `/actuator/**` con un `Content-Type` que no declare `charset`, el sistema debe añadir `charset=UTF-8` conservando el tipo (`application/vnd.spring-boot.actuator.v3+json;charset=UTF-8`). Las respuestas sin `Content-Type` y las de otras rutas no se modifican. |
| REQ-CS-07 | El cambio no debe alterar el estado HTTP, el cuerpo (`{"code":"AUTH_REQUIRED","message":"…"}`), los mensajes, el log `WARN` ni la cabecera `X-Request-Id` de ninguna respuesta. |

Valor literal del encabezado: lo que produce Spring al serializar `new MediaType("application", "json", StandardCharsets.UTF_8)`,
es decir `application/json;charset=UTF-8` (sin espacio tras el punto y coma). Es equivalente a `application/json; charset=UTF-8`
del CA-1.2.13 (RFC 9110 §8.3.1); por eso las pruebas comparan el texto exacto que emite Spring (pregunta 4, cerrada sin consulta).

## 4. Reglas, validaciones y errores

No hay entrada nueva que validar ni código de error nuevo. El catálogo de errores del Gateway no cambia:

| Causa | Estado | `code` | Mensaje (sin cambios) |
|---|---|---|---|
| Sin `Authorization` o esquema no Bearer o token vacío | 401 | `AUTH_REQUIRED` | `Token de acceso requerido` |
| Token inválido | 401 | `AUTH_REQUIRED` | `Token de acceso inválido` |

Casos borde de `estandar-estricto.md` §C: los de entrada (nulos, límites, Unicode en campos, fechas, duplicados, idempotencia,
concurrencia, colecciones, carga) **no aplican**: el cambio no recibe ni procesa datos, solo fija un encabezado de salida. Aplican y
se prueban: mensajes con tilde (`inválido`) siguen bien codificados en UTF-8 (REQ-CS-04, la prueba lee el cuerpo como UTF-8 y compara
`Token de acceso inválido`), respuesta ya confirmada (el manejador no reescribe, prueba existente), dependencia caída (`503`/`504`, REQ-CS-05).

## 5. Contrato

- Cambia solo el valor de `Content-Type` del `401` del filtro: de `application/json` a `application/json;charset=UTF-8`.
- Consumidor: `cameia-web`, que lee estos `401` como JSON. Un cliente que lee JSON no se ve afectado por un charset explícito. Aviso a
  Frontend: una línea en el documento a ese rol que ya está en `comunicaciones/` (un solo documento por rol).
- No hay endpoint nuevo: no hay OpenAPI que actualizar (el Gateway no usa springdoc). Se actualiza el Javadoc de `writeUnauthorized`.

## 6. Datos

Sin base de datos ni migración (el Gateway no persiste).

## 7. Seguridad y calidad

- ASVS 4.1.1: se cumple en las dos rutas de escritura manual. OWASP API8 (configuración incorrecta de seguridad): se reduce el
  riesgo de interpretación errónea del cuerpo.
- Sin datos sensibles nuevos en logs: el `WARN` de rechazo no cambia (REQ-CS-07).
- Rendimiento: sin efecto (la constante se crea una vez).
- Mantenibilidad: la regla ArchUnit evita la reaparición del defecto (REQ-CS-06).

## 8. Decisiones

| # | Decisión | Porqué | Alternativas descartadas |
|---|---|---|---|
| D1 | Una constante `APPLICATION_JSON_UTF8` **propia del filtro**, no compartida con el manejador | `AGENTS.md` §3: `filter` no importa `exception` («cada uno escribe su propia respuesta») y `exception` es independiente; son 2 usos y la regla de tres pide extraer a la tercera. El CM-179 (403 en el mismo filtro) reutiliza la del filtro: es un uso más **dentro de la misma clase**, no una tercera clase, así que no obliga a extraerla | Extraer a un paquete nuevo (`http`/`common`): cambia la estructura de paquetes que el `AGENTS.md` fija y agrega un paquete para una constante. Importar la constante del manejador desde el filtro: rompe la regla documentada. Es la **pregunta 1** (el análisis previo proponía extraerla; contradice el `AGENTS.md`) |
| D2 | Regla ArchUnit contra `MediaType.APPLICATION_JSON` | Barata (6 líneas) y evita reintroducir el defecto; las pruebas de comportamiento solo cubren los caminos que existen hoy. Límite conocido: no detecta un tipo sin charset escrito como texto (`MediaType.parseMediaType("application/json")` o `"application/json"` en un encabezado); eso lo cubren las pruebas de comportamiento de cada camino | Solo pruebas de comportamiento: no detectan una ruta de escritura nueva |
| D3 | El valor lo produce Spring (`new MediaType("application","json",UTF_8)`), no una cadena literal | Mismo mecanismo que ya usa el manejador; sin riesgo de errores de tipeo | `MediaType.parseMediaType("application/json; charset=utf-8")`: más texto y el mismo resultado |

| D4 | Un `WebFilter` (`ActuatorCharsetWebFilter`) que, solo para `/actuator/**`, completa el charset antes de confirmar la respuesta (`beforeCommit`) | Spring Boot no ofrece propiedad para el charset del tipo de actuator; el filtro es local, no toca las rutas proxificadas y conserva el tipo que eligió Spring. Es un `WebFilter` y no un `GlobalFilter` porque actuator no pasa por las rutas del Gateway | Reescribir el `Content-Type` de todas las respuestas: alteraría lo que los microservicios devuelven y rompe `downstreamProblemJson_isReturnedUnchanged`. Cambiar el tipo de actuator a `application/json`: cambia el contrato de las sondas |

**Decisión humana (HITL):** D1 y D2 aprobadas por Paula el 6-oct-2026; D4 y la inclusión de actuator en el alcance, por Paula el 6-oct-2026 con la salida real de T1.

## 9. Preguntas abiertas (rondas de 6; aquí son 4)

| # | Pregunta | A quién | Recomendación | Bloquea |
|---|---|---|---|---|
| 1 | ¿Constante propia del filtro (D1) o paquete compartido? | **Respondida (Paula, 6-oct): propia del filtro** | Constante propia: respeta `AGENTS.md` §3 y la regla de tres | El código del bloque |
| 2 | Los `application/problem+json` de Cuentas y los JSON de Perfil/Entrevista los escriben esos servicios y el Gateway los reenvía tal cual. ¿Se verifica su charset en una tarea aparte? | **Respondida (Paula, 6-oct): sí, como verificación de solo lectura dentro de CM-283** | Sí, como verificación de solo lectura dentro de CM-283 o una tarea corta; no se mezcla con CM-260 | Nada de CM-260 |
| 3 | Las respuestas de `/actuator/health` y `/actuator/info` las escribe Spring Boot. ¿Se mide su `Content-Type` en la tarea 1 y, si falta el charset, se corrige dentro de CM-260? | **Respondida (Paula, 6-oct): se mide en T1; si falta el charset, se trae la salida real y Paula decide** | Medir primero (tarea T1); si no declara charset, decidir con la salida real en la mano | Alcance si el resultado es negativo |
| 4 | El CA-1.2.13 escribe `application/json; charset=UTF-8` (con espacio) y Spring emite `application/json;charset=UTF-8` | **Cerrada sin consulta (cambio sin alternativa):** son el mismo valor según RFC 9110 §8.3.1 (los parámetros del tipo de contenido no dependen del espacio); las pruebas comparan el texto exacto que emite Spring (`application/json;charset=UTF-8`) | — | Nada |

Además, la descripción de Jira pide al reportante «endpoints afectados, evidencia y versión probada»: **acción para Vela** (completarla);
el hallazgo de esta spec los da: los cuatro caminos de `401` del filtro, versión `4c79b6e`.

## 10. Casos de prueba (valores literales)

| Prueba | Entrada | Resultado esperado | Requisito |
|---|---|---|---|
| `missingAuthorization_returns401WithUtf8Charset` | `GET /api/v1/profiles/me` sin `Authorization` | 401, `Content-Type` = `application/json;charset=UTF-8`, `$.code` = `AUTH_REQUIRED` | REQ-CS-01 |
| `nonBearerScheme_returns401WithUtf8Charset` | `Authorization: Basic abc` | ídem | REQ-CS-02 |
| `emptyBearerToken_returns401WithUtf8Charset` | `Authorization: Bearer ` | ídem | REQ-CS-03 |
| `invalidToken_returns401WithUtf8Charset` | `Bearer token-invalido`, Firebase lanza `FirebaseAuthException` | ídem y cuerpo `Token de acceso inválido` | REQ-CS-04 |
| `firebaseIllegalArgument_returns401WithUtf8Charset` | Firebase lanza `IllegalArgumentException` | ídem | REQ-CS-04 |
| `everyCatalogStatus_declaresUtf8Charset` (parametrizada: 401, 404, 502, 503, 504, 500) | `ResponseStatusException` con cada estado; para 500, `RuntimeException` | `Content-Type` = `application/json;charset=UTF-8` y código del catálogo | REQ-CS-05 |
| `productionCode_doesNotUseBareApplicationJson` (ArchUnit) | clases de `tech.cameia.gateway` | falla si alguna accede a `MediaType.APPLICATION_JSON` | REQ-CS-06 |
| `actuatorHealth_declaresUtf8Charset` | `GET /actuator/health` sin token | 200, `Content-Type` = `application/vnd.spring-boot.actuator.v3+json;charset=UTF-8` | REQ-CS-08 |
| `actuatorInfo_declaresUtf8Charset` | `GET /actuator/info` | ídem | REQ-CS-08 |
| `ActuatorCharsetWebFilterTest` (unitaria) | `/actuator/x` con tipo sin charset; con tipo que ya trae charset; sin tipo; `/api/x` sin charset | solo el primero cambia; los demás quedan intactos | REQ-CS-08 |
| Pruebas existentes (`missingAuthorization_returns401`, `unauthorizedResponse_includesRequestId`, `errorResponse_isUnchanged`, `GatewayErrorMappingTest`) | sin cambios | siguen en verde | REQ-CS-07 |

Cobertura: el único cambio de producción es una constante y una línea; ambas quedan ejecutadas por las pruebas anteriores (meta ≥ 90 % de líneas y ramas del código modificado, que serán 100 %).

## 11. Fuera de alcance y riesgos

- Riesgo: ninguno de producto. Si una prueba existente compara `application/json` exacto, se ajusta (se buscó: ninguna lo hace; `GlobalErrorHandlerLoggingTest` línea 106 ya espera el charset).
- No se toca la configuración de CORS ni de `application*.yml`.
- Riesgo de D4: una sonda de plataforma que compare el `Content-Type` exacto. No se ha verificado qué comparan las sondas de la plataforma; se avisa a DevOps en el PR.
