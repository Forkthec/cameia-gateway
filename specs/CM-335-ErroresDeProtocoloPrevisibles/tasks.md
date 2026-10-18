# Tarjetas · CM-335 Rechazos de protocolo del Gateway (opción A)

**Reglas para todas las tarjetas:** identificadores y nombres de prueba en inglés con la forma del repo `escenario_condicion_resultado`; Javadoc en todo lo nuevo; sin `CM-335` en comentarios ni rutas a otros archivos; sin dependencias nuevas; sin tocar código de producción, `application.yml` ni `.github/`. Si algo no coincide, detenerse y reportar a Paula con la salida real. Estado de la spec: **BORRADOR; D-1 decidida (opción A). No se implementa nada hasta que Paula apruebe la spec.**

---

## T0 · Preparación

1. `git fetch`; rama `CM-335-errores-de-protocolo-previsibles` desde `origin/develop`; anotar el SHA.
2. Línea base: `./mvnw.cmd -B -ntp clean verify` (o `docker compose run --rm verify`) → `BUILD SUCCESS`. Si falla, detenerse.
3. Confirmar que Paula eligió la opción A. Si eligió B o C, detenerse: hacen falta tarjetas nuevas.

---

## T1 · Pruebas con sockets (REQ-01, REQ-02)

Crear `src/test/java/tech/cameia/gateway/server/RawHttpClient.java`:

```java
/**
 * Cliente HTTP de pruebas que escribe la petición byte a byte en un socket. Existe porque
 * {@code WebTestClient} y {@code java.net.URI} rechazan o normalizan una petición mala antes de enviarla.
 */
final class RawHttpClient {
    /** Respuesta reducida a lo que las pruebas comprueban. */
    record Reply(int status, Map<String, String> headers, String body) { }

    private final int port;
    RawHttpClient(int port) { this.port = port; }

    /**
     * @param method  método HTTP
     * @param target  ruta y consulta tal como se escriben en la línea de petición, sin validar
     * @param headers encabezados completos {@code Nombre: valor}; se escriben en ISO-8859-1
     * @return la respuesta; si el servidor cierra sin responder, lanza {@code AssertionError} con ese motivo
     */
    Reply send(String method, String target, List<String> headers) throws IOException { /* ... */ }
}
```

Esqueleto de `send`: `new Socket("localhost", port)`; escribir `METHOD target HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n` + encabezados + `\r\n`; leer la línea de estado, los encabezados y el cuerpo hasta el cierre (decodificar `Transfer-Encoding: chunked` si aparece); tiempo de espera de lectura de 5 s.

Crear `src/test/java/tech/cameia/gateway/server/NettyDecodingRejectionsTest.java` con la misma configuración de `GlobalCorsConfigTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT, classes = TestFirebaseConfig.class)`, `@ActiveProfiles("test")`, `@DynamicPropertySource` con los tres `CAMEIA_*_URL` apuntando a `http://localhost:1`, `@LocalServerPort`). Pruebas (todas comprueban estado, cuerpo vacío y que el cuerpo no contiene `code`):

| Prueba | Petición | Esperado |
|---|---|---|
| `controlCharacterInHeader_returns400WithEmptyBody` | `GET /api/v1/users/health`, encabezado `X-Foo: a\u0007` | 400 |
| `oversizedHeader_returns431WithEmptyBody` | encabezado `X-Foo: ` + `"a"` × 9000 | 431 |
| `headerBelowLimit_isNotRejected` | encabezado `X-Foo: ` + `"a"` × 7000 | estado distinto de 431 y de 400 (el que responda la ruta, p. ej. 401) |
| `oversizedUrl_returns414WithEmptyBody` | `GET /` + `"a"` × 9000 | 414 |
| `malformedQuery_returns400WithEmptyBody` | `GET /api/v1/profiles/professional-roles?lang=%zz` | 400 |
| `malformedPath_returns400WithEmptyBody` | `GET /api/v1/profiles/%zz` | 400 |
| `gatewayKeepsServing_afterRejections` | tras las anteriores, `GET /actuator/health` | 200 |

**Trampas:** el escape `\u0007` en Java se convierte en el carácter real al compilar, no al escribir el archivo; verificar con `git diff` que el archivo conserva el escape y no el carácter. Si algún estado difiere del esperado, **no ajustar la prueba**: anotar la salida real y reportar a Paula (cambia la documentación). Si el perfil `test` no apaga alguna propiedad que cambie los límites, detenerse.

**Comando:** `./mvnw.cmd -B -ntp test -Dtest=NettyDecodingRejectionsTest,GatewayArchTest`.

**Commit:** `CM-335 | test(gateway): pruebas con sockets de los rechazos de protocolo`.

---

## T2 · Documentos (REQ-03 a REQ-05)

1. `docs/errores.md`: reemplazar la sección «Respuestas sin código» («Ninguna.») por una tabla con columnas Petición, Estado, Quién la produce, Por qué no lleva `code`, Prueba, y las filas E-1 a E-5 de la spec §2 (la E-5 aclara que el Gateway reenvía tal cual la página del servicio destino). Agregar una frase: «El Gateway no cambia estos cuerpos: los escribe Reactor Netty o WebFlux antes de que corra cualquier filtro del Gateway; el cliente decide por el estado HTTP».
2. `docs/adr/0003-rechazos-de-protocolo-del-gateway.md` (formato del ADR 0002): Estado, Contexto, Decisión (opción elegida por Paula), Consecuencias, Alternativas descartadas (B y C con su costo, de la spec §3 y §15).
3. `docs/bitacora-ia/`: entrada `NN_rechazos-de-protocolo-del-gateway_prompt.md` con el siguiente número del `README.md` (solo el prompt armado) y su fila en la tabla, con salida `../../specs/CM-335-ErroresDeProtocoloPrevisibles/spec.md`. La decisión humana (HITL) queda `PENDIENTE (Paula)`.
4. Aviso a Frontend (REQ-05): se entrega a Paula en el chat al cerrar, no en el repositorio.

**Commit:** `CM-335 | docs(gateway): rechazos sin código documentados, ADR 0003 y bitácora`.

---

## T3 · Cierre (una vez, sobre la punta)

1. `./mvnw.cmd -B -ntp clean verify` completo; `jacoco.csv` sin caída frente a la línea base.
2. Las peticiones de la spec §2 con `curl` contra el jar empaquetado (como en la evidencia `65`); guardar la salida en `ejecucion/evidencia/CM-335/`.
3. Lista de verificación de la spec (V-01 a V-05).
4. `ejecucion/evidencia/CM-335/resumen.md` con lo hecho, cobertura, hallazgos y commits.
5. Sin push ni PR: se pregunta a Paula.
