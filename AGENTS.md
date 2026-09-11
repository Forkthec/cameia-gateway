# AGENTS.md — reglas de código de `cameia-gateway`

> Reglas operativas de **este** repositorio. Deriva de
> `03092026_v1_reglas-codigo-backend-cameia.md` v1.3, pero contiene **solo lo que aplica al
> API Gateway**: quien trabaje aquí no necesita cargar las reglas de los otros cinco repositorios.
>
> Si algo de aquí contradice al documento de reglas del equipo, manda el documento del equipo
> y hay que avisarlo.
>
> **Este es el único documento de reglas para agentes en este repositorio.** No hay `CLAUDE.md`
> ni guía paralela: una segunda copia se desactualiza y empieza a contradecir a esta.

---

## 0. Antes de escribir una línea

1. **No se publica nada sin autorización expresa.** Prohibido `git push`, abrir Pull Request,
   hacer merge, usar comandos `gh` que escriban en GitHub, o tocar `main` y `develop`.
   Commits locales sí.
2. **No asumir: preguntar.** Si falta un dato, se pregunta. Si dos documentos se contradicen, se
   avisa y se detiene. Un `TBD` del equipo **nunca** se cierra escribiendo código.
3. **No inventar.** Comandos, versiones, puertos y rutas se verifican antes de escribirlos.
   Si no se ejecutó, no se escribe como si se hubiera ejecutado.
4. **Se explica mientras se trabaja**, en español: qué archivo, por qué ahí, de qué documento
   sale la decisión.
5. **La bitácora de IA se llena el mismo día.** Ver §10.

---

## 0.1 Spec Driven Development — flujo obligatorio para toda HU nueva

> Adoptado el 07/09/2026.

**Ninguna HU se implementa sin spec previo.** El ciclo SDD tiene 7 fases:

| Fase | Artefacto | Regla |
|---|---|---|
| 1 · Spec | `specs/CM-NNN-nombre/spec.md` | Requisitos EARS — qué hace y por qué, sin decir cómo |
| 2 · Clarification | notas en el spec | Resolver ambigüedades antes de diseñar |
| 3 · Plan | `specs/CM-NNN-nombre/plan.md` | Diseño técnico: filtros, rutas, decisiones |
| 4 · Tasks | `specs/CM-NNN-nombre/tasks.md` | Checkboxes de 20-30 min, trazados al REQ-N del spec |
| 5 · Implementation | código Java / YAML | Una tarea a la vez; marcar `[x]` antes de la siguiente |
| 6 · Validation | suite de pruebas | Cada REQ-N del spec tiene al menos una prueba que lo cubre |
| 7 · Loop | — | Si el scope cambia, se actualiza el spec (Spec Anchored) |

### Estructura de carpetas

```text
specs/
  CM-NNN-nombre-hu/
    spec.md    ← requisitos EARS
    plan.md    ← diseño técnico
    tasks.md   ← checkboxes
```

> CM-104 y CM-113 se implementaron antes de adoptar el flujo completo de SDD.
> A partir de **CM-114** el ciclo es obligatorio desde la Fase 1.

---

## 1. Qué es este componente

Punto de entrada HTTP de toda la plataforma CAMEIA. Responsabilidades:

- **Autenticar** al usuario con Firebase Admin SDK en las rutas que lo exigen: verifica el ID Token y lee sus custom claims.
- **Autorizar** por ruta cuando la spec lo defina, con los roles y claims del token ya verificado.
- **Firmar cada llamada saliente** con un token OIDC de Google cuyo audience es la URL exacta del microservicio destino. Esto ocurre en **todas** las rutas, con o sin usuario autenticado.
- **Propagar** la identidad del usuario en headers `X-User-*` dedicados, nunca reenviando el token de Firebase.
- **Enrutar** `/api/v1/profiles/**` → cameia-perfil, etc.
- **CORS** global para el frontend (cameia-web).
- **Responder errores uniformes** sin filtrar detalle interno al cliente.

El modelo completo de seguridad, que es la razón de existir de este componente, está en **§6**. Ninguna ruta nueva se implementa sin decidir antes si es Caso A o Caso B.

**No** es responsable de: lógica de negocio, persistencia, cálculo de cuota ni validación de datos de dominio.

- Stack: Java 21, Spring Boot 4.0.8, **Spring Cloud Gateway 5.0.3** (WebFlux reactivo), Firebase Admin SDK.
- La versión que manda es la de `pom.xml`, y es la de esta línea. El `README.md` y los specs de CM-104 y CM-113 afirman Spring Boot `4.1.1`: están equivocados y hay que corregirlos, no citarlos.
- Puerto: 8080.
- **Sin base de datos propia.** Sin Spring Data, sin JPA.

---

## 2. Idioma

**Todo el código en inglés. Todo lo que explica el código, en español.**

| En inglés | En español |
|---|---|
| Paquetes, clases, métodos, variables | Comentarios y Javadoc |
| Rutas HTTP y variables de entorno | Mensajes de log |
| Nombres de pruebas | Mensajes de commit y PR |
| | README y documentación |

---

## 3. Estructura de paquetes

```text
co.edu.unicauca.cameia.gateway
├── config          FirebaseConfig, GatewayProperties (beans de arranque)
├── filter          GlobalFilter — auth de entrada, identidad y firma OIDC de salida
└── exception       GlobalErrorHandler — formato JSON de errores HTTP
```

**Sin paquete `domain`, `application`, `service` ni `persistence`.** El gateway no tiene modelo de dominio ni base de datos. Toda la lógica de enrutamiento vive en `application.yml`.

### Reglas de dependencia

```text
filter         recibe sus beans por inyección de constructor, sin importar el paquete config
exception      independiente
```

- `filter` **no importa** el paquete `config`. La prueba ArchUnit `filterNoImportaConfig` lo verifica: un filtro recibe `FirebaseAuth` o un proveedor de credenciales por constructor, nunca la clase de configuración que los produce.
- `filter` tampoco importa `exception`: cada uno escribe su propia respuesta.
- Ningún paquete importa `jakarta.persistence` ni `org.springframework.data.jpa`. ArchUnit lo verifica.

---

## 4. Componentes existentes

| Clase | Paquete | Qué hace |
|---|---|---|
| `GatewayApplication` | raíz | `@SpringBootApplication`, punto de entrada |
| `FirebaseConfig` | `config` | Inicializa `FirebaseApp` en `@PostConstruct`; falla en arranque si las credenciales son inválidas (fail-fast) |
| `GatewayProperties` | `config` | `@ConfigurationProperties("gateway")` — `corsAllowedOrigin` (String) y `timeout` (`Duration`). **Hoy nada se enlaza a esta clase**: `application.yml` lee las variables de entorno directamente. Está declarada y validada pero inerte |
| `FirebaseAuthGlobalFilter` | `filter` | Valida Bearer token, extrae UID y plan, propaga headers, elimina `Authorization` |
| `GlobalErrorHandler` | `exception` | Formatea el error como `{"code":"...","message":"..."}`. Hoy mapea todo lo que no sea `ResponseStatusException` a 500 y copia el mensaje de la excepción al cuerpo |

**No se crea ninguna clase que no esté en esta tabla o en el spec aprobado de la HU.** El componente de firma OIDC de §6.3 todavía no existe: entra con su propia spec, no improvisado.

---

## 5. Rutas HTTP — declaradas en `application.yml`, no en código

| Ruta | Downstream | Caso (§6) | Estado |
|---|---|---|---|
| `/api/v1/profiles/**` | `${CAMEIA_PERFIL_URL}` | A | Activo (CM-113) |
| `/api/v1/users/**` | `${CAMEIA_CUENTAS_URL}` | A | Configurado, sin servicio aún |
| `/api/v1/interviews/**` | `${CAMEIA_ENTREVISTA_URL}` | A | Configurado, sin servicio aún |
| `/api/v1/voice-service/**` | `${CAMEIA_VOZ_URL}` | A | Declarado, Sprint 2 |
| `/api/v1/audit/**` | `${CAMEIA_AUDITORIA_URL}` | A | Declarado, Sprint 2/3 |
| `POST /webhooks/wompi` | `${CAMEIA_CUENTAS_URL}` | B | Declarado, operativo en Sprint 3 |
| `/actuator/health` | el gateway mismo | — | Público, no se enruta |
| `/actuator/info` | el gateway mismo | — | Público, no se enruta |

La ruta de cuentas es `/api/v1/users/**`, no `/api/v1/accounts/**`: el valor que manda es el de `application.yml`.

`/webhooks/wompi` es Caso B, no "sin seguridad": **sí lleva token OIDC** hacia cameia-cuentas. Lo único que no se valida es un token de Firebase, porque Wompi no es un usuario del sistema. Los endpoints de Actuator no son rutas del gateway: los atiende el propio servicio y por eso no hay paso OIDC.

Añadir una ruta nueva **no requiere código Java**, solo editar `application.yml` y abrir PR. Lo que sí requiere es declarar en la spec si es Caso A o Caso B.

---

## 6. Modelo de seguridad — reglas de implementación

Son **dos capas independientes**: una mira al navegador (Firebase, solo en Caso A), la otra mira a los microservicios (OIDC, siempre). Confundirlas es el error más caro de este repositorio. Nada de esta sección se toca sin spec aprobada.

### 6.1 Caso A — endpoint que exige usuario autenticado (privado de negocio)

1. Extraer y validar el ID Token de Firebase de la petición entrante con el Admin SDK: firma, expiración y emisor.
2. Si el token falta o es inválido, responder `401` con código `AUTH_REQUIRED` **de inmediato**. No se reenvía nada al downstream.
3. Si es válido, aplicar la autorización de la ruta (roles y claims) antes de continuar.
4. Obtener un token OIDC firmado por Google para el microservicio destino (§6.3).
5. Adjuntar ese token OIDC como `Authorization: Bearer <token>` en la petición saliente.
6. Propagar la identidad del usuario en los headers `X-User-*` de §6.4. Como `Authorization` ya está ocupado por el token OIDC, **no se reutiliza para los dos propósitos**.
7. Reenviar la petición.

La llamada a `FirebaseAuth.verifyIdToken()` es bloqueante, así que se ejecuta en `Schedulers.boundedElastic()`.

### 6.2 Caso B — endpoint que no exige usuario autenticado (público de negocio)

1. Omitir la validación del token de Firebase. Esa ruta no requiere inicio de sesión.
2. Obtener igualmente el token OIDC del microservicio destino, por el mismo mecanismo de §6.3.
3. Adjuntarlo como `Authorization: Bearer <token>` en la petición saliente.
4. **Eliminar** cualquier header `X-User-*` que venga del cliente: no hay usuario autenticado y el downstream no debe creer que lo hay.
5. Reenviar la petición.

Hoy las rutas de Caso B viven en la constante `PUBLIC_PATHS` del filtro (`/webhooks/wompi`, `/actuator/health`, `/actuator/info`).

> **La única diferencia entre el Caso A y el Caso B es si el gateway valida un token de Firebase en la entrada. El paso OIDC hacia el microservicio ocurre en ambos casos, sin excepciones.**

### 6.3 Requisito común — generación del token OIDC

Aplica a los dos casos:

- Usar `GoogleCredentials.getApplicationDefault()`. Sin archivos de clave manuales, sin secretos que administrar.
- El `targetAudience` debe coincidir **exactamente** con la URL del microservicio destino en Cloud Run, incluida la coherencia de la barra final. Un audience que no coincide es la causa más común de un `401` inesperado, y el síntoma no dice que el problema sea el audience.
- El audience es el mismo valor que la ruta ya declara en `uri` (`CAMEIA_PERFIL_URL`, `CAMEIA_CUENTAS_URL`, …). **No se introduce una segunda variable de entorno para el audience**: duplicar el valor es crear la oportunidad de que ambos divergan.
- Se implementa como **un único componente reutilizable** — una `ExchangeFilterFunction` de `WebClient` o un filtro equivalente a nivel de gateway — aplicado a todas las rutas salientes. Nunca duplicado por endpoint ni por microservicio.
- El token vive cerca de una hora. La renovación se delega en la librería de credenciales: **no se cachea la cadena del token** a largo plazo ni se programa refresco manual.

**OIDC en desarrollo local.** En Docker los microservicios son contenedores HTTP sin IAM y no hay credenciales ADC. El paso saliente se gobierna con `GATEWAY_OIDC_ENABLED`:

- Perfil `local`: `false` por defecto. Se reenvía sin token OIDC y el desarrollo funciona sin credenciales de Google.
- Perfil de despliegue: `true`, y **no admite que una variable de entorno lo apague**. Un despliegue capaz de desactivar el paso OIDC por configuración no es un despliegue seguro.

El flag apaga únicamente el paso saliente. La validación de Firebase del Caso A sigue activa en local.

### 6.4 Contrato de headers hacia los microservicios

Los microservicios reciben solo lo necesario para su autorización de negocio, **nunca el JWT completo**. Este es el mismo contrato de entrada que declaran `cameia-cuentas` y `cameia-entrevista`: las tres partes lo cambian a la vez o no lo cambian.

| Header | Origen | Obligatorio |
|---|---|---|
| `X-User-Id` | `uid` del token de Firebase | Sí, en Caso A |
| `X-User-Email` | claim `email` | Sí, en Caso A |
| `X-User-Roles` | claim de roles, lista separada por comas | Sí, en Caso A |
| `X-Request-Id` | lo genera el gateway si el cliente no lo envía | Sí, en toda ruta |
| `X-User-Plan` | custom claim `plan` (`FREE` \| `PREMIUM`), lo escribe cameia-cuentas | Opcional: si el claim no existe, **el header se omite** |

- El gateway **reemplaza**, no agrega, cualquier `X-User-*` que llegue del cliente. Un cliente externo no puede inyectar identidad, y `mutate().header(...)` añade en lugar de reemplazar: hay que usar `set`.
- La ausencia de `X-User-Plan` significa plan desconocido o sin suscripción activa. El gateway no inventa un plan por defecto.
- La lista `allowedHeaders` de CORS **no incluye** headers `X-User-*`: los emite el gateway, no el navegador. Admitirlos en CORS es invitar al cliente a mandar exactamente lo que solo el gateway debe firmar.
- No se agregan campos derivados del JWT sin justificar su necesidad y documentar el contrato en una spec.

### 6.5 Estado actual frente a este modelo

§6 describe el objetivo aprobado. CM-104 y CM-113 **todavía no lo cumplen**. No afirmar como implementado nada de esta lista, y no cerrar una brecha sin su spec y sus pruebas:

| Brecha | Dónde |
|---|---|
| El paso OIDC saliente no existe: ninguna petición hacia un microservicio lleva token de Google | falta el componente de §6.3 |
| El filtro no sanea los `X-User-*` entrantes, ni en Caso A ni en Caso B | `FirebaseAuthGlobalFilter.propagateClaims` usa `header()`, que agrega |
| Faltan `X-User-Email`, `X-User-Roles` y `X-Request-Id` del contrato | ídem |
| CORS admite `X-User-Id` y `X-User-Plan` como headers de petición | `application.yml`, `globalcors.allowedHeaders` |
| Un Bearer vacío o malformado lanza `IllegalArgumentException`, no `FirebaseAuthException`, y termina en `500` en vez de `401` | `onErrorResume` solo captura `FirebaseAuthException` |
| Un fallo del downstream responde `500` con el mensaje de la excepción en el cuerpo, en vez de `502`/`503`/`504` | `GlobalErrorHandler.resolveStatus` |

---

## 7. Clean Code

- Método: máximo **20 líneas**.
- Máximo **3 parámetros**. Del cuarto en adelante, objeto de configuración.
- Máximo **2 niveles de anidamiento** en código reactivo (evitar `flatMap` anidados).
- Inyección **por constructor**. Prohibido `@Autowired` en campos.
- Dependencias `private final`.
- **Sin setters públicos** en clases de configuración.

### Lo que bloquea un PR en este repositorio

1. Lógica de negocio (cuota, validación de datos de dominio) dentro de un filtro.
2. `FirebaseAuth` inyectado en una clase que no sea `filter` o `config`.
3. `jakarta.persistence` o `org.springframework.data.jpa` en cualquier clase.
4. El ID Token de Firebase reenviado al downstream. El `Authorization` de salida es **solo** el token OIDC de §6.3.
5. Una ruta saliente hacia un microservicio sin token OIDC, en cualquiera de los dos casos de §6.
6. `targetAudience` construido a partir de algo que no sea la URL destino de la ruta, o cacheado a mano.
7. Un header `X-User-*` que llegue del cliente y sobreviva hasta el downstream.
8. `X-User-Plan` propagado vacío o nulo.
9. Secreto real (`private_key`, token, contraseña) en cualquier archivo versionado.

---

## 8. Pruebas

| Tipo | Herramienta | Regla |
|---|---|---|
| Integración del filtro | `@SpringBootTest(RANDOM_PORT)` + `WebTestClient` + `MockWebServer` | Cada caso del filtro (token válido, sin token, token inválido, Bearer vacío, ruta de Caso B) tiene prueba |
| Contrato de headers | `MockWebServer.takeRequest()` | Se afirma sobre la petición que **recibe** el downstream: los `X-User-*` de §6.4 presentes, y un `X-User-Id` inyectado por el cliente reemplazado, no duplicado |
| Firma OIDC | proveedor de credenciales simulado | El audience de la petición saliente coincide con la URI de la ruta. Con `GATEWAY_OIDC_ENABLED=false` no se adjunta token; con `true` se adjunta en Caso A y en Caso B |
| Arranque fail-fast | `SpringApplication.run` en `assertThatThrownBy` | Verifica que sin `FIREBASE_PROJECT_ID` el contexto no arranca |
| Arquitectura | ArchUnit | Sin JPA, sin dependencias circulares entre paquetes |

- Firebase real se reemplaza en tests con `@TestConfiguration` que produce un `mock(FirebaseAuth.class)`.
- El archivo `src/test/resources/firebase-noop.json` se versiona (sin secretos reales).
- `SPRING_PROFILES_ACTIVE: test` desactiva `FirebaseConfig` (`gateway.firebase.enabled=false`).

Cómo ejecutar sin instalar Java ni Maven:

```bash
docker compose run --rm verify
```

Resultado esperado: `BUILD SUCCESS`. Hoy son 10 pruebas; el número sube a medida que se cubran las brechas de §6.5, y ninguna existente debe ponerse roja al hacerlo.

---

## 9. Docker y variables de entorno

El gateway necesita Docker y la red compartida `cameia-net` para conectar con cameia-perfil:

```bash
docker network create cameia-net          # una sola vez
docker compose up --build -d              # arranca el gateway
```

### Variables obligatorias en `.env`

| Variable | Descripción |
|---|---|
| `FIREBASE_PROJECT_ID` | ID del proyecto en Firebase Console |
| `FIREBASE_KEY_PATH` | Ruta absoluta al JSON de service account (nunca en el repo) |

### Variables con default razonable

| Variable | Default |
|---|---|
| `SERVER_PORT` | `8080` |
| `GATEWAY_CORS_ALLOWED_ORIGIN` | `http://localhost:5173` |
| `GATEWAY_TIMEOUT_MS` | `30000` |
| `GATEWAY_OIDC_ENABLED` | `false` en perfil `local`; en despliegue es `true` y no se puede apagar por entorno (§6.3) |
| `CAMEIA_PERFIL_URL` | fijo en compose: `http://cameia-perfil-app:8082` |
| `CAMEIA_CUENTAS_URL` | `http://cameia-cuentas-app:8081` |
| `CAMEIA_ENTREVISTA_URL` | `http://cameia-entrevista-app:8083` |

**`FIREBASE_KEY_PATH` vacío** → compose usa `/dev/null` como fallback → el gateway **no arranca** (fail-fast por diseño). El compañero de frontend necesita el JSON de Firebase de la responsable del gateway.

El archivo de clave es un recurso **solo de desarrollo local**. En Cloud Run las Application Default Credentials salen del metadata server con la identidad de la service account del gateway: ahí no hay JSON que montar ni `GOOGLE_APPLICATION_CREDENTIALS` que definir. Las mismas ADC sirven para el Admin SDK y para firmar los tokens OIDC de §6.3.

---

## 10. Bitácora de IA — OBLIGATORIA

`..\..\Entregables\07092026_02_BitacoraIA_Codigo_E2.xlsx`, hoja **`Bitacora_Codigo_E2_Sofia`**.

**Se llena el mismo día.** Todo PR con código asistido por IA lleva `[IA-ASISTIDO]` en el título.

---

## 11. Título de PR — formato obligatorio

```text
CM-NNN | tipo(scope): resultado [IA-ASISTIDO]
```

El `[IA-ASISTIDO]` va **siempre al final**, nunca al inicio.

---

## 12. Checklist antes de abrir PR

- [ ] `docker compose run --rm verify` en verde (pegar salida en el PR)
- [ ] Ningún secreto real en el diff
- [ ] El ID Token de Firebase no sale hacia el downstream
- [ ] Toda ruta nueva declara en su spec si es Caso A o Caso B (§6)
- [ ] Las rutas salientes llevan token OIDC con el audience de la URL destino, o se explica por qué no aplica
- [ ] Ningún `X-User-*` del cliente sobrevive hasta el downstream
- [ ] `X-User-Plan` no se propaga vacío
- [ ] Ninguna importación de `jakarta.persistence` ni JPA
- [ ] `.env.example` actualizado si cambiaron variables
- [ ] Bitácora del §10 con la fila correspondiente
- [ ] Revisión pedida a alguien distinto de la autora
