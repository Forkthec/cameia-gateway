# cameia-gateway

## 1. Servicio

Punto de entrada HTTP de toda la plataforma CAMEIA. Sus responsabilidades:

- **Autenticar** al usuario con Firebase Admin SDK en las rutas que lo exigen: verifica el ID Token y lee sus custom claims.
- **Autorizar** por ruta cuando la spec lo defina, con los roles y claims del token ya verificado.
- **Firmar cada llamada saliente** con un token OIDC de Google cuyo audience es la URL exacta del microservicio destino. Ocurre en **todas** las rutas, con o sin usuario autenticado.
- **Propagar** la identidad del usuario en encabezados `X-User-*` dedicados, nunca reenviando el token de Firebase.
- **Enrutar** cada prefijo `/api/v1/...` hacia el microservicio responsable.
- **CORS** global para el frontend.
- **Responder errores uniformes** sin filtrar detalle interno al cliente.

No es responsable de lógica de negocio, de persistencia, del cálculo de cuota ni de la validación de datos de dominio. Ninguna ruta nueva se implementa sin decidir antes si es Caso A o Caso B (sección 3).

Pila: Java 21, Spring Boot 4.1.1, Spring Cloud Gateway (WebFlux, release train 2025.1.3), Firebase Admin SDK y Maven Wrapper. Escucha en el puerto 8080.

Las reglas comunes de Backend están en [docs/estandar-backend.md](docs/estandar-backend.md) y los principios no negociables, en [docs/constitution.md](docs/constitution.md).

## 2. Estructura y dependencias

```text
tech.cameia.gateway
├── config          beans de arranque y guardias de configuración
├── filter          filtros globales: autenticación de entrada, identidad y firma OIDC de salida; charset de Actuator
└── exception       GlobalErrorHandler: formato JSON de los errores HTTP
```

No hay paquetes `domain`, `application`, `service` ni `persistence`: el Gateway no tiene modelo de dominio ni base de datos. El enrutamiento vive en `application.yml`.

**Reglas de dependencia**, vigiladas por `GatewayArchTest`:

- `filter` no importa `config`: un filtro recibe `FirebaseAuth` o su fuente de tokens por constructor, nunca la clase de configuración que los produce (`filterDoesNotImportConfig`).
- `filter` tampoco importa `exception`: cada uno escribe su propia respuesta.
- Ningún paquete importa `jakarta.persistence` ni `org.springframework.data.jpa` (`noJpa`, `noSpringDataJpa`).
- El código de producción no usa las constantes de `MediaType` de texto que no declaran `charset` (`productionCodeDoesNotUseCharsetlessTextMediaTypes`).

En código reactivo, máximo dos niveles de anidamiento: se evitan los `flatMap` anidados.

**Componentes existentes.** No se crea ninguna clase que no esté en esta tabla o en una spec aprobada.

| Clase | Paquete | Qué hace |
|---|---|---|
| `GatewayApplication` | raíz | `@SpringBootApplication`, punto de entrada |
| `FirebaseConfig` | `config` | Inicializa `FirebaseApp` en `@PostConstruct`; falla el arranque si las credenciales son inválidas (fail-fast). Con `FIREBASE_AUTH_EMULATOR_HOST` con valor y fuera de un despliegue usa credenciales ficticias (emulador de Firebase Auth) en vez de las de Google. Con esa variable definida, aunque esté vacía, y `K_SERVICE` o el perfil `prod`, **falla el arranque**: el emulador emite tokens sin firma y el SDK los aceptaría (`specs/CM-190-emulador-firebase-auth/`) |
| `FirebaseAuthGlobalFilter` | `filter` | Resuelve `X-Request-Id` y lo devuelve en la respuesta. Caso A: valida el Bearer token (`401` si falta, está vacío o Firebase lo rechaza), borra los `X-User-*` del cliente, emite `X-User-Id`, `X-User-Email`, `X-User-Roles`, `X-User-Plan` y `X-User-Email-Verified` con `set` y elimina `Authorization`. Caso B, por método y ruta exactos (`PUBLIC_ROUTES`, y `DEV_PUBLIC_ROUTES` solo con el perfil `local`): borra los `X-User-*` y el `Authorization` del cliente. Falla el arranque si `local` está activo con `K_SERVICE` definida |
| `GlobalErrorHandler` | `exception` | Formatea el error como `{"code":"...","message":"..."}` desde un catálogo cerrado (`AUTH_REQUIRED`, `NOT_FOUND`, `BAD_GATEWAY`, `SERVICE_UNAVAILABLE`, `GATEWAY_TIMEOUT`, `INTERNAL_ERROR`). Nunca copia el mensaje de la excepción: la registra en el log con su `X-Request-Id`. El nivel depende del estado: 5xx `ERROR` con traza; 404 `INFO` y otros 4xx `WARN`, ambos sin traza |
| `OidcConfig` | `config` | Con `gateway.oidc.signing-enabled=true` crea `GoogleIdTokenSource` y `OidcSigningGlobalFilter`; con `false` no crea ningún bean. La fuente real se apaga en pruebas con `gateway.oidc.google-credentials.enabled=false` |
| `OidcRequiredInProd` | `config` | Con el perfil `prod`, falla el arranque si la firma OIDC está apagada |
| `OidcTokenSource` | `filter` | Interfaz de una operación, `tokenFor(audience)`. Permite probar la firma con una fuente falsa |
| `GoogleIdTokenSource` | `filter` | Pide el token a Google con `GoogleCredentials.getApplicationDefault()`. Cachea el objeto `IdTokenCredentials` por audience, nunca la cadena del token. Falla el arranque si las credenciales no son una service account |
| `OidcSigningGlobalFilter` | `filter` | Orden `LOWEST_PRECEDENCE - 2`, antes del reenvío. Audience = `esquema://host[:puerto]` de la ruta, sin path, sin barra final y sin el puerto por defecto que `Route` agrega. Fija `Authorization` con `set`. Si no hay token responde `503` sin reenviar |
| `ActuatorCharsetWebFilter` | `filter` | `WebFilter` de orden `HIGHEST_PRECEDENCE`. Bajo la ruta base de Actuator (`management.endpoints.web.base-path`, por defecto `/actuator`) añade `charset=UTF-8` al `Content-Type` de texto (`text/*`, `application/json`, `application/*+json`) que no lo declara, justo antes de confirmar la respuesta; los tipos binarios y las demás rutas no se tocan. Falla el arranque si la ruta base es la raíz |

## 3. Límites de confianza

Son **dos capas independientes**: una mira al navegador (Firebase, solo en Caso A) y la otra a los microservicios (OIDC, siempre). Confundirlas es el error más caro de este repositorio. Nada de esta sección se toca sin spec aprobada.

> La única diferencia entre el Caso A y el Caso B es si el Gateway valida un token de Firebase en la entrada. El paso OIDC hacia el microservicio ocurre en ambos casos, sin excepciones.

### Caso A: endpoint que exige usuario autenticado

1. Extraer y validar el ID Token de Firebase con el Admin SDK: firma, expiración y emisor.
2. Si el token falta o es inválido, responder `401` con código `AUTH_REQUIRED` **de inmediato**; no se reenvía nada al destino.
3. Si es válido, aplicar la autorización de la ruta (roles y claims) antes de continuar.
4. Obtener el token OIDC para el microservicio destino y adjuntarlo como `Authorization: Bearer <token>`.
5. Propagar la identidad en los encabezados `X-User-*` de la sección 4. Como `Authorization` ya lleva el token OIDC, **no se reutiliza para los dos propósitos**.
6. Reenviar la petición.

`FirebaseAuth.verifyIdToken()` es bloqueante, así que se ejecuta en `Schedulers.boundedElastic()`.

### Caso B: endpoint que no exige usuario autenticado

1. Omitir la validación del token de Firebase.
2. Obtener igualmente el token OIDC del microservicio destino y adjuntarlo como `Authorization: Bearer <token>`.
3. **Eliminar** cualquier `X-User-*` y el `Authorization` que vengan del cliente: no hay usuario autenticado y el destino no debe creer que lo hay.
4. Reenviar la petición.

**Las rutas de Caso B son la constante `PUBLIC_ROUTES` del filtro, no el YAML**, y se comparan por **método y ruta exactos**, sin comodines: `GET /api/v1/users` es Caso A aunque `POST /api/v1/users` sea Caso B. Hoy son `POST /webhooks/wompi` y `POST /api/v1/users` (registro). Con el perfil `local` se suman los health v1 de `DEV_PUBLIC_ROUTES`. Una ruta nueva que no se añada a la constante queda protegida por omisión: nunca se abre sola, y abrirla exige recompilar, porque cualquier propiedad enlazada se puede sobrescribir con una variable de entorno.

- Ninguna ruta se da por pública porque «parezca» pública (registro, health, webhooks): si su spec no la declara Caso B, es Caso A.
- Cada entrada nueva de `PUBLIC_ROUTES` sale de una spec aprobada y llega con su prueba. Una entrada sin spec se revierte.
- Un endpoint público solo para desarrollo vive en `DEV_PUBLIC_ROUTES`, que solo se consulta con el perfil `local`; si además existe `K_SERVICE` (Cloud Run), el Gateway no arranca.
- Actuator no va en `PUBLIC_ROUTES`: lo atiende su propio `HandlerMapping` (orden `-100`) antes que las rutas del Gateway (orden `1`), así que el filtro nunca lo ve y todo endpoint de Actuator expuesto es público.
- `/webhooks/wompi` es Caso B, no «sin seguridad»: **sí lleva token OIDC** hacia Cuentas. Lo único que no se valida es un token de Firebase, porque Wompi no es un usuario del sistema.

### Token OIDC saliente

- Se obtiene con `GoogleCredentials.getApplicationDefault()`: sin archivos de clave manuales ni secretos que administrar.
- El `targetAudience` coincide **exactamente** con la URL del microservicio destino, incluida la coherencia de la barra final. Un audience que no coincide es la causa más común de un `401` inesperado, y el síntoma no dice que el problema sea el audience.
- El audience es el mismo valor que la ruta ya declara en `uri` (`CAMEIA_PERFIL_URL`, `CAMEIA_CUENTAS_URL`, …): no se introduce una segunda variable para el audience.
- Un único componente (`OidcSigningGlobalFilter`) lo aplica a todas las rutas salientes; nunca se duplica por endpoint ni por microservicio.
- Vive cerca de una hora y la renovación se delega en la librería de credenciales: no se cachea la cadena del token ni se programa un refresco manual.
- **En local** el paso saliente se gobierna con `GATEWAY_OIDC_ENABLED`: con el perfil `local` vale `false` por defecto y se reenvía sin token, porque los microservicios en Docker no tienen IAM. Con el perfil `prod` la firma es `true` literal y ninguna variable de entorno la apaga. El indicador solo apaga el paso saliente: la validación de Firebase del Caso A sigue activa.

### Emulador de Firebase Auth

Por defecto en local el Gateway usa el emulador (servicio `firebase-emulator` del `docker-compose.yml`): no necesita llave de service account ni un proyecto real. Con `FIREBASE_AUTH_EMULATOR_HOST` definida, aunque esté vacía, y `K_SERVICE` o el perfil `prod`, el Gateway **no arranca**.

## 4. Contrato y errores

**Rutas**, declaradas en `application.yml` y no en código. Añadir una ruta no requiere código Java, solo editar el YAML y abrir un PR; lo que sí requiere es declarar en la spec si es Caso A o Caso B.

| Ruta | Destino | Caso |
|---|---|---|
| `/api/v1/profiles/**` | `${CAMEIA_PERFIL_URL}` | A |
| `/api/v1/users/**` | `${CAMEIA_CUENTAS_URL}` | A |
| `/api/v1/interviews/**` | `${CAMEIA_ENTREVISTA_URL}` | A |
| `/api/v1/voice-service/**` | `${CAMEIA_VOZ_URL}` | A |
| `/api/v1/audit/**` | `${CAMEIA_AUDITORIA_URL}` | A |
| `POST /webhooks/wompi` | `${CAMEIA_CUENTAS_URL}` | B |
| `POST /api/v1/users` (registro) | `${CAMEIA_CUENTAS_URL}` | B; sin ruta YAML propia: la cubre `Path=/api/v1/users/**` |
| `POST /api/v1/users/me/verification` (activación por correo verificado) | `${CAMEIA_CUENTAS_URL}` | A; sin ruta YAML propia. Es la única ruta que un usuario sin correo verificado debe poder usar |
| `GET /api/v1/<prefijo>/health` (los cinco microservicios) | el de su prefijo | B **solo con el perfil `local`**; A en cualquier otro |
| `/actuator/health` y `/actuator/info` | el propio Gateway | Públicos; no se enrutan |

La ruta de Cuentas es `/api/v1/users/**`, no `/api/v1/accounts/**`: manda el valor de `application.yml`. Sin su variable, las rutas de voz y auditoría apuntan a un host por defecto (`http://cameia-voz:8080` y `http://cameia-auditoria:8080`) y responden `503` mientras no exista.

**Encabezados hacia los microservicios.** Reciben solo lo necesario para su autorización de negocio, **nunca el JWT completo**. Es el mismo contrato de entrada de `cameia-cuentas` y `cameia-entrevista`: las partes lo cambian a la vez o no lo cambian.

| Encabezado | Origen | Obligatorio |
|---|---|---|
| `X-User-Id` | `uid` del token de Firebase | Sí, en Caso A |
| `X-User-Email` | claim `email` | Sí, en Caso A |
| `X-User-Roles` | claim de roles, lista separada por comas | Sí, en Caso A |
| `X-Request-Id` | lo genera el Gateway si el cliente no lo envía | Sí, en toda ruta |
| `X-User-Plan` | custom claim `plan` (`FREE` o `PREMIUM`), que escribe `cameia-cuentas` | No: si el claim no existe, **el encabezado se omite** |
| `X-User-Email-Verified` | claim `email_verified` del token | Sí, en Caso A, **incluido cuando vale `false`**; solo se omite si el claim no existe |

- El Gateway **reemplaza**, no agrega, cualquier `X-User-*` que llegue del cliente: `mutate().header(...)` añade en lugar de reemplazar, así que se usa `set`. Un cliente externo no puede inyectar identidad.
- La ausencia de `X-User-Plan` significa plan desconocido o sin suscripción activa; el Gateway no inventa un plan por defecto.
- `X-User-Email-Verified` se lee del mapa de claims y no de `FirebaseToken.isEmailVerified()`: ese método devuelve `false` cuando el claim no existe y convertiría una ausencia en una afirmación. Un `false` sí se propaga; la ausencia significa «no probado».
- El Gateway **no bloquea** las rutas de Caso A cuando `email_verified` es `false` (ver sección 10). Quien lo implemente debe exceptuar `POST /api/v1/users/me/verification`.
- No se agregan campos derivados del JWT sin justificar su necesidad y documentar el contrato en una spec.

**Trazabilidad.** El Gateway genera `X-Request-Id` cuando el cliente no lo envía y lo devuelve en el encabezado de cada respuesta; los servicios lo toman como `requestId`.

**Errores.** El Gateway conserva su formato `{"code","message"}` con un catálogo cerrado, escrito con `Content-Type: application/json;charset=UTF-8`. El catálogo está en [docs/errores.md](docs/errores.md) y la decisión, en el [ADR 0001](docs/adr/0001-codigo-de-error-y-request-id.md). La forma de error de los servicios está en la [sección 6 del estándar](docs/estandar-backend.md#6-errores).

## 5. Datos

El Gateway no tiene base de datos ni persistencia: no usa Spring Data ni JPA, y ArchUnit prohíbe importarlos en cualquier paquete.

## 6. Seguridad

Las reglas comunes (ASVS nivel 1, OWASP API Security Top 10, secretos y registros) están en la [sección 8 del estándar](docs/estandar-backend.md#8-seguridad). Las propias del Gateway:

- **CORS.** La lista `allowedHeaders` no incluye ningún `X-User-*`: los emite el Gateway, no el navegador. Admitirlos invitaría al cliente a mandar justo lo que solo el Gateway debe firmar.
- **Actuator.** Solo `health` e `info` son públicos (`MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`).
- **Emulador de Firebase Auth.** Es solo para desarrollo local (ver sección 3).
- El archivo de clave de la service account es un recurso solo de desarrollo local. En Cloud Run las credenciales por defecto salen del servidor de metadatos con la identidad de la service account del Gateway: no hay JSON que montar ni `GOOGLE_APPLICATION_CREDENTIALS` que definir. Las mismas credenciales sirven para el Admin SDK y para firmar los tokens OIDC.

**Lo que bloquea un PR en este repositorio:**

1. Lógica de negocio (cuota, validación de datos de dominio) dentro de un filtro.
2. `FirebaseAuth` inyectado en una clase que no sea de `filter` o `config`.
3. `jakarta.persistence` o `org.springframework.data.jpa` en cualquier clase.
4. El ID Token de Firebase reenviado al destino: el `Authorization` de salida es **solo** el token OIDC.
5. Una ruta saliente hacia un microservicio sin token OIDC, en cualquiera de los dos casos.
6. `targetAudience` construido a partir de algo que no sea la URL destino de la ruta, o cacheado a mano.
7. Un `X-User-*` que llegue del cliente y sobreviva hasta el destino.
8. `X-User-Plan` propagado vacío o nulo.
9. Un secreto real (`private_key`, token, contraseña) en cualquier archivo versionado.

## 7. Pruebas

| Tipo | Herramienta | Regla |
|---|---|---|
| Integración del filtro | `@SpringBootTest(RANDOM_PORT)`, `WebTestClient` y `MockWebServer` | Cada caso del filtro (token válido, sin token, token inválido, Bearer vacío, ruta de Caso B) tiene prueba |
| Contrato de encabezados | `MockWebServer.takeRequest()` | Se afirma sobre la petición que **recibe** el destino: los `X-User-*` de la sección 4 presentes, y un `X-User-Id` inyectado por el cliente reemplazado, no duplicado |
| Firma OIDC | fuente de tokens simulada | El audience de la petición saliente coincide con la URI de la ruta. Con `GATEWAY_OIDC_ENABLED=false` no se adjunta token; con `true` se adjunta en Caso A y en Caso B |
| Arranque fail-fast | `SpringApplication.run` en `assertThatThrownBy` | Sin `FIREBASE_PROJECT_ID` el contexto no arranca |
| Arquitectura | ArchUnit (`GatewayArchTest`) | Sin JPA, sin que `filter` importe `config` y sin constantes de `MediaType` sin `charset` |

- Firebase real se reemplaza en las pruebas con una `@TestConfiguration` que produce un `mock(FirebaseAuth.class)`.
- `src/test/resources/firebase-noop.json` se versiona y no lleva secretos reales.
- El perfil `test` desactiva `FirebaseConfig` (`gateway.firebase.enabled=false`).
- Las reglas de pruebas y de cobertura están en la [sección 9 del estándar](docs/estandar-backend.md#9-pruebas-y-cobertura).

## 8. Verificación

Verificación completa: `./mvnw.cmd clean verify`. Genera el informe de cobertura en `target/site/jacoco/index.html`.

- Sin instalar Java ni Maven: `docker compose run --rm verify`.
- Arranque local: `docker network create cameia-net` (una sola vez) y `docker compose up --build -d`. El `docker-compose.yml` declara el perfil `local`, el emulador de Firebase Auth y la red compartida `cameia-net`.
- Con el Gateway en `http://localhost:8080`: salud en `/actuator/health`.

**Variables obligatorias** (en `.env`):

| Variable | Descripción |
|---|---|
| `FIREBASE_PROJECT_ID` | ID del proyecto. Con el emulador, `demo-cameia`; con un proyecto real, el ID de Firebase Console. Sin ella el Gateway no arranca |
| `FIREBASE_AUTH_EMULATOR_HOST` | Camino por defecto en local: dirección del emulador, `cameia-firebase-emulator:9099`. Sustituye a la llave. **Nunca en un despliegue**: el Gateway no arranca si la ve junto a `K_SERVICE` o al perfil `prod` |
| `FIREBASE_KEY_PATH` | Solo sin emulador: ruta absoluta al JSON de la service account, que el compose monta como volumen de solo lectura. Nunca dentro del repositorio |

Sin `FIREBASE_AUTH_EMULATOR_HOST` y con `FIREBASE_KEY_PATH` vacío, el compose usa `/dev/null` como respaldo y el Gateway **no arranca**, por diseño.

**Variables con valor por defecto:**

| Variable | Valor por defecto |
|---|---|
| `PORT` | Sin valor. Cloud Run la inyecta y manda sobre `SERVER_PORT` |
| `SERVER_PORT` | `8080` (`server.port: ${PORT:${SERVER_PORT:8080}}`) |
| `SPRING_PROFILES_ACTIVE` | Sin valor: `application.yml` no activa ningún perfil. `docker-compose.yml` y `.env.example` declaran `local`; quien arranque desde el IDE debe declararlo también |
| `GATEWAY_CORS_ALLOWED_ORIGIN` | `http://localhost:5173`. Admite varios orígenes separados por coma |
| `GATEWAY_TIMEOUT_MS` | `30000` |
| `GATEWAY_OIDC_ENABLED` | `false`. Solo alimenta `gateway.oidc.signing-enabled`; con el perfil `prod` no tiene efecto, porque `application-prod.yml` fija `true` literal |
| `CAMEIA_PERFIL_URL` | Sin valor en `application.yml`. El compose la fija en `http://cameia-perfil-app:8082` |
| `CAMEIA_CUENTAS_URL` | Sin valor en `application.yml`. El compose usa `http://cameia-cuentas:8081` |
| `CAMEIA_ENTREVISTA_URL` | `http://cameia-entrevista:8080`, en `application.yml` y en el compose |
| `CAMEIA_VOZ_URL`, `CAMEIA_AUDITORIA_URL` | `http://cameia-voz:8080` y `http://cameia-auditoria:8080` |
| `LOG_LEVEL` | `INFO` |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health,info` |

## 9. Contribución

Rama, commit, tipos, título de PR, revisión y merge: rige [CONTRIBUTING.md](CONTRIBUTING.md). Lo que este repositorio añade:

- El título del PR lleva `[IA-ASISTIDO]` al final cuando hubo IA; el commit no lo lleva. Un commit asistido por IA lleva el trailer `Co-Authored-By` con el modelo.
- Una IA puede abrir un PR; nunca lo fusiona. El control humano lo marca la persona que revisa.
- Un PR cubre una pieza reconocible y no pasa de 1000 líneas entre agregadas y eliminadas.
- Antes del código hay una spec aprobada en `specs/CM-NNN-Descripcion/` (`spec.md`, `plan.md` y `tasks.md`); el flujo está en la sección 12 de [docs/estandar-backend.md](docs/estandar-backend.md).
- Las decisiones con peso humano se registran en [docs/bitacora-ia/](docs/bitacora-ia/README.md).

Las specs nuevas viven en `specs/CM-NNN-Descripcion/` con `Descripcion` en PascalCase; las carpetas de spec existentes con otro nombre no se renombran.

## 10. Pendientes

| Pendiente | Responsable | Qué bloquea |
|---|---|---|
| `GW-TBD-17`: responder `403` con `EMAIL_NOT_VERIFIED` en las rutas de Caso A cuando `email_verified` es `false`, exceptuando `POST /api/v1/users/me/verification` | Backend | Que `cameia-cuentas` y el Gateway apliquen la misma regla de correo sin verificar |
| `GW-TBD-11`: estado de los endpoints de Actuator, hoy públicos por arquitectura | Backend | Exponer cualquier endpoint de Actuator distinto de `health` e `info` |
| Lista definitiva de rutas públicas (Caso B) | Backend, con el Product Owner | Abrir cualquier ruta nueva al público |
| Puerto de `cameia-entrevista`: el Gateway apunta al `8080` en `application.yml` y en el compose, y `.env.example` sugiere `8083` | Backend | Que las tres configuraciones coincidan con el puerto único del servicio |
| Prueba de extremo a extremo Gateway → Perfil y URL de producción | DevOps | Afirmar que la firma OIDC funciona en producción |
