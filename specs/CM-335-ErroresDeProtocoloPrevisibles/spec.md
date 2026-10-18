# CM-335 · Rechazos de protocolo del Gateway

| Dato | Valor |
|---|---|
| Tarea Jira | CM-335 (tarea, Sprint 3), responsable Paula Andrea Muñoz Delgado |
| Repositorio | `cameia-gateway`, base `origin/develop` = `ee5f78b` (se vuelve a anotar el SHA al preparar la rama) |
| Depende de | Nada sin fusionar |
| Estado | **BORRADOR. Pendiente de aprobación de Paula y de su decisión D-1 (limitación documentada u opción B)** |
| Estimación | Opción A (recomendada): 1 a 1,5 h. Opción B: 4 a 5 h más. Se informa a Vela |
| Servicios hermanos | `cameia-perfil` y `cameia-cuentas` tienen su propia spec de la misma tarea; cada una se entiende sola |

## 1. Objetivo

Dejar escrito, probado y vigilado qué responde el Gateway cuando rechaza una petición malformada antes de que corra cualquier filtro suyo, porque hoy esas respuestas salen con cuerpo vacío y sin `code`, y la documentación del servicio dice «Respuestas sin código: Ninguna». Según la decisión D-1 de Paula, esto se resuelve como **limitación documentada con pruebas que la vigilan (opción A)** o **respondiendo JSON con `code` (opción B)**.

## 2. Evidencia (peticiones reales del 17-oct-2026)

Registros en `ejecucion/evidencia/CM-335/` de la carpeta raíz de trabajo: `65`, `66` (peticiones con token verificado del emulador de Firebase), `12-gateway-app.log`. Gateway empaquetado desde `develop` `ee5f78b`, perfil `local`, enrutando a Perfil y Cuentas locales.

| # | Petición | Respuesta | Quién la produce |
|---|---|---|---|
| E-1 | Encabezado con un carácter de control (`X-User-Id: a<BEL>`, `X-Foo: a<BEL>`) | 400, cuerpo vacío | Reactor Netty (decodificador HTTP) |
| E-2 | Encabezado de 9000 bytes | 431, cuerpo vacío | Reactor Netty (`TooLongHttpHeaderException`, aviso «Decoding failed» de `HttpServerOperations`) |
| E-3 | URL de 9000 bytes | 414, cuerpo vacío | Reactor Netty (`TooLongHttpLineException`, mismo aviso) |
| E-4 | `%zz` en la consulta o en la ruta | 400, cuerpo vacío, sin aviso en el registro | El adaptador de Reactor de WebFlux, al construir la petición (URI inválida) |
| E-5 | `%00` en la ruta | Pasa al servicio destino, que responde 400 con la página HTML de Tomcat; el Gateway la reenvía tal cual | Tomcat del servicio |
| E-6 | Formulario `a=%zz` en `PUT`/`PATCH`/`DELETE`/`POST` con token válido | Lo reenvía al servicio destino | Defecto del servicio destino, que se corrige en sus specs (Perfil y Cuentas); no del Gateway |
| E-7 | `?lang=%zz` con o sin token | 400, cuerpo vacío (E-4) | El Gateway lo bloquea antes que el servicio |
| E-8 | `TRACE`, `OPTIONS` sin `Origin` | 401 `AUTH_REQUIRED` con el formato del Gateway | `FirebaseAuthGlobalFilter` (sin problema) |
| E-9 | Cuerpo JSON de 20 MB con token | Llega a Perfil, que responde 422 | No hay límite de tamaño en el Gateway (fuera de alcance, §14) |

El Gateway ya borra los `X-User-*` que envía el cliente y usa el `uid` del token (`X-User-Id` con BEL o TAB enviado por el cliente no llega a los servicios).

## 3. Viabilidad técnica (verificada en el binario `reactor-netty-http` 1.3.7)

- **E-1, E-2 y E-3** los escribe `HttpTrafficHandler.sendDecodingFailures` con una respuesta fija de cuerpo vacío. No hay propiedad ni hook de Spring para cambiarla. Para responder JSON habría que insertar un manejador de Netty propio **antes** de `reactor.left.httpTrafficHandler` (nombre interno verificado en el binario) que, al ver una petición con `decoderResult` fallido, escriba la respuesta y cierre el canal.
- **E-4** ocurre más tarde, dentro del adaptador de WebFlux, antes de cualquier `WebFilter` o manejador de errores del Gateway; se cubriría con el mismo manejador de Netty validando la URI antes de pasarla.
- Costo y riesgo de la opción B: código de bajo nivel acoplado a nombres internos de una librería (se puede romper al subir la versión de Reactor Netty), en el único punto de entrada de todo el tráfico, con pruebas de socket y de canal; no cambia el estado HTTP, solo agrega el cuerpo.

## 4. Requisitos (EARS) — opción A (recomendada)

- **REQ-01** El sistema debe seguir respondiendo 400 (carácter de control en un encabezado; `%zz` o URI inválida en la consulta o la ruta), 431 (encabezado de unos 9000 bytes o más) y 414 (URL de unos 9000 bytes o más), con cuerpo vacío.
- **REQ-02** Una prueba con sockets reales debe fijar ese comportamiento: estado, cuerpo vacío y ausencia de `code`, de modo que un cambio de versión de Reactor Netty que lo altere (para bien o para mal) haga fallar la prueba y obligue a revisar la documentación.
- **REQ-03** `docs/errores.md` debe sustituir «Respuestas sin código: Ninguna.» por una tabla que liste cada rechazo de §2 (E-1 a E-5) con su estado, quién lo produce, el motivo por el que no lleva `code` y la prueba que lo vigila.
- **REQ-04** Un ADR debe registrar la decisión y la opción B con su costo, para reabrirla con datos si Frontend lo necesita.
- **REQ-05** Frontend debe recibir un aviso de una línea: «El Gateway responde 400, 414 y 431 con cuerpo vacío ante peticiones que un navegador normal no produce; se decide por el estado HTTP».

## 5. Requisitos (EARS) — opción B (solo si Paula la elige)

- **REQ-B1** El sistema debe responder los rechazos E-1, E-2, E-3 y E-4 con `application/json;charset=UTF-8` y el cuerpo `{"code","message"}` del formato del Gateway, conservando el estado (400, 431, 414) y agregando `X-Request-Id`.
- **REQ-B2** Códigos nuevos del catálogo cerrado: `REQUEST_MALFORMED` (400, «La petición no es válida.»), `REQUEST_URI_TOO_LONG` (414, «La dirección de la petición es demasiado larga.»), `REQUEST_HEADERS_TOO_LARGE` (431, «Los encabezados de la petición son demasiado grandes.»). **Los textos y nombres son propuestas: no se asumen hasta que Paula los confirme (PENDIENTE de Paula).**
- **REQ-B3** El manejador de Netty debe quedar registrado con `NettyServerCustomizer`, antes de `reactor.left.httpTrafficHandler`, y una prueba de arranque debe fallar si ese nombre interno no existe en la versión usada.
- **REQ-B4** El manejador no debe registrar el contenido de los encabezados ni de la URL.

La opción B se detalla en tarjetas solo si Paula la elige; hoy no hay tarjetas de B para no escribir código especulativo.

## 6. Contrato

Opción A: ninguno; solo documentación. Opción B: aditivo (cuerpo donde antes no había).

## 7. Validación y casos de prueba con datos literales (opción A)

| Caso | Petición (literal) | Resultado vigilado | Prueba |
|---|---|---|---|
| Carácter de control | `GET /api/v1/users/health` con `X-Foo: a<BEL>` | 400, cuerpo vacío | `NettyDecodingRejectionsTest.controlCharacterInHeader_returns400WithEmptyBody` |
| Encabezado grande | encabezado de 9000 bytes | 431, cuerpo vacío | `…oversizedHeader_returns431WithEmptyBody` |
| Encabezado en el límite | encabezado de 7000 bytes | no es 431 (el límite por defecto de Netty es 8192 en total) | `…headerBelowLimit_isNotRejected` |
| URL grande | URL de 9000 bytes | 414, cuerpo vacío | `…oversizedUrl_returns414WithEmptyBody` |
| Consulta mala | `GET /api/v1/profiles/professional-roles?lang=%zz` | 400, cuerpo vacío | `…malformedQuery_returns400WithEmptyBody` |
| Ruta mala | `GET /api/v1/profiles/%zz` | 400, cuerpo vacío | `…malformedPath_returns400WithEmptyBody` |
| Sin `code` | los cinco anteriores | `Content-Length: 0` o sin cuerpo; no hay `code` | las mismas, con `assertThat(body).isEmpty()` |
| El Gateway sigue sano | `GET /actuator/health` después de los rechazos | 200 | `…gatewayKeepsServing_afterRejections` |

## 8. Base de datos

El Gateway no tiene base de datos.

## 9. Seguridad (ASVS nivel 1 y OWASP API Top 10)

| Control | Qué hace | Prueba |
|---|---|---|
| API8 / ASVS 14.1 | Documenta que el Gateway no refleja el contenido rechazado: los cuerpos están vacíos | `NettyDecodingRejectionsTest` |
| API4 | Los límites de encabezado y de URL de Netty se mantienen | `…oversizedHeader…`, `…oversizedUrl…` |
| Defecto en servicios | E-6 y E-5 se corrigen o documentan en Perfil y Cuentas; aquí solo se anota | specs hermanas |

## 10. Atributos de calidad de CAMEIA

| Atributo | Métrica | Límite | Cómo | Prueba |
|---|---|---|---|---|
| AC-0002 (disponibilidad) | DES-04 | 5xx < 1 % | Los rechazos son 4xx | `NettyDecodingRejectionsTest` |
| AC-0006 (interoperabilidad) | INT | Comportamiento conocido y documentado | REQ-02, REQ-03 | la misma |
| Transversal | MAN-01 | ≥ 90 % en lo nuevo | La tarea no agrega código de producción en la opción A; la medición es sobre las pruebas que corren | `./mvnw.cmd clean verify` |

## 11. Errores

Opción A: ningún código nuevo. Opción B: los de REQ-B2.

## 12. Pruebas y comandos

`NettyDecodingRejectionsTest` nueva, en `src/test/java/tech/cameia/gateway/` (paquete `server`), con `@SpringBootTest(webEnvironment = RANDOM_PORT, classes = TestFirebaseConfig.class)`, `@ActiveProfiles("test")` y las mismas propiedades de `GlobalCorsConfigTest`; cliente de sockets `RawHttpClient` en pruebas (los rechazos ocurren antes de la autenticación, así que no hace falta token). Comandos: `./mvnw.cmd -B -ntp clean verify`; en Docker: `docker compose run --rm verify`.

## 13. Postman

Sin pasos nuevos: los rechazos no son reproducibles con Newman (reescribe la URL y los encabezados). La evidencia son las pruebas de sockets y las peticiones con `curl` guardadas en la carpeta de evidencia.

## 14. Fuera de alcance

El límite de tamaño del cuerpo (E-9, OWASP API4; necesita tarea propia, junto con Cloud Run); los defectos de los servicios destino (E-6; Perfil y Cuentas); el formato `{code,message}` frente a `ProblemDetail` (decisión de equipo vigente).

## 15. Decisiones

| # | Decisión | Por qué | Descartado | Quién |
|---|---|---|---|---|
| D-1 | **PENDIENTE de Paula:** A (limitación documentada) o B (manejador de Netty que responde JSON) o C (B más validar la URI antes) | Recomendación: **A** | — | Paula |
| D-2 | La recomendación es A porque (1) ningún navegador ni la interfaz de CAMEIA produce estas peticiones: las envía un script; (2) el estado HTTP ya es el correcto y el cliente decide por él; (3) B depende de nombres internos de Reactor Netty y toca el único punto de entrada de todo el tráfico, con riesgo en cada actualización; (4) lo alcanzable por un usuario real (formularios mal codificados) se corrige en Perfil y Cuentas | Costo y riesgo frente a beneficio | B y C | Backend |
| D-3 | La prueba con sockets vigila el comportamiento aunque no se cambie | Evita que la documentación mienta tras una actualización | Solo documentar | Backend |

## 16. Preguntas

**PENDIENTE de Paula:** D-1. Si elige B o C, se agregan tarjetas y los nombres, textos y estados de REQ-B2 se confirman con ella antes de escribir código. Nada de Perfil ni de Cuentas depende de esta decisión.

## 17. Lista de verificación (opción A)

| # | Qué | Cómo | Esperado |
|---|---|---|---|
| V-01 | REQ-01 y REQ-02 | `NettyDecodingRejectionsTest` | Verde con los estados de §7 |
| V-02 | REQ-03 | Lectura de `docs/errores.md` | Tabla de E-1 a E-5 con prueba |
| V-03 | REQ-04 | ADR 0003 | Opción B con costo |
| V-04 | Regresión | `./mvnw.cmd -B -ntp clean verify` y `GatewayArchTest` | Verde |
| V-05 | Cobertura | `jacoco.csv` | Sin caída frente a la línea base |
