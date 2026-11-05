# ADR 0003 · Rechazos de protocolo del Gateway

## Estado

Aceptada. Decisión de Backend, 17 de octubre de 2026; aprobada por Paula Andrea Muñoz Delgado el 19 de octubre de 2026.

## Contexto

Reactor Netty y el adaptador de WebFlux rechazan algunas peticiones mal formadas antes de que corra cualquier filtro del Gateway:

- un encabezado con un carácter de control, sin dos puntos o con espacios en el nombre (400);
- encabezados cuyo total supera 8192 bytes (431) y una línea de petición de más de 4096 bytes (414);
- una dirección con `%zz` o `%` incompleto en la ruta o en la consulta, un espacio en la ruta, una ruta vacía o una versión HTTP ilegible (400);
- longitudes de cuerpo contradictorias o fragmentos con un tamaño inválido (400).

Esas respuestas salen con el estado correcto, pero con el cuerpo vacío y sin `code` ni `X-Request-Id`. El Gateway conserva su formato
`{"code","message"}` con un catálogo cerrado, así que no puede devolver la forma común de Cuentas y Perfil (`application/problem+json`).

Medido con sockets contra el jar empaquetado: ninguna de estas peticiones la produce un navegador ni la interfaz de CAMEIA, y el
Gateway sigue sirviendo después de recibirlas (50 rechazos en paralelo, sin ningún 5xx). Todo lo demás que llega a un servicio lo
decide el servicio destino: el Gateway no limita el cuerpo, no valida `Accept` y no lee formularios ni `multipart`.

## Decisión

- **D-1 · Limitación documentada y vigilada, sin código de producción.** El Gateway sigue respondiendo esos rechazos con su estado
  HTTP y el cuerpo vacío. `docs/errores.md` lista cada uno con quién lo produce y la prueba que lo vigila.
- **D-2 · Pruebas con sockets reales** (`NettyDecodingRejectionsTest`, con `RawHttpClient`) fijan el estado, el cuerpo vacío, la
  ausencia de `X-Request-Id` y los límites exactos (n−1 y n+1). Si una actualización de Reactor Netty cambia algo, para bien o para
  mal, la prueba falla y obliga a revisar la documentación.
- **D-3 · El cliente decide por el estado HTTP.** Frontend recibe el aviso de que 400, 414 y 431 pueden llegar con cuerpo vacío.
- **D-4 · El Gateway reenvía el cuerpo y el contenido sin interpretarlos.** No pone tope al tamaño del cuerpo, no valida `Accept` ni
  lee formularios o `multipart`: responde el servicio destino. Un `multipart` grande lo rechaza el destino con 413
  `REQUEST_BODY_TOO_LARGE` (Cuentas y Perfil) y el Gateway devuelve esa respuesta sin tocarla (`BodyForwardingTest`).
- **D-5 · El límite de encabezados del Gateway es el total de bytes (8192), no el número.** Acepta cientos de encabezados pequeños;
  el Tomcat del servicio destino rechaza 99 o más con su página HTML de 400, y eso se documenta en `docs/errores.md`.
- **D-6 · El catálogo cerrado agrega `METHOD_NOT_ALLOWED` (405) y `NOT_ACCEPTABLE` (406)** para los dos fallos previsibles que Actuator produce: un método no admitido y un `Accept` que no ofrece. Son el único cambio de producción de la tarea (dos entradas en `GlobalErrorHandler`); un estado fuera del catálogo conserva su estado y sale con `INTERNAL_ERROR`.

## Por qué

Los rechazos de protocolo ocurren dentro de la librería, antes de la primera línea de código del Gateway. Cambiarlos exige insertar
un manejador propio en el único punto de entrada de todo el tráfico, para atender peticiones que solo envía un script. Documentarlos
con una prueba por cada situación da la misma certeza con una fracción del costo y sin depender de nombres internos de la librería.

## Alternativas descartadas

| Alternativa | Por qué se descartó |
|---|---|
| Responder JSON con `code` desde un manejador de Netty propio, insertado antes de `reactor.left.httpTrafficHandler`, que al ver un resultado de decodificación fallido escriba `{"code","message"}` y cierre el canal. Códigos propuestos: `REQUEST_MALFORMED` (400), `REQUEST_URI_TOO_LONG` (414) y `REQUEST_HEADERS_TOO_LARGE` (431) | Cuesta de 4 a 5 horas más, depende de nombres internos de una librería que cambian al subir de versión y toca el único punto de entrada del tráfico. No cambia el estado HTTP, solo agrega el cuerpo; el beneficio para una persona usuaria es nulo porque su navegador no genera estas peticiones |
| Lo anterior más validar la dirección antes de pasarla al adaptador de WebFlux, para cubrir también `%zz` | Mismo costo y riesgo, con más superficie |
| Solo documentar, sin pruebas | La documentación quedaría desactualizada tras la siguiente actualización de la librería sin que nadie lo note |
| Poner un tope de tamaño al cuerpo en el Gateway dentro de esta tarea | Es una decisión de consumo de recursos (OWASP API4) que se toma junto con Cloud Run; es otra tarea |

## Consecuencias

- No hay contrato nuevo para las rutas de negocio y el único código nuevo es el de D-6; `docs/errores.md` lista cada rechazo sin código con su prueba.
- Una actualización de Reactor Netty que cambie un límite o un estado hace fallar una prueba, no la producción.
- Frontend recibe dos códigos nuevos del Gateway, `METHOD_NOT_ALLOWED` y `NOT_ACCEPTABLE`, que solo aparecen en Actuator. Un `multipart` grande que pasa por el Gateway responde 413 porque así lo responde el servicio destino.
- Queda abierto, con su destino en `docs/errores.md`: el tope de tamaño del cuerpo (otra tarea).
- Si Frontend necesita un cuerpo con `code` en los rechazos de protocolo, se reabre con la primera alternativa descartada.
