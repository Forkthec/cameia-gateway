# Tasks — CM-104-correcciones: Endurecimiento de identidad, errores y empaquetado

- **Spec de referencia:** `specs/CM-104-correcciones/spec.md`
- **Plan de referencia:** `specs/CM-104-correcciones/plan.md`
- **Fecha:** 11/09/2026
- **Flujo SDD:** Fase 4 — Checkboxes de implementación
- **Rama:** `CM-104-correcciones` → `develop`

## Cómo usar esta lista

- Cada tarea está pensada para **30 minutos o menos**. Si una te toma más, para y pregunta:
  probablemente falta un dato, no te falta habilidad.
- Marca `[x]` **antes** de empezar la siguiente. Una tarea a la vez.
- Al final de cada bloque corre `docker compose run --rm verify`. **No pases al bloque siguiente
  con la suite en rojo.**
- Las tareas marcadas `[x]` en el Bloque 0 ya están hechas en esta rama. Algunas están
  implementadas pero **todavía sin commitear**: eso se indica en cada una.
- Los números entre paréntesis son los requisitos del spec que la tarea satisface.

### El comando que vas a repetir

```bash
docker compose run --rm verify
```

---

## Bloque 0 — Ya completado en esta rama

> No hay nada que hacer aquí. Está para que el historial SDD no dé por pendiente algo ya hecho.

### Coherencia de documentación (commit `cd5b4c0`)

- [x] T-00 · Consolidar `AGENTS.md` como único documento de reglas y eliminar `CLAUDE.md`
- [x] T-01 · Corregir la URL de la ruta de Cuentas en `AGENTS.md` §5: decía `/api/v1/accounts/**`, el `application.yml` declara `/api/v1/users/**` (REQ-04)
- [x] T-02 · Completar la tabla de rutas de `AGENTS.md` §5: faltaban `/api/v1/voice-service/**` y `/api/v1/audit/**`, y `/webhooks/wompi` aparecía sin destino (REQ-04)
- [x] T-03 · Corregir el diagrama de dependencias de `AGENTS.md` §3: presentaba `filter → config` como permitido, que es justo lo que prohíbe la prueba ArchUnit `filterNoImportaConfig`
- [x] T-04 · Corregir la descripción de `GatewayProperties` en `AGENTS.md` §4: el campo es `timeout` (`Duration`), no `timeoutMs`
- [x] T-05 · Corregir la referencia cruzada de la bitácora de IA en `AGENTS.md` §0: apuntaba a §7 en vez de §10

### Coherencia de código (implementado, pendiente de commit)

- [x] T-06 · Renombrar el dominio de paquetes de `co.edu.unicauca.cameia` a `tech.cameia`, igual que el resto de microservicios (REQ-NF-07) — 9 archivos Java movidos y 23 referencias actualizadas
- [x] T-07 · Subir el parent de `pom.xml` de Spring Boot `4.0.8` a `4.1.1`, la versión que ya afirmaban README y specs (REQ-NF-07) — verificado: `spring-cloud-dependencies:2025.1.3` es compatible, suite 10/10 en verde

### Diagnóstico (base de este spec)

- [x] T-08 · Escanear el código y documentar el estado real en `docs/COMO-FUNCIONA.md` (pendiente de commit)
- [x] T-09 · Verificar que `docker compose up` también arranca el servicio `verify`, con `docker compose config --services` y `--profiles` (REQ-NF-03)
- [x] T-10 · Verificar que las entradas de Actuator en `PUBLIC_PATHS` son inertes: quitarlas, correr la suite y comprobar que la prueba de `/actuator/health` sin token sigue en verde (REQ-13)

---

## Bloque 1 — Trazabilidad: `X-Request-Id`

> Primero esto, porque los bloques 2 y 4 lo usan para registrar en el log qué solicitud fallaba.

- [ ] T-11 · En `FirebaseAuthGlobalFilter`, añadir la constante `X_REQUEST_ID = "X-Request-Id"` y un método privado `resolveRequestId(ServerWebExchange)` que devuelva el valor de la cabecera entrante si existe y no está en blanco, o `UUID.randomUUID().toString()` si no (REQ-09)
- [ ] T-12 · Llamar a `resolveRequestId` al principio de `filter(...)`, **antes** de decidir si la ruta es pública, y fijar la cabecera en la solicitud reenviada en los dos caminos (REQ-09)
- [ ] T-13 · Añadir dos pruebas en `FirebaseAuthGlobalFilterTest`: con `X-Request-Id: abc` el destino recibe `abc`; sin la cabecera, el destino recibe un valor generado y no vacío (pruebas 9 y 10 del plan)

**Cierre de bloque:** `docker compose run --rm verify` en verde.

---

## Bloque 2 — Identidad: reemplazar en vez de añadir

> El bloque más importante del spec. Aquí se cierra la posibilidad de suplantar un usuario.

- [ ] T-14 · En `FirebaseAuthGlobalFilter`, añadir las constantes `X_USER_EMAIL`, `X_USER_ROLES` y `ROLES_CLAIM`, y el conjunto `IDENTITY_HEADERS` con las cuatro cabeceras `X-User-*`. **`X-Request-Id` no va en ese conjunto** (plan §3.1)
- [ ] T-15 · Añadir el método privado `readRoles(FirebaseToken)` que acepta el claim `roles` tanto como lista (une con comas) como cadena única (la propaga tal cual). Sin cambiar mayúsculas, sin ordenar, sin deducirlo de `plan` (REQ-08, plan §3.2)
- [ ] T-16 · Añadir un método privado `setIfPresent(HttpHeaders, String, String)` que fije la cabecera solo si el valor no es `null` ni está en blanco. Es lo que evita propagar cabeceras vacías (REQ-01)
- [ ] T-17 · Reescribir `propagateClaims` como `withIdentity`: primero borra todas las de `IDENTITY_HEADERS` y `Authorization`, después fija las cinco cabeceras con `headers(h -> h.set(...))`. **Cambiar `.header(...)` por `h.set(...)` es el corazón de la tarea**: `.header(...)` añade un segundo valor en vez de reemplazar (REQ-07, plan §3.3)
- [ ] T-18 · Añadir el método `withoutIdentity` para rutas públicas: borra todas las de `IDENTITY_HEADERS` y fija `X-Request-Id`. Aplicarlo en la rama de ruta pública de `filter(...)`, que hoy hace `return chain.filter(exchange)` sin tocar nada (REQ-03, REQ-10)
- [ ] T-19 · Ampliar la prueba `tokenValidoConPlan_propagaHeadersAlDownstream` para afirmar las cinco cabeceras, y añadir la prueba de suplantación: el cliente envía `X-User-Id: atacante` con token válido y el destino recibe **un solo** valor, el del token. Usa `RecordedRequest.getHeaders().values("X-User-Id")` y afirma que tiene tamaño 1 (pruebas 1 y 2 del plan)
- [ ] T-20 · Ampliar la prueba de `/webhooks/wompi`: el cliente envía `X-User-Id` propio y el destino no recibe la cabecera (prueba 3 del plan)
- [ ] T-21 · Añadir tres pruebas de claims: `roles` como lista → `free,premium`; `roles` como cadena → tal cual y sin cambiar mayúsculas; token sin `email` → la cabecera se omite en vez de llegar vacía (pruebas 4, 5 y 6 del plan)

**Cierre de bloque:** `docker compose run --rm verify` en verde.

---

## Bloque 3 — Rechazo: Bearer vacío y errores de Firebase

- [ ] T-22 · En `FirebaseAuthGlobalFilter`, extraer el token con `trim()` y responder `401` si queda vacío, **antes** de llamar a Firebase. Hoy `Authorization: Bearer ` (con el valor vacío) llega a `verifyIdToken("")`, que lanza `IllegalArgumentException` y termina en `500` (REQ-02, plan §3.4)
- [ ] T-23 · Añadir el método privado `isTokenRejection(Throwable)` que devuelva `true` para `FirebaseAuthException` e `IllegalArgumentException`, y usarlo en el `onErrorResume`. **No capturar `Throwable` en bruto**: un fallo de red al hablar con Firebase es problema del Gateway y debe seguir siendo `500` (REQ-02, plan §3.4)
- [ ] T-24 · Añadir dos pruebas: `Authorization: Bearer ` (vacío) → `401` con `code: AUTH_REQUIRED`; `Authorization: Basic xyz` → `401` (pruebas 7 y 8 del plan)

**Cierre de bloque:** `docker compose run --rm verify` en verde.

---

## Bloque 4 — Errores: catálogo cerrado y traducción de fallos

- [ ] T-25 · En `GlobalErrorHandler`, crear el catálogo fijo de códigos y mensajes del spec §2.4 (`AUTH_REQUIRED`, `NOT_FOUND`, `BAD_GATEWAY`, `SERVICE_UNAVAILABLE`, `GATEWAY_TIMEOUT`, `INTERNAL_ERROR`) como constante (REQ-11)
- [ ] T-26 · Reescribir `resolveStatus` con el mapeo del plan §3.5: `TimeoutException` → `504`; `ConnectException` y `UnknownHostException` → `503`; excepciones de `reactor.netty` → `502`; `ResponseStatusException` conserva su estado; el resto → `500` (REQ-11)
- [ ] T-27 · Construir el cuerpo de la respuesta desde el catálogo y **eliminar el uso de `ex.getMessage()`**. Con el mensaje ya fijo, el método `sanitize` sobra: bórralo (REQ-12)
- [ ] T-28 · Registrar la excepción original en el log con el `X-Request-Id` de la solicitud, en nivel `ERROR`. El detalle va al log, nunca a la respuesta (REQ-12)
- [ ] T-29 · Crear `GatewayErrorMappingTest` con `@DynamicPropertySource` que baje el timeout a `300ms`, y dos pruebas: destino que no responde → `504`; destino en un puerto cerrado → `503`. Para el puerto cerrado, abre un `ServerSocket` en el puerto 0, lee el puerto asignado y ciérralo antes de arrancar el contexto (pruebas 11 y 12 del plan)
- [ ] T-30 · Añadir una prueba que afirme que ningún cuerpo de error contiene `Exception`, `java.` ni el host del destino (prueba 13 del plan)

**Cierre de bloque:** `docker compose run --rm verify` en verde.

---

## Bloque 5 — Limpieza: configuración inerte y artefactos que faltan

- [ ] T-31 · Eliminar `/actuator/health` y `/actuator/info` de `PUBLIC_PATHS`, y dejar un comentario en el código explicando por qué: Actuator lo atiende su propio handler y el filtro nunca lo ve. La prueba `actuatorHealth_sinToken_retorna200` **no se toca**: es la red de seguridad que demuestra que el cambio es inocuo (REQ-13)
- [ ] T-32 · Eliminar `GatewayProperties.java` y la anotación `@EnableConfigurationProperties(GatewayProperties.class)` de `GatewayApplication`. Eliminar también las claves `gateway.cors-allowed-origin` y `gateway.timeout` de `application-test.yml`. **`gateway.firebase.enabled` se queda**: la usa el `@ConditionalOnProperty` de `FirebaseConfig` (REQ-14, plan §3.6)
- [ ] T-33 · Retirar `X-User-Id` y `X-User-Plan` de `allowedHeaders` en el CORS de `application.yml`, y añadir `X-Request-Id` (REQ-NF-01, plan §3.7)
- [ ] T-34 · Crear `src/main/resources/application-local.yml` con el override de log del plan §3.8. El perfil `local` es el activo por defecto y hoy no existe el archivo, aunque `tasks.md` de CM-104 lo marque como creado (REQ-15)

**Cierre de bloque:** `docker compose run --rm verify` en verde. Además, arranca el gateway y
confirma que `GET /actuator/health` sigue respondiendo `200` sin token.

---

## Bloque 6 — Empaquetado: Docker

- [ ] T-35 · En el `Dockerfile`, crear un usuario `cameia` sin privilegios, darle la propiedad de `/app` y añadir `USER cameia` antes del `ENTRYPOINT` (REQ-NF-06, plan §3.9)
- [ ] T-36 · Añadir la instrucción `HEALTHCHECK` al `Dockerfile` con los mismos intervalos que ya usa Compose, para que un contenedor arrancado sin Compose también la tenga (REQ-NF-05, plan §3.9)
- [ ] T-37 · Añadir `profiles: ["tools"]` al servicio `verify` de `docker-compose.yml`. Después comprueba las dos cosas: `docker compose config --services` sigue listando ambos, pero `docker compose up -d` arranca solo `app`, y `docker compose run --rm verify` sigue funcionando igual (REQ-NF-03, plan §3.10)

**Cierre de bloque:** construye la imagen con `docker build -t cameia-gateway .`, arráncala con
`docker run` y confirma dos cosas: `docker ps` muestra el estado `healthy`, y
`docker exec <id> whoami` responde `cameia`, no `root`.

---

## Bloque 7 — Documentación

- [ ] T-38 · Actualizar la tabla de brechas de `AGENTS.md` §6.5: salen las cerradas por este spec y queda solo la del token OIDC. Quitar `GatewayProperties` de la tabla de componentes de §4 (plan §5)
- [ ] T-39 · Actualizar `docs/COMO-FUNCIONA.md`: las secciones 3, 5.3 y 7 describen defectos que este spec corrige. Deja el documento contando el estado real, no el histórico (plan §5)
- [ ] T-40 · Añadir una nota en `specs/CM-104-base-tecnica/tasks.md` junto a las dos casillas incorrectas (`application-local.yml` y el campo `timeoutMs`), apuntando a este spec. **No desmarcar las casillas**: la nota conserva el historial y dice la verdad (REQ-14, REQ-15)
- [ ] T-41 · Rellenar la bitácora de IA del mismo día, según `AGENTS.md` §10

---

## Bloque 8 — Cierre

- [ ] T-42 · Revisar el diff completo con `git diff develop...HEAD` y confirmar: ningún secreto real, ninguna URL de downstream escrita en el código, y el total por debajo de las 1000 líneas que fija `AGENTS.md`
- [ ] T-43 · Recorrer el DoD del spec §6 y marcar cada casilla con la evidencia real (salida de comando o número de prueba). Si algo no pasa, no se marca
- [ ] T-44 · Abrir el PR hacia `develop` con el título `CM-104 | fix(gateway): endurecer identidad, errores y empaquetado [IA-ASISTIDO]`, y avisar en la descripción a los equipos de Cuentas y Entrevista de que ya reciben `X-User-Email`, `X-User-Roles` y `X-Request-Id` (`GW-TBD-07`)

---

## Lo que esta lista deja fuera a propósito

| Tema | Dónde va |
|---|---|
| Token OIDC hacia los microservicios | Spec propio. Es el bloqueante de despliegue |
| Rate limit y circuit breaker | Fuera de alcance por decisión escrita en CM-113 |
| Archivo de perfil de despliegue | Lo necesita el flag de OIDC; se crea en ese spec |
| Definir qué microservicio aplica los límites por plan | `GW-TBD-08`. No es trabajo del Gateway |
