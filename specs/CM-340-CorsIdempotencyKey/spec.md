# CM-340 · CORS del Gateway: admitir `Idempotency-Key` desde el navegador

Estado: **APROBADA por Paula el 24-oct-2026** (D-1 = A, D-2 = A). Base: `origin/develop` `84d7e02`. Repo: `cameia-gateway`.

## 1. Contexto

- Desde la web, «Iniciar entrevista» (HU-4.4, crear la sesión) nunca crea la entrevista. El navegador hace primero una
  consulta previa (`OPTIONS`, llamada *preflight*) preguntando qué encabezados puede enviar. El Gateway responde que
  `Idempotency-Key` no está admitido, y el navegador cancela el `POST` real (`Failed to fetch`).
- Causa: `src/main/resources/application.yml` (líneas 87 a 90), `allowedHeaders` solo tiene `Authorization`,
  `Content-Type` y `X-Request-Id`.
- Por qué no se vio antes: las pruebas de la creación de la sesión se hicieron con un script desde el servidor, donde
  CORS no existe. La prueba actual de CORS (`GlobalCorsConfigTest`) solo comprueba el origen, nunca los encabezados.
- Sin el encabezado, el servicio de entrevistas crea la sesión bien (201, comprobado por el tester el 24-oct).
- **No es solo crear la sesión.** `cameia-web` envía `Idempotency-Key` en tres llamadas
  (`interview-setup/api/interviewSession.api.ts:26`, `interview-session/api/interviewSession.api.ts:39` y `:82`):
  crear la sesión, registrar la respuesta escrita y registrar la respuesta por voz. Las dos últimas fallarían igual.

## 2. Alcance

Dentro:

- Agregar `Idempotency-Key` a `allowedHeaders` del CORS global.
- Pruebas del preflight por encabezado (hoy no existen).
- Documentación del Gateway (`CLAUDE.md`, `docs/COMO-FUNCIONA.md`).
- Comprobación real del preflight en el entorno tras el despliegue.

Fuera (con destino):

| Qué | Destino |
|---|---|
| Exponer `X-Request-Id` al JavaScript (`exposedHeaders`) | `GW-TBD-10` de `docs/COMO-FUNCIONA.md`; la web aún no lo lee (CM-298). Ver decisión D-1 |
| Cualquier cambio en Entrevista | Ninguno: el servicio cumplió todo lo probado |
| Texto propio de `JOB_OFFER_INVALID_CHARACTERS` en la pantalla | Frontend, con texto que debe aportar el Product Owner (respuesta de Ana Sofía, 23-oct) |

## 3. Requisitos (EARS)

| ID | Requisito | Prueba |
|---|---|---|
| REQ-CORS-01 | Cuando un navegador envíe un preflight (`OPTIONS`) desde un origen admitido con `Access-Control-Request-Method: POST` y `Access-Control-Request-Headers: idempotency-key`, el Gateway responderá 200 con `Access-Control-Allow-Origin` igual al origen y `Access-Control-Allow-Headers` que incluya `idempotency-key` | `preflight_idempotencyKey_isAllowed` |
| REQ-CORS-02 | El nombre del encabezado se admitirá sin distinguir mayúsculas (los navegadores lo envían en minúsculas) | misma prueba con `idempotency-key` y otra con `Idempotency-Key` |
| REQ-CORS-03 | Seguirán admitidos `Authorization`, `Content-Type` y `X-Request-Id` | `preflight_existingHeaders_stillAllowed` |
| REQ-CORS-04 | El Gateway no admitirá `X-User-Id`, `X-User-Plan` ni `X-User-Email-Verified` en el preflight: solo el Gateway las emite | `preflight_userIdentityHeaders_areRejected` |
| REQ-CORS-05 | Un preflight con un encabezado no admitido cualquiera (`X-Otro`) se rechaza y no recibe `Access-Control-Allow-Origin` | `preflight_unknownHeader_isRejected` |
| REQ-CORS-06 | Un preflight desde un origen no admitido con `Idempotency-Key` sigue sin recibir `Access-Control-Allow-Origin` | `preflight_idempotencyKeyFromUnknownOrigin_isRejected` |
| REQ-CORS-07 | La ruta de crear la sesión (`/api/v1/interviews/sessions`) acepta el preflight con `Idempotency-Key` (el CORS es global, no por ruta) | parametrizada en REQ-CORS-01 con `/api/v1/interviews/sessions` y `/api/v1/interviews/sessions/abc/answers` |

Valor exacto del encabezado: `Idempotency-Key`. No se agrega comodín (`*`): la lista sigue cerrada.

## 4. Errores (regla de errores visibles)

- El rechazo de un preflight es respuesta del propio CORS, que lee el navegador, no la persona. No hay mensaje de
  negocio que mostrar. La persona ve el error de la pantalla («Ocurrió un error…»), que es el comportamiento que
  esta tarea corrige. No hay ruta nueva hacia el error genérico (`INTERNAL_ERROR`).
- Con el encabezado ya admitido, los errores del servicio (409 `PROFILE_SYNC_PENDING`, 422 con campos, 404, etc.)
  llegan al navegador, que hoy no los puede ver. Eso es lo que Frontend ya maneja.

## 5. Análisis resumido

| Punto | Resultado |
|---|---|
| Contrato | Aditivo: un encabezado más aceptado. No cambia ruta, cuerpo ni respuesta. Frontend no cambia nada |
| Datos | Sin migración |
| Encabezados que la web envía | `Authorization`, `Content-Type`, `Accept-Language`, `Idempotency-Key` y, a veces, `X-Request-Id`. `Accept-Language` y `Accept` son de los que el navegador envía sin preflight, no hace falta listarlos |
| Qué más puede faltar | Nada más: se recorrió `httpClient.ts` y los tres usos de `Idempotency-Key` |
| Seguridad (ASVS N1, API Top 10) | `Idempotency-Key` es una clave de repetición que el cliente elige, no una identidad: no abre suplantación. La identidad sigue viniendo solo del token. El servicio la liga al dueño (`keys` con `owner`), así que otro usuario con la misma clave no ve la sesión ajena. Lista cerrada, sin comodín, con credenciales y origen configurado |
| Defectos del código que se toca | (a) `GlobalCorsConfigTest` no verifica encabezados ni métodos: se amplía; (b) el rechazo de `X-User-*` solo estaba «comprobado a mano» en `COMO-FUNCIONA.md:428`: queda con prueba automática |
| Reutilización | Se amplía `GlobalCorsConfigTest` (mismo arranque y mismas propiedades). No se crea clase nueva ni dependencia |

## 6. Atributos de calidad de CAMEIA

| Atributo | Cómo lo cumple la tarea | Métrica | Evidencia |
|---|---|---|---|
| Fiabilidad (FIA) | El flujo principal de la entrevista deja de fallar en el navegador | 0 fallos de preflight en las 3 llamadas con `Idempotency-Key`; antes 3 de 3 | 3 preflights reales en el entorno |
| Seguridad (SEG) | Lista cerrada; identidad `X-User-*` sigue sin admitirse | 0 encabezados de identidad admitidos | REQ-CORS-04 y 05 |
| Interoperabilidad (IOP) | Gateway y web hablan el mismo contrato de encabezados | Los encabezados que envía la web ⊆ `allowedHeaders` | prueba que recorre los cuatro encabezados |
| Mantenibilidad (MAN) | El hueco queda vigilado por una prueba por encabezado | Prueba falla si se quita `Idempotency-Key` | comprobar en rojo antes del cambio |
| Rendimiento, Disponibilidad (DES) | Sin efecto: el preflight ya está cacheado 1 h (`maxAge: 3600`) | Sin cambio | — |

## 7. Pruebas

Ampliar `src/test/java/tech/cameia/gateway/config/GlobalCorsConfigTest.java` (mismo `@SpringBootTest`, mismas propiedades):

| Prueba | Entrada | Esperado |
|---|---|---|
| `preflight_idempotencyKey_isAllowed` (parametrizada en ruta y en mayúsculas/minúsculas) | `OPTIONS`, `Origin: https://cameia.app`, método `POST`, `Access-Control-Request-Headers: idempotency-key` / `Idempotency-Key` | 200, `Allow-Origin` = `https://cameia.app`, `Allow-Headers` contiene `idempotency-key` sin distinguir mayúsculas |
| `preflight_existingHeaders_stillAllowed` | `authorization,content-type,x-request-id` | 200 y los tres en `Allow-Headers` |
| `preflight_allFourWebHeaders_inOneRequest` | `authorization,content-type,idempotency-key,x-request-id` | 200 |
| `preflight_userIdentityHeaders_areRejected` (3 valores) | `x-user-id`, `x-user-plan`, `x-user-email-verified` | sin `Allow-Origin` |
| `preflight_unknownHeader_isRejected` | `x-otro` | sin `Allow-Origin` |
| `preflight_idempotencyKeyFromUnknownOrigin_isRejected` | `Origin: https://evil.example.com` | sin `Allow-Origin` |

**Trampa:** no escribir el código de estado esperado de los rechazos de memoria. Primero ejecutar la petición contra el
Gateway de prueba y fijar lo que responde de verdad (Spring puede dar 403 o 200 sin cabeceras CORS). La aserción que
manda es la ausencia de `Allow-Origin`.

**Comprobar en rojo:** correr `preflight_idempotencyKey_isAllowed` antes del cambio de `application.yml`; debe fallar.

## 8. Comprobación en el entorno (tras el despliegue)

Tres preflights reales contra `https://api-staging.cameia.app` con el `Origin` de la web de staging, para `POST` a
`/api/v1/interviews/sessions`, `/api/v1/interviews/sessions/{id}/answers` y `.../answers/audio`, con
`Access-Control-Request-Headers: authorization,content-type,idempotency-key`. Esperado: 200 y `Allow-Headers` con
`idempotency-key`. Más un `POST` real desde la web de staging si el tester lo repite. Informe breve en
`analisis/revisiones/prueba-final-CM-340_staging.md` y, si se pide, `docs/verificaciones/V-NN-cors.md`. Sin tocar datos ni tokens.

## 9. Decisiones

| ID | Decisión | A (recomendada) | B |
|---|---|---|---|
| D-1 | ¿Se aprovecha para exponer `X-Request-Id` (`exposedHeaders`)? | **No.** La web no lo lee (fuera de CM-298); es la deuda `GW-TBD-10` y mezclarla agranda el riesgo de un arreglo urgente | Sí, una línea más y una prueba |
| D-2 | ¿Dónde va? | **Un PR por CM**, rama `CM-340-cors-idempotency-key` desde `develop`; en el Gateway, que es de Backend | — |

Alternativa descartada: comodín `*` en `allowedHeaders`. Es incompatible con credenciales en varios navegadores y
admitiría `X-User-*`, que la regla del Gateway prohíbe (REQ-NF-01).

## 10. Plan de commits, estimación y entrega

| Commit | Contenido |
|---|---|
| 1 | `fix(gateway): admitir Idempotency-Key en el CORS` más sus pruebas, juntas para que el commit compile y pase |
| 2 | `docs(gateway): cabeceras admitidas por CORS` (`CLAUDE.md` línea 151, `docs/COMO-FUNCIONA.md` líneas 422 y 428, y la nota de que `Idempotency-Key` es de repetición, no de identidad) |

- Estimación honesta: **1,5 h** de código y pruebas, más 0,5 h de comprobación en el entorno tras el despliegue.
- Verificación: pruebas del módulo (`GlobalCorsConfigTest`) mientras se construye; al terminar, una vez, `clean verify` y
  cobertura de lo tocado. Newman no aplica: el Gateway no tiene colección propia.
- Jira: CM-340 pasa a «En curso» al empezar y a «En revisión» con PR y prueba en el entorno. Pasar CM-207 y CM-264 a
  «Finalizado» es del tester, que repite CA-4.4.1, CA-4.4.17 y CA-4.4.19 cuando se avise en la tarjeta.
- Aviso: el tester y Frontend se enteran por el comentario de la tarjeta; no hay cambio de contrato.

## 11. Preguntas abiertas

Ninguna. Decisión humana: **Paula, 24-oct-2026, D-1 = A** (no exponer `X-Request-Id`; sigue como `GW-TBD-10`) y **D-2 = A** (un PR, rama `CM-340-cors-idempotency-key`, worktree aparte).
