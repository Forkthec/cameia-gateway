# Tasks — CM-104-correcciones-OIDC: Firma OIDC de las llamadas salientes

- **Spec de referencia:** `specs/CM-104-correcciones-OIDC/spec.md`
- **Plan de referencia:** `specs/CM-104-correcciones-OIDC/plan.md`
- **Fecha:** 11/09/2026
- **Flujo SDD:** Fase 4 — Checkboxes de implementación
- **Rama:** `CM-104-correcciones-OIDC` → `develop`
- **Prerequisito:** `specs/CM-104-correcciones/` mergeado en `develop`

## Cómo usar esta lista

- Cada tarea está pensada para **30 minutos o menos**. Si una te toma más, para y pregunta:
  probablemente falta un dato, no te falta habilidad.
- Marca `[x]` **antes** de empezar la siguiente. Una tarea a la vez.
- Al final de cada bloque corre `docker compose run --rm verify`. **No pases al bloque siguiente
  con la suite en rojo.**
- Los números entre paréntesis son los requisitos del spec que la tarea satisface.

### El Bloque 0 no te bloquea

El Bloque 0 necesita un proyecto de GCP que **todavía no está configurado**. Bloquea el despliegue
y la prueba de extremo a extremo, **no** la implementación: los Bloques 1 a 7 se hacen hoy, con el
paso de firma apagado y una fuente de tokens simulada. Esa es justo la razón de que el diseño tenga
una interfaz `OidcTokenSource` en vez de una sola clase.

### El comando que vas a repetir

```bash
docker compose run --rm verify
```

---

## Bloque 0 — Infraestructura GCP · ⛔ BLOQUEADO

> No es trabajo de código. Lo ejecuta quien administre el proyecto de GCP. Cada tarea apunta al
> abierto del spec §6 que la desbloquea. **No escribas comandos `gcloud` en el repositorio como si
> se hubieran ejecutado** (`AGENTS.md` §0.3).

- [ ] T-INF-01 · Definir con arquitectura la identidad con la que corre el Gateway en Cloud Run: nombre de la service account y proyecto. Anotar la decisión en el spec, no en el código ⛔ `GW-TBD-10`
- [ ] T-INF-02 · Conceder `roles/run.invoker` de **cada** microservicio destino a esa identidad. Sin este paso el destino responde `403` aunque el token sea correcto ⛔ `GW-TBD-11`
- [ ] T-INF-03 · Registrar la URL exacta de Cloud Run de cada microservicio y usarla como valor de `CAMEIA_*_URL` en el despliegue. Es el audience, y debe coincidir carácter a carácter ⛔ `GW-TBD-12` (REQ-OIDC-02)
- [ ] T-INF-04 · Verificación de extremo a extremo, una vez desplegado: una llamada a través del Gateway llega y es aceptada; una llamada directa al microservicio, saltándose el Gateway, es rechazada. Sin las dos mitades la verificación no demuestra nada ⛔ `GW-TBD-11`

---

## Bloque 1 — El flag y el perfil de despliegue

> Primero esto: mientras el flag esté apagado, todo lo que venga después no altera el comportamiento
> actual y la suite existente sigue en verde sin tocarla.

- [ ] T-01 · En `application.yml`, declarar `gateway.oidc.signing-enabled: ${GATEWAY_OIDC_ENABLED:false}`. **La propiedad no se llama `gateway.oidc.enabled`**: con ese nombre la variable de entorno se enlazaría directamente a ella y podría apagar la firma en despliegue (REQ-OIDC-07, plan §3.7)
- [ ] T-02 · Crear `src/main/resources/application-prod.yml` con `gateway.oidc.signing-enabled: true` escrito **literal**, sin `${...}`. Solo eso y el nivel de log: las URLs y el CORS siguen llegando por variable de entorno (REQ-OIDC-07, plan §3.9)
- [ ] T-03 · Crear el guardia `OidcRequiredInProd`, anotado `@Profile("prod")`, que falle el arranque si la firma está apagada. Va aparte de `OidcConfig` porque debe correr **aunque** el flag esté en `false` (REQ-OIDC-07, plan §3.8)
- [ ] T-04 · Añadir `GATEWAY_OIDC_ENABLED: ${GATEWAY_OIDC_ENABLED:-false}` al servicio `app` de `docker-compose.yml` (REQ-OIDC-07)
- [ ] T-05 · Documentar la variable en `.env.example`, con la nota de que en el perfil de despliegue no tiene efecto (REQ-OIDC-07)

**Cierre de bloque:** `docker compose run --rm verify` en verde, sin cambios en las pruebas.

---

## Bloque 2 — La fuente de tokens

- [ ] T-06 · Crear la interfaz `OidcTokenSource` en el paquete `filter`, con una sola operación: `Mono<String> tokenFor(String audience)`. **Va en `filter`, no en `config`**: la regla ArchUnit `filterNoImportaConfig` prohíbe que el filtro mire al paquete `config` (plan §1)
- [ ] T-07 · Crear `GoogleIdTokenSource` con un `ConcurrentHashMap<String, IdTokenCredentials>` por audience. Se cachea **el objeto de credenciales, no la cadena del token**: la renovación la decide la librería (REQ-OIDC-05, REQ-OIDC-06, plan §3.4)
- [ ] T-08 · Envolver la llamada bloqueante en `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`, igual que hace `FirebaseAuthGlobalFilter` con `verifyIdToken` (REQ-NF-OIDC-02)
- [ ] T-09 · Crear `OidcConfig` en `config`, con `@ConditionalOnProperty` sobre `gateway.oidc.signing-enabled` y los beans de `OidcTokenSource` y del filtro. El bean de credenciales reales lleva su propio interruptor `gateway.oidc.google-credentials.enabled`, que es lo que permite apagarlo en pruebas igual que `FirebaseConfig` (REQ-OIDC-05, REQ-NF-OIDC-03, plan §3.8)

**Cierre de bloque:** `docker compose run --rm verify` en verde. Con el flag apagado no debe crearse ningún bean nuevo.

---

## Bloque 3 — El filtro de firma

- [ ] T-10 · Crear `OidcSigningGlobalFilter` con `getOrder()` devolviendo un valor posterior a la resolución de ruta y anterior al reenvío. **Verifica en el jar de Spring Cloud Gateway 5.0.3 el orden real del filtro de reenvío antes de escribir la constante**; no lo des por hecho (REQ-NF-OIDC-01, plan §3.1)
- [ ] T-11 · Leer el `Route` del atributo del `exchange` y construir el audience con `esquema://host[:puerto]`, sin path y sin barra final. **No uses la URL completa de la petición**: incluye el path y el destino rechaza el token (REQ-OIDC-02, plan §3.2)
- [ ] T-12 · Fijar `Authorization: Bearer <token>` con `headers(h -> h.set(...))`. **`set`, nunca `.header(...)`**: el segundo añade un valor más en vez de reemplazar, y deja indefinido cuál lee el destino (REQ-OIDC-01, REQ-OIDC-03, plan §3.5)
- [ ] T-13 · Si el `exchange` no trae ruta resuelta, seguir la cadena sin firmar: no hay destino que firmar y no debe fallar. Es el caso de una URL que no coincide con ninguna ruta (REQ-OIDC-01)

**Cierre de bloque:** `docker compose run --rm verify` en verde.

---

## Bloque 4 — Fail closed

- [ ] T-14 · Añadir `onErrorResume` que registre en el log la causa original con el `X-Request-Id` y el audience, y devuelva `Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))` **sin `reason`**: cualquier texto que se le pase puede acabar en la respuesta (REQ-OIDC-04, REQ-OIDC-09, plan §3.6)
- [ ] T-15 · Comprobar que el catálogo de `GlobalErrorHandler` ya traduce ese `503` a `{"code":"SERVICE_UNAVAILABLE",...}` sin tocar el mensaje de la excepción. Si el catálogo no lo cubre, la corrección va ahí y no en el filtro (REQ-OIDC-09)

**Cierre de bloque:** `docker compose run --rm verify` en verde. Revisa el log: la causa real tiene que aparecer ahí, y en ningún otro sitio.

---

## Bloque 5 — Pruebas

> Son la única forma de demostrar `REQ-NF-OIDC-03`: que todo esto se verifica sin credenciales de
> Google ni acceso a GCP.

- [ ] T-16 · Crear `TestOidcConfig` con un `OidcTokenSource` falso que registre los audiences pedidos y pueda fallar a demanda. Añadir `gateway.oidc.google-credentials.enabled: false` a `application-test.yml`, para que la implementación real nunca se construya en pruebas (REQ-NF-OIDC-03, plan §4)
- [ ] T-17 · Crear `OidcSigningFilterTest` con el flag encendido por propiedad y las pruebas 1, 2 y 5 del plan: el destino recibe el token OIDC en un solo valor; el audience coincide con la URI de la ruta sin path ni barra final; el ID Token de Firebase no aparece en el `Authorization` saliente (REQ-OIDC-01, REQ-OIDC-02, REQ-OIDC-03)
- [ ] T-18 · Añadir las pruebas 3 y 4: `POST /webhooks/wompi` también llega firmado, y un `Authorization: Basic xyz` del cliente se reemplaza en vez de sobrevivir (REQ-OIDC-01, REQ-OIDC-03)
- [ ] T-19 · Añadir la prueba 6: la fuente falla → `503` con `code: SERVICE_UNAVAILABLE`, el cuerpo no contiene `Exception`, `java.`, el audience ni el host, y `getRequestCount()` demuestra que **no se reenvió nada** (REQ-OIDC-04, REQ-OIDC-09)
- [ ] T-20 · Añadir la prueba 7 en una clase sin la propiedad del flag: el destino no recibe `Authorization`, y el `401` sin token de Firebase sigue funcionando. Demuestra que el flag apaga solo el paso saliente (REQ-OIDC-07, REQ-OIDC-08)
- [ ] T-21 · Añadir la prueba 8: leer `application-prod.yml` del classpath y afirmar que declara el flag literal, sin `${`. No arranca contexto; es la forma barata de fijar una regla que si no solo se descubre rota en despliegue (REQ-OIDC-07)

**Cierre de bloque:** `docker compose run --rm verify` en verde, con las pruebas nuevas contadas en la salida.

---

## Bloque 6 — Documentación

- [ ] T-22 · Añadir a la tabla de componentes de `AGENTS.md` §4 las cuatro clases nuevas, y quitar la nota de que el componente de firma de §6.3 todavía no existe (plan §5)
- [ ] T-23 · Vaciar la tabla de brechas de `AGENTS.md` §6.5 y anotar la fecha y el PR que la cerró. Es la última fila pendiente del modelo de seguridad (plan §5)
- [ ] T-24 · Actualizar la tabla de variables de `AGENTS.md` §9 y la de `README.md`: `GATEWAY_OIDC_ENABLED` deja de ser una promesa, con la aclaración de que en el perfil de despliegue no tiene efecto (plan §5)
- [ ] T-25 · Actualizar `docs/COMO-FUNCIONA.md` §3 y §4: la sección 3 afirma que no existe una sola línea de firma OIDC, y la 4 que el perfil de despliegue no existe. Las dos cosas dejan de ser ciertas con este PR (plan §5)
- [ ] T-26 · Rellenar la bitácora de IA del mismo día, según `AGENTS.md` §10

---

## Bloque 7 — Cierre

- [ ] T-27 · Revisar el diff completo con `git diff develop...HEAD` y confirmar: ningún secreto real, ninguna URL de microservicio escrita en el código, ningún comando de GCP presentado como ejecutado
- [ ] T-28 · Recorrer el DoD del spec §7 y marcar cada casilla con la evidencia real: salida de comando o número de prueba. Las casillas de despliegue quedan sin marcar mientras el Bloque 0 siga bloqueado, y eso se dice en el PR
- [ ] T-29 · Abrir el PR hacia `develop` con el título `CM-104 | feat(gateway): firmar las llamadas salientes con token OIDC [IA-ASISTIDO]`, y avisar en la descripción a infraestructura de las tareas del Bloque 0 y a Cuentas de `GW-TBD-14`

---

## Trazabilidad requisito → tarea

| Requisito | Tareas |
|---|---|
| `REQ-OIDC-01` | T-12, T-13, T-17, T-18 |
| `REQ-OIDC-02` | T-INF-03, T-11, T-17 |
| `REQ-OIDC-03` | T-12, T-17, T-18 |
| `REQ-OIDC-04` | T-14, T-19 |
| `REQ-OIDC-05` | T-07, T-09 |
| `REQ-OIDC-06` | T-07 |
| `REQ-OIDC-07` | T-01, T-02, T-03, T-04, T-05, T-20, T-21 |
| `REQ-OIDC-08` | T-20 |
| `REQ-OIDC-09` | T-14, T-15, T-19 |
| `REQ-NF-OIDC-01` | T-10 |
| `REQ-NF-OIDC-02` | T-08 |
| `REQ-NF-OIDC-03` | T-06, T-09, T-16 |

---

## Lo que esta lista deja fuera a propósito

| Tema | Dónde va |
|---|---|
| Desplegar el Gateway a Cloud Run y el pipeline de CI/CD | Ticket propio. Este spec deja el servicio listo, no desplegado |
| Rate limit y circuit breaker | Fuera de alcance por decisión escrita en CM-113 |
| mTLS entre Gateway y microservicios | Fuera de alcance por decisión escrita en CM-113 |
| Autorización por rol dentro del Gateway | `AGENTS.md` §6.1 paso 3. Entra con la HU que la exija |
| Cambios en el contrato de cabeceras `X-User-*` | Lo fija `specs/CM-104-correcciones/` |
