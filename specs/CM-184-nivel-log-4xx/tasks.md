# Tasks — CM-184-nivel-log-4xx

- **Spec de referencia:** `specs/CM-184-nivel-log-4xx/spec.md`
- **Plan de referencia:** `specs/CM-184-nivel-log-4xx/plan.md`
- **Fecha:** 19/09/2026
- **Flujo SDD:** Fase 4 — Checkboxes de implementación
- **Rama:** `CM-184-nivel-log-4xx` → `develop`

## Cómo usar esta lista

- Una tarea a la vez; marcar `[x]` antes de la siguiente.
- Al cerrar el bloque: `docker compose run --rm verify` en verde.

---

## Bloque 1 — El nivel depende del estado

- [x] T-01 · En `handle`, mover `resolveStatus(ex)` antes del log y sustituir el `log.error` por la llamada a `logFailure` (REQ-LOG-01, plan §2) — 19/09/2026: `handle` resuelve el estado antes del log y delega en `logFailure`
- [x] T-02 · Añadir `logFailure` y `logClientError` con su Javadoc en español, después de `resolveRequestId` (REQ-LOG-01 a REQ-LOG-03, REQ-LOG-05, plan §3) — 19/09/2026: los dos métodos quedan en 6 líneas cada uno

## Bloque 2 — Pruebas

- [x] T-03 · Crear `GlobalErrorHandlerLoggingTest` con `OutputCaptureExtension` y las pruebas 1 y 2 del plan: `404` en `INFO` y `400` en `WARN`, las dos sin traza (REQ-LOG-02, REQ-LOG-03) — 19/09/2026: `notFound_isLoggedAtInfoWithoutStackTrace`, `clientError_isLoggedAtWarnWithoutStackTrace`
- [x] T-04 · Prueba 3 del plan: un `5xx` conserva nivel `ERROR` y traza (REQ-LOG-01) — 19/09/2026: `serverError_isLoggedAtErrorWithStackTrace`
- [x] T-05 · Pruebas 4 y 5 del plan: la respuesta del `404` no cambia y el mensaje de la excepción no llega al log (REQ-LOG-04, REQ-LOG-05) — 19/09/2026: `errorResponse_isUnchanged`, `clientErrorLog_doesNotLeakExceptionMessage`
- [x] T-06 · Añadir a `GatewayErrorMappingTest` el caso de extremo a extremo de una ruta inexistente, sin tocar las pruebas existentes (REQ-LOG-02, REQ-NF-LOG-02, plan §4.2) — 19/09/2026: `unknownRoute_isLoggedAtInfoWithoutStackTrace`, añadida sin tocar las cuatro existentes
- [x] T-07 · `docker compose run --rm verify` en verde; anotar el número de pruebas antes y después — 19/09/2026: 73 pruebas antes, 79 después; `BUILD SUCCESS`

## Bloque 3 — Documentación y cierre

- [x] T-08 · `AGENTS.md` §4 (fila de `GlobalErrorHandler`) y §8 (número de pruebas) (plan §5) — 19/09/2026: fila del componente y «Hoy son 79 pruebas» (el 70 que decía venía de antes de CM-14)
- [x] T-09 · `docs/COMO-FUNCIONA.md` §5.4: la regla de niveles sustituye al aviso de que todo error sale en `ERROR` con traza (plan §5) — 19/09/2026: fila nueva en la tabla de §5.4, párrafo de niveles y el aviso antiguo tachado
- [x] T-10 · Nota de enmienda en el segundo bloque de `REQ-12` de `specs/CM-104-correcciones/spec.md` (plan §5) — 19/09/2026
- [x] T-11 · `specs/NUMERACIONES.md`: `GW-TBD-27`, la carpeta del spec y sus rangos (plan §5) — 19/09/2026: `GW-TBD-27` cerrado, siguiente libre `GW-TBD-28`; se añadieron las filas que faltaban de `CM-14-verificacion-correo`
- [x] T-12 · Recorrer el DoD del spec §6 con evidencia real — 19/09/2026: DoD del spec §6 recorrido con evidencia
- [x] T-13 · Bitácora de IA del mismo día (`AGENTS.md` §10) — 19/09/2026: `../../Entregables/19092026_BitacoraIA_Codigo_E2.md`
- [x] T-14 · Redactar la respuesta a DevOps con el formato de la §6 de la solicitud — 19/09/2026: al final de este archivo
- [ ] T-15 · Abrir el PR **solo con autorización expresa** (`AGENTS.md` §0, punto 1)

---

## Trazabilidad requisito → tarea

| Requisito | Tareas |
|---|---|
| `REQ-LOG-01` | T-01, T-02, T-04 |
| `REQ-LOG-02` | T-02, T-03, T-06 |
| `REQ-LOG-03` | T-02, T-03 |
| `REQ-LOG-04` | T-01, T-05 |
| `REQ-LOG-05` | T-02, T-05 |
| `REQ-NF-LOG-01` | T-01, T-02 |
| `REQ-NF-LOG-02` | T-06, T-07 |

---

## Respuesta a DevOps (T-14) — formato de la §6 de la solicitud

```text
Opción de nivel elegida: 1

PR: pendiente de autorización para publicar la rama (AGENTS.md §0). Título previsto:
    CM-184 | fix(gateway): bajar el nivel de log de los errores 4xx esperados [IA-ASISTIDO]
    Se abrirá en Draft, hacia develop, con revisor distinto del autor.

Spec: specs/CM-184-nivel-log-4xx/ (spec.md, plan.md, tasks.md)

Desviaciones respecto a este documento: dos, ninguna de fondo.
  1. Se añadió REQ-LOG-05 ("el log de un 4xx no repite texto del cliente"). El documento lo pide
     en la §3 y lo prueba en la §2.4, pero no lo tenía como requisito numerado; sin él, la prueba
     clientErrorLog_doesNotLeakExceptionMessage no trazaba a ningún REQ-N (AGENTS.md §0.1 fase 6).
  2. GlobalErrorHandlerLoggingTest se escribió como prueba unitaria con MockServerWebExchange, no
     con @SpringBootTest. La §2.4 lo permite si se comprueba que la línea INFO se imprime: se
     comprobó, las cinco pruebas pasan en la suite completa. El caso de extremo a extremo sí usa
     el contexto real, en GatewayErrorMappingTest.

Verificación local: docker compose run --rm verify → Tests run: 79, Failures: 0, Errors: 0,
    Skipped: 0. BUILD SUCCESS. En develop sin estos cambios son 73, así que las 6 nuevas son las
    cinco de GlobalErrorHandlerLoggingTest más unknownRoute_isLoggedAtInfoWithoutStackTrace.
    Ninguna prueba existente se modificó.

Fecha estimada de merge: por confirmar con Juan Vela; el código está listo y el PR depende solo de
    la autorización para publicar la rama.
```

> El criterio 4.2 (staging) y el 4.3 (Error Reporting) los ejecuta DevOps tras el despliegue: aquí
> no hay forma de comprobarlos.
