# Spec — CM-104-correcciones: Endurecimiento de identidad, errores y empaquetado del Gateway

- **HU asociada:** CM-104 (revisión) — correcciones detectadas al escanear la base técnica
- **Sprint:** 1
- **Fecha:** 11/09/2026
- **Estado:** propuesta pendiente de aprobación
- **Flujo SDD:** Fase 1 — Requisitos EARS
- **Rama:** `CM-104-correcciones` → `develop`
- **Fuentes:**
  `docs/COMO-FUNCIONA.md` (escaneo del código, 11/09/2026),
  `specs/CM-104-base-tecnica/spec.md` (requisitos originales),
  `AGENTS.md` §6 (modelo de seguridad aprobado),
  contratos de entrada declarados por `cameia-cuentas` y `cameia-entrevista`.

---

## 1. Contexto

CM-104 dejó el Gateway operativo: enruta, valida tokens de Firebase y responde `401`. El escaneo
documentado en `docs/COMO-FUNCIONA.md` encontró que **varios requisitos de CM-104 se cumplen solo
en apariencia**, y que faltan controles que el contrato con los microservicios da por hechos.

Este spec **no introduce funcionalidad nueva de negocio**. Corrige requisitos de CM-104 y agrega
los que faltaban. Conserva la numeración de CM-104: un `REQ-0X` con la etiqueta **(corregido)**
reemplaza al de CM-104 con el mismo número; de `REQ-09` en adelante son nuevos.

**Tres problemas de fondo que justifican el spec:**

1. **La identidad es falsificable.** El filtro *añade* los headers `X-User-*` en vez de
   reemplazarlos, y en rutas públicas no los toca. Un cliente puede mandar su propio `X-User-Id`.
2. **Los fallos del downstream mienten.** Todo lo que no sea `ResponseStatusException` se traduce
   a `500`, con el mensaje de la excepción copiado al cuerpo de la respuesta.
3. **El contrato está incompleto.** `cameia-cuentas` y `cameia-entrevista` declaran obligatorios
   `X-User-Email`, `X-User-Roles` y `X-Request-Id`. El Gateway no envía ninguno.

### Fuera de alcance

| Tema | Por qué queda fuera |
|---|---|
| Token OIDC hacia los microservicios | Es el bloqueante de despliegue y lleva spec propio: requiere decisiones de GCP (service account, bindings de IAM, URLs de Cloud Run) y su diff no cabe junto al de este spec |
| Rate limit y circuit breaker | Fuera de alcance por decisión escrita en CM-113. Además requieren contador compartido (Redis) o control previo al Gateway (Cloud Armor) |
| Límites por plan | Los aplica **otro microservicio**, todavía sin definir: puede ser Cuentas u otro aparte (ver `GW-TBD-08`) |
| Archivo de perfil de despliegue | Lo necesita el flag de OIDC, así que se crea en el spec de OIDC y no aquí |

---

## 2. Requisitos funcionales — notación EARS

### 2.0 Mapa de requisitos frente a CM-104

| ID | Estado respecto a CM-104 | Qué cambia |
|---|---|---|
| `REQ-01` | **corregido** | El contrato de headers de salida pasa de 2 a 5 headers |
| `REQ-02` | **corregido** | El `401` cubre también el Bearer vacío o malformado |
| `REQ-03` | **corregido** | Las rutas públicas ahora **limpian** los headers de identidad entrantes |
| `REQ-04` | **corregido** | La ruta de Cuentas es `/api/v1/users/**`; la tabla omitía voz y auditoría |
| `REQ-05` | aclarado | El filtro nunca ve Actuator: lo atiende su propio handler |
| `REQ-06` | aclarado | El arranque fail-fast lo produce el placeholder sin resolver, no la clase de propiedades |
| `REQ-07` | **corregido** | Los headers de identidad se reemplazan, no se añaden |
| `REQ-08` | sin cambio | Se refuerza: el Gateway no normaliza ni interpreta los claims |
| `REQ-09` … `REQ-15` | **nuevos** | Trazabilidad, procedencia de headers, errores y coherencia interna |
| `REQ-NF-01` | **corregido** | CORS deja de admitir headers de identidad desde el navegador |
| `REQ-NF-02` | confirmado | Se mantiene el timeout, pero ahora con prueba que lo demuestre |
| `REQ-NF-03` | **corregido** | El servicio `verify` deja de arrancar con `docker compose up` |
| `REQ-NF-04` | sin cambio | — |
| `REQ-NF-05` … `REQ-NF-07` | **nuevos** | Healthcheck en la imagen, usuario no root, coherencia documentación/código |

### 2.1 Identidad y contrato de salida

#### REQ-01 (corregido) — Enrutamiento autenticado

```
Cuando el cliente envía una solicitud HTTP con cabecera
`Authorization: Bearer <ID_TOKEN>` a una ruta protegida,
el sistema debe verificar el token con Firebase Admin SDK,
y si el token es válido debe reenviar la solicitud al microservicio
destino con las cabeceras `X-User-Id`, `X-User-Email`, `X-User-Roles`,
`X-Request-Id` y, si el claim existe, `X-User-Plan`.
```

Contrato completo:

| Cabecera | Origen | Regla |
|---|---|---|
| `X-User-Id` | `uid` del token | Siempre presente en ruta protegida |
| `X-User-Email` | claim `email` | Se omite si el claim no existe (ver `GW-TBD-07`) |
| `X-User-Roles` | claim `roles` | Lista separada por comas. Valores `free` y `premium` |
| `X-User-Plan` | claim `plan` | Se omite si el claim no existe. Nunca se propaga vacío |
| `X-Request-Id` | ver `REQ-09` | Siempre presente, en ruta protegida y pública |

#### REQ-07 (corregido) — Procedencia de las cabeceras de identidad

```
El sistema debe ser la única fuente de las cabeceras `X-User-*`.
Cuando una solicitud entrante incluya una cabecera `X-User-*`,
el sistema debe descartar el valor recibido antes de reenviar la solicitud.
```

```
El sistema nunca debe reenviar al microservicio destino
la cabecera `Authorization` recibida del cliente.
```

> **Nota de implementación necesaria, no opcional:** reemplazar no es lo mismo que añadir.
> El mecanismo actual produce dos valores de la misma cabecera cuando el cliente manda el suyo,
> y cuál de los dos lee el microservicio no está definido.

#### REQ-10 (nuevo) — Limpieza en ambos sentidos

```
Mientras el sistema procesa una solicitud hacia un microservicio,
la solicitud reenviada debe contener exactamente las cabeceras de identidad
que el sistema emitió, y ninguna otra cabecera `X-User-*`.
```

### 2.2 Rutas públicas y rechazo

#### REQ-02 (corregido) — Rechazo de token ausente, inválido o malformado

```
Si el cliente envía una solicitud a una ruta protegida sin cabecera
`Authorization`, con un esquema distinto de `Bearer`, con un valor de token
vacío, o con un token que Firebase rechaza,
entonces el sistema debe responder `401` con cuerpo
`{"code":"AUTH_REQUIRED","message":"<mensaje en español>"}`
sin reenviar la solicitud al microservicio destino.
```

> CM-104 cubría token ausente e inválido. El valor vacío (`Authorization: Bearer `) produce hoy
> una `IllegalArgumentException` que escapa al manejo de `FirebaseAuthException` y termina en `500`.

#### REQ-03 (corregido) — Enrutamiento público

```
Cuando la solicitud es `POST /webhooks/wompi`,
el sistema debe enrutarla a cameia-cuentas sin verificar el token de Firebase,
y debe eliminar de la solicitud reenviada toda cabecera `X-User-*`
recibida del cliente.
```

#### REQ-05 (aclarado) — Health check público

```
El sistema debe exponer `GET /actuator/health` sin autenticación de Firebase
y responder `200` con `{"status":"UP"}` cuando el servicio está operativo.
```

> **Aclaración verificada experimentalmente:** los endpoints de Actuator los atiende su propio
> handler de Spring, con más precedencia que el enrutador del Gateway, así que el filtro de
> autenticación **nunca los ve**. Este requisito se cumple por arquitectura, no por la lista de
> rutas públicas del filtro. De ahí `REQ-13`.

#### REQ-13 (nuevo) — La lista de rutas públicas solo contiene rutas reales

```
El sistema no debe declarar como ruta pública una ruta que el filtro
de autenticación no pueda alcanzar.
```

> Aplica a `/actuator/health` y `/actuator/info`, hoy presentes en la lista e inertes. Una entrada
> que parece proteger algo y no lo hace es peor que no tenerla: induce a error a quien la lee.

### 2.3 Trazabilidad

#### REQ-09 (nuevo) — Identificador de solicitud

```
Cuando el cliente envía una solicitud con la cabecera `X-Request-Id`,
el sistema debe conservar su valor y propagarlo al microservicio destino.
```

```
Si la solicitud entrante no incluye la cabecera `X-Request-Id`,
entonces el sistema debe generar un identificador único y propagarlo
al microservicio destino.
```

```
El sistema debe incluir el valor de `X-Request-Id` en toda entrada de log
que registre un rechazo de autenticación o un fallo del microservicio destino.
```

### 2.4 Errores

#### REQ-11 (nuevo) — Traducción de fallos del microservicio destino

```
Cuando el microservicio destino no responde dentro del timeout configurado,
el sistema debe responder `504`.
```

```
Si el sistema no logra establecer conexión con el microservicio destino,
entonces el sistema debe responder `503`.
```

```
Si el microservicio destino cierra la conexión o devuelve una respuesta
que el sistema no puede interpretar, entonces el sistema debe responder `502`.
```

Catálogo de respuestas de error del Gateway:

| Situación | Estado | `code` |
|---|---|---|
| Token ausente, vacío, malformado o rechazado | `401` | `AUTH_REQUIRED` |
| Ninguna ruta coincide con la URL | `404` | `NOT_FOUND` |
| Respuesta ininteligible del destino | `502` | `BAD_GATEWAY` |
| Destino inalcanzable | `503` | `SERVICE_UNAVAILABLE` |
| Destino sin responder a tiempo | `504` | `GATEWAY_TIMEOUT` |
| Cualquier otro fallo del Gateway | `500` | `INTERNAL_ERROR` |

#### REQ-12 (nuevo) — El cuerpo de error no revela detalle interno

```
El sistema debe responder los errores con un mensaje de un catálogo fijo
y nunca debe incluir en el cuerpo de la respuesta el mensaje de la excepción,
la traza, el nombre del host destino ni la URL interna del microservicio.
```

```
Mientras el sistema responde un error, debe registrar en el log
la excepción original con su `X-Request-Id` asociado.
```

### 2.5 Coherencia interna

#### REQ-08 (sin cambio, reforzado) — Sin lógica de negocio

```
Mientras el sistema actúa como gateway, no debe modificar el cuerpo de ninguna
solicitud ni respuesta, no debe consultar ninguna base de datos propia,
y no debe aplicar reglas de negocio de Cuentas, Perfil, Entrevista,
Auditoría ni Voz.
```

> Consecuencia directa sobre los claims: el sistema propaga el valor de `roles` y de `plan`
> **tal como vienen en el token**, sin cambiar mayúsculas, ordenar, traducir ni deducir uno
> a partir del otro. Decidir qué significa un rol es trabajo del microservicio.

#### REQ-14 (nuevo) — Sin configuración tipada inerte

```
El sistema no debe declarar clases de configuración tipada
que ninguna parte del sistema lea.
```

> `GatewayProperties` está declarada, anotada `@Validated` y no enlazada a nada. Sugiere una
> validación de arranque que no ocurre. También contradice a `specs/CM-104-base-tecnica/tasks.md`,
> que la describe con un campo `timeoutMs` inexistente.

#### REQ-15 (nuevo) — Los artefactos declarados existen

```
El sistema debe incluir un archivo de configuración para el perfil `local`,
que es el perfil activo por defecto.
```

> `specs/CM-104-base-tecnica/tasks.md` marca `application-local.yml` como creado. El archivo no
> existe, así que el perfil `local` se activa sin que nada lo respalde.

### 2.6 Enrutamiento y arranque

#### REQ-04 (corregido) — Tabla de enrutamiento

```
El sistema debe enrutar cada familia de rutas hacia el microservicio propietario
configurado por variable de entorno.
```

| Patrón de ruta | Microservicio destino | Caso | Sprint |
|---|---|---|---|
| `/api/v1/users/**` | cameia-cuentas | A | Sprint 1 |
| `/api/v1/profiles/**` | cameia-perfil | A | Sprint 1 |
| `/api/v1/interviews/**` | cameia-entrevista | A | Sprint 1 |
| `POST /webhooks/wompi` | cameia-cuentas | B | Sprint 3 |
| `/api/v1/voice-service/**` | cameia-voz | A | Sprint 2 |
| `/api/v1/audit/**` | cameia-auditoria | A | Sprint 2/3 |

> **Qué se corrige:** la tabla de CM-104 y la de `AGENTS.md` §5 no coincidían con
> `application.yml`. La ruta de Cuentas es `/api/v1/users/**`, no `/api/v1/accounts/**`, y
> faltaban las rutas de voz y auditoría. El `application.yml` no cambia: **la corrección es
> de documentación** y ya está aplicada (ver `tasks.md` T-01 y T-02).

#### REQ-06 (aclarado) — Arranque forzado por configuración

```
Si alguna de las variables de entorno obligatorias está ausente al arrancar,
entonces el sistema debe fallar el arranque con un mensaje descriptivo
en lugar de iniciar con configuración parcialmente rota.
```

> **Aclaración:** el fail-fast lo produce el placeholder `${FIREBASE_PROJECT_ID}` sin resolver,
> no la validación de `GatewayProperties`. Por eso eliminar esa clase (`REQ-14`) **no debilita
> este requisito**, y `GatewayStartupTest` lo sigue demostrando sin cambios.

---

## 3. Requisitos no funcionales

### REQ-NF-01 (corregido) — CORS

```
El sistema debe aplicar CORS permitiendo el origen configurado por variable
de entorno (`GATEWAY_CORS_ALLOWED_ORIGIN`) en todas las rutas,
con métodos `GET, POST, PUT, PATCH, DELETE, OPTIONS`
y cabeceras `Authorization`, `Content-Type` y `X-Request-Id`.
```

```
El sistema no debe admitir cabeceras `X-User-*` en la configuración de CORS.
```

> Admitir `X-User-Id` y `X-User-Plan` como cabeceras de solicitud, como hoy, invita al navegador
> a enviar justo lo que solo el Gateway debe emitir. `X-Request-Id` sí se admite: es trazabilidad,
> no identidad, y `REQ-09` contempla que el cliente lo aporte.

### REQ-NF-02 (confirmado) — Timeout al microservicio destino

```
El sistema debe aplicar un timeout de respuesta de 30 segundos
a cada solicitud reenviada al microservicio destino,
y responder `504` cuando se agote.
```

> Se mantiene el valor de CM-104. Lo que cambia es que deja de ser una afirmación sin prueba:
> `REQ-11` exige una prueba que lo demuestre.

### REQ-NF-03 (corregido) — Las pruebas corren en Docker y solo cuando se piden

```
Cuando se ejecuta `docker compose run --rm verify`,
el sistema debe compilar, ejecutar la suite de pruebas y reportar el resultado
sin requerir JDK ni Maven instalados localmente.
```

```
Cuando se ejecuta `docker compose up`,
el sistema debe levantar únicamente el Gateway.
```

> Hoy `verify` no está detrás de ningún perfil de Compose, así que `up` también lo arranca y se
> queda ejecutando la suite. Verificado con `docker compose config --services` y `--profiles`.

### REQ-NF-04 (sin cambio) — Sin secretos en Git

```
Ninguna clave de Firebase, credencial de servicio ni URL de downstream
debe quedar registrada en el repositorio.
```

### REQ-NF-05 (nuevo) — Healthcheck en la imagen

```
La imagen del sistema debe declarar su propia comprobación de salud,
de modo que un contenedor arrancado sin Compose también la tenga.
```

### REQ-NF-06 (nuevo) — Usuario sin privilegios

```
El sistema debe ejecutarse en el contenedor con un usuario distinto de `root`.
```

### REQ-NF-07 (nuevo) — Coherencia entre documentación y código

```
El sistema debe usar el dominio de paquetes `tech.cameia`,
igual que el resto de los microservicios de CAMEIA.
```

```
La versión de Spring Boot declarada en `pom.xml` debe coincidir
con la que afirman `README.md` y los specs.
```

---

## 4. Variables de entorno

Este spec **no agrega ni elimina variables de entorno**. Las de CM-104 §4 siguen vigentes sin
cambios. `GATEWAY_OIDC_ENABLED` pertenece al spec de OIDC.

---

## 5. Lo que este spec NO decide (abierto)

| ID | Tema | Impacto | Quién decide |
|---|---|---|---|
| `GW-TBD-06` | Formato del claim `roles` en el token: ¿cadena única o lista? | Cómo se construye `X-User-Roles` | Cuentas. Mitigado: la implementación acepta ambos (ver plan.md §3.2) |
| `GW-TBD-07` | Qué hace Cuentas si `X-User-Email` viene ausente, caso posible con métodos de login sin correo | Contrato de entrada de Cuentas | Cuentas + arquitectura |
| `GW-TBD-08` | Qué microservicio aplica los límites por plan | Ninguno sobre el Gateway hoy | Arquitectura. Por ahora se documenta como "otro microservicio", sin asumir que sea Cuentas |
| `GW-TBD-09` | Si `roles` y `plan` son redundantes, cuál consume cada microservicio | Contrato de salida | Arquitectura. El Gateway propaga ambos sin deducir uno del otro (`REQ-08`) |

`GW-TBD-06` a `GW-TBD-09` **no bloquean este spec**: ninguno cambia el código que aquí se pide.
Ninguno se cierra escribiendo código.

---

## 6. Criterio de terminado (DoD)

- [ ] `docker compose run --rm verify` en verde, con las pruebas nuevas incluidas
- [ ] `docker compose up` levanta solo el Gateway, no el servicio `verify`
- [ ] Un cliente que envía `X-User-Id` propio a una ruta protegida no logra que ese valor llegue al microservicio (prueba de contrato)
- [ ] Un cliente que envía `X-User-Id` propio a `/webhooks/wompi` no logra que ese valor llegue a Cuentas (prueba de contrato)
- [ ] Token válido → el microservicio recibe `X-User-Id`, `X-User-Email`, `X-User-Roles` y `X-Request-Id` (prueba de contrato)
- [ ] `Authorization: Bearer ` (vacío) → `401`, no `500` (prueba de contrato)
- [ ] Microservicio caído → `503`; microservicio lento → `504` (pruebas de contrato)
- [ ] Ningún cuerpo de error contiene el mensaje de una excepción (prueba de contrato)
- [ ] `X-Request-Id` ausente en la entrada → el microservicio lo recibe generado (prueba de contrato)
- [ ] CORS no admite cabeceras `X-User-*`
- [ ] El contenedor no corre como `root` y la imagen declara `HEALTHCHECK`
- [ ] `AGENTS.md` §6.5 actualizado: las brechas cerradas salen de la tabla
- [ ] `docs/COMO-FUNCIONA.md` actualizado con el estado real tras el cambio
- [ ] Ningún secreto real en el diff del PR
- [ ] Bitácora IA rellenada el mismo día
- [ ] Título del PR: `CM-104 | fix(gateway): endurecer identidad, errores y empaquetado [IA-ASISTIDO]`
