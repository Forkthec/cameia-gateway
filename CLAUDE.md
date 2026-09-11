# cameia-gateway

## Contexto del proyecto

CAMEIA ofrece práctica y simulación de entrevistas virtuales para preparar entrevistas de trabajo. Aunque puede incluir preguntas técnicas, el foco principal son las preguntas de comportamiento.

Este repositorio contiene el **API Gateway**: el único punto de entrada HTTP del sistema. Recibe todas las solicitudes de `cameia-web` (React 19), decide si la ruta exige un usuario autenticado, y reenvía cada solicitud al microservicio propietario con la identidad ya resuelta.

El Gateway **no tiene base de datos, no tiene lógica de negocio y no replica reglas de ningún contexto**. Si un cambio requiere conocer una regla de Cuentas, Perfil, Entrevista, Auditoría o Voz, ese cambio no pertenece a este repositorio.

## Responsabilidad del servicio

- Verificar el ID Token de Firebase con el Admin SDK y leer sus custom claims.
- Rechazar con `401` toda solicitud a ruta protegida cuyo token falte o sea inválido, sin tocar el downstream.
- Obtener un token OIDC firmado por Google para cada llamada saliente hacia un microservicio.
- Propagar la identidad del usuario a los microservicios mediante headers dedicados, nunca reenviando el token de Firebase.
- Enrutar hacia el servicio responsable sin acceder a sus bases de datos ni modificar cuerpos de solicitud o respuesta.
- Aplicar controles transversales aprobados: CORS, timeouts, trazabilidad y formato uniforme de errores.

Las contraseñas y credenciales de autenticación permanecen en Firebase. El Gateway no las almacena ni las registra.

## Estructura de carpetas

El Gateway **no sigue DDD**: no tiene dominio, agregados ni persistencia. Aplicar la estructura de capas de los microservicios aquí sería carga sin beneficio. La organización es por responsabilidad técnica y las carpetas están predefinidas. Crear subcarpetas solo si existe evidencia de un motivo de cambio distinto.

```text
co.edu.unicauca.cameia.gateway
├── config          // configuración de Spring, Firebase Admin SDK, propiedades tipadas
├── filter          // GlobalFilter y GatewayFilter: autenticación, identidad, OIDC saliente
├── security        // componentes reutilizables de token y claims (cuando existan varios)
└── exception       // manejo uniforme de errores HTTP del gateway
```

**Reglas de dependencia:**

- `filter` recibe sus dependencias por inyección de constructor y no importa el paquete `config` directamente. Esta regla se valida con la prueba ArchUnit `GatewayArchTest`.
- Ningún paquete importa JPA, Spring Data ni ningún cliente de base de datos. También validado por ArchUnit.
- El enrutamiento es declarativo en `application.yml`. No escribir `RouteLocator` en Java salvo que una spec aprobada lo justifique.
- Ninguna URL de downstream se escribe en el código. Siempre variable de entorno.

## Modelo de seguridad

Este es el núcleo del servicio. Las dos capas siguientes son independientes: la primera mira hacia el navegador, la segunda hacia los microservicios.

### Caso A — Endpoint que exige usuario autenticado (privado de negocio)

1. Extraer y validar el ID Token de Firebase de la solicitud entrante con el Admin SDK: firma, expiración y emisor.
2. Si el token falta o es inválido, responder `401` de inmediato. **No reenviar la solicitud al downstream.**
3. Si es válido, aplicar las verificaciones de autorización de la ruta (roles y claims) antes de continuar.
4. Antes de llamar al microservicio, obtener un token OIDC firmado por Google mediante Application Default Credentials, con `audience` igual a la URL **exacta** del microservicio destino (ver "Requisito común").
5. Adjuntar ese token OIDC como `Authorization: Bearer <token>` en la solicitud saliente.
6. Como `Authorization` queda ocupado por el token OIDC, la identidad del usuario viaja en headers **separados** (`X-User-Id`, `X-User-Roles`, …). No intentar reutilizar `Authorization` para los dos propósitos.
7. Reenviar la solicitud.

### Caso B — Endpoint que no exige usuario autenticado (público de negocio)

1. Omitir la validación del token de Firebase en la entrada. Esa ruta no requiere inicio de sesión.
2. Obtener igualmente el token OIDC firmado por Google para el microservicio destino, por el mismo mecanismo del Caso A paso 4.
3. Adjuntarlo como `Authorization: Bearer <token>` en la solicitud saliente.
4. Reenviar la solicitud.

**La única diferencia entre el Caso A y el Caso B es si el Gateway valida un token de Firebase en la entrada. El paso de token OIDC hacia el microservicio ocurre en ambos casos, sin excepciones.**

### Requisito común — generación del token OIDC

- Usar `GoogleCredentials.getApplicationDefault()`. Sin archivos de clave manuales, sin secretos que administrar.
- El `targetAudience` debe coincidir **exactamente** con la URL del microservicio destino en Cloud Run, incluida la coherencia de la barra final. Un audience que no coincide es la causa más común de un `401` inesperado.
- El audience es el mismo valor que la ruta ya declara en `uri` (`CAMEIA_CUENTAS_URL`, `CAMEIA_PERFIL_URL`, …). No introducir una segunda variable de entorno para el audience: duplicarla es crear la oportunidad de que ambas diverjan.
- Implementarlo como **un solo componente reutilizable** (una `ExchangeFilterFunction` de `WebClient`, o un filtro equivalente a nivel de gateway) aplicado a todas las rutas salientes hacia microservicios protegidos. Nunca duplicado por endpoint ni por microservicio.
- La vida del token es corta, cerca de una hora. Delegar la renovación en la librería de credenciales. No cachear la cadena del token a largo plazo.

### OIDC en desarrollo local

En Docker local los microservicios son contenedores HTTP sin IAM y no hay credenciales ADC. El paso OIDC se gobierna con `GATEWAY_OIDC_ENABLED`:

- Perfil `local`: `false` por defecto. El Gateway reenvía sin token OIDC y el desarrollo funciona sin credenciales de Google.
- Perfil de despliegue: `true` y **no admite que una variable de entorno lo apague**. Un despliegue que pueda desactivar el paso OIDC por configuración no es un despliegue seguro.

El flag apaga únicamente el paso saliente. La validación del token de Firebase del Caso A sigue activa en local.

## Contrato hacia los microservicios

Los microservicios reciben del Gateway solo los datos necesarios para su autorización de negocio, **nunca el JWT completo**. Este contrato es el mismo que `cameia-cuentas` y `cameia-entrevista` declaran como contrato de entrada; las tres partes deben cambiarlo a la vez o no cambiarlo.

| Header | Origen | Obligatorio |
|---|---|---|
| `X-User-Id` | `uid` del token de Firebase | Sí, en rutas del Caso A |
| `X-User-Email` | claim `email` | Sí, en rutas del Caso A |
| `X-User-Roles` | claim de roles, lista separada por comas | Sí, en rutas del Caso A |
| `X-Request-Id` | generado por el Gateway si el cliente no lo envía | Sí, en toda ruta |
| `X-User-Plan` | custom claim `plan` (`FREE` \| `PREMIUM`), lo escribe cameia-cuentas | Opcional: se omite el header si el claim no existe |

Reglas que acompañan al contrato:

- El Gateway **reemplaza**, no agrega, cualquier header `X-User-*` que venga del cliente. Un cliente externo no puede inyectar identidad.
- En rutas del Caso B, los headers de identidad se **eliminan** de la solicitud entrante antes de reenviar. No hay usuario autenticado; que el downstream no crea que lo hay.
- La ausencia del header `X-User-Plan` significa plan desconocido o sin suscripción activa. El Gateway no inventa un plan por defecto.
- No agregar campos derivados del JWT sin justificar su necesidad y documentar su contrato en una spec.

## Reglas de seguridad

- No registrar ID Tokens, tokens OIDC, secretos, contraseñas ni payloads sensibles completos. Un log de depuración con un token dentro es una fuga.
- No versionar claves de Firebase, archivos `.env` ni credenciales de service account. Las rutas de credenciales se documentan por nombre y propósito en `.env.example`.
- `Authorization` entrante nunca llega al downstream. En la salida, ese header es exclusivamente el token OIDC.
- La lista de headers permitidos por CORS no incluye headers de identidad: `X-User-*` los emite el Gateway, no el navegador.
- Un fallo de autenticación responde `401` con cuerpo uniforme y sin detalle interno. Un fallo del downstream responde `502`, `503` o `504` según corresponda, nunca `500` con el mensaje de la excepción.
- Exponer solo los endpoints de Actuator necesarios para salud. `/actuator/health` e `/actuator/info` son públicos por diseño; cualquier otro requiere decisión explícita.
- Toda ruta nueva declara en su spec si es Caso A o Caso B. Una ruta sin esa decisión no se implementa.

## Estado actual frente al modelo objetivo

El modelo de seguridad descrito arriba es el objetivo aprobado. La implementación de CM-104 y CM-113 todavía no lo cumple por completo. **No afirmar como implementado lo que sigue pendiente**, y no dar por cerrada una brecha sin la spec y las pruebas que la respalden:

- El paso OIDC saliente no existe: hoy ninguna solicitud hacia un microservicio lleva token de Google.
- El filtro no sanea los headers `X-User-*` entrantes, ni en rutas públicas ni en protegidas.
- CORS todavía admite `X-User-Id` y `X-User-Plan` como headers de solicitud.
- El contrato saliente implementado es `X-User-Id` y `X-User-Plan`; faltan `X-User-Email`, `X-User-Roles` y `X-Request-Id`.
- Un bearer vacío o malformado produce `500` en lugar de `401`, y un fallo del downstream produce `500` con el mensaje de la excepción en el cuerpo.
- `GatewayProperties` está declarado y validado pero nada se enlaza a él: la configuración se lee directamente de variables de entorno en el YAML.

## Metodología Spec-Driven Development

El repositorio sigue Spec-Driven Development clásico. Las especificaciones viven bajo `specs/`, una carpeta por HU (`specs/CM-NNN-descripcion/` con `spec.md`, `plan.md` y `tasks.md`). Antes de implementar una capacidad nueva:

1. Crear o actualizar la spec con contexto, alcance, requisitos en notación EARS, reglas, casos de éxito y casos de error.
2. Registrar las decisiones y contratos relevantes, incluida la clasificación Caso A / Caso B de cada ruta.
3. Implementar únicamente lo respaldado por una spec aprobada.
4. Añadir pruebas que demuestren los escenarios de la spec.
5. Actualizar la documentación si cambian contratos, configuración, rutas, headers o comandos.

No crear carpetas o especificaciones ficticias para aparentar que una decisión está tomada.

## Restricción de ambigüedades

Si una petición contiene una ambigüedad que puede afectar seguridad, contrato de headers, enrutamiento, permisos, arquitectura o comportamiento observable, el agente debe detenerse antes de editar. Debe formular preguntas concretas y resolverlas ahí mismo, con máximo 6 preguntas.

Toda ambigüedad cuya resolución tenga impacto en la arquitectura, sea de alto impacto en seguridad, o comprometa una buena práctica (por ejemplo, omitir pruebas unitarias) queda registrada en la spec de la HU que la origina, con la pregunta, la decisión adoptada y su impacto. Ambigüedades menores, sin ese impacto, pueden resolverse en la conversación.

No asumir defaults silenciosos en decisiones críticas. Una tarea puede continuar solo si las partes ambiguas son irrelevantes para el cambio o si ya existe una decisión documentada y aprobada.

## Límite de tamaño de cambios

Se prohíben cambios cuyo diff total agregado y eliminado supere 1000 líneas por solicitud. Antes de editar, estimar el tamaño. Si se supera el umbral:

- Detener la implementación.
- Informar al usuario que debe revisar cada cambio.
- Recomendar dividir la petición en incrementos pequeños, por responsabilidad o por spec.
- Proponer un orden de modularización y esperar confirmación.

No usar este límite para ocultar cambios relacionados en commits separados: cada incremento debe ser revisable y funcional.

## Reglas de nombrado

- Clases e interfaces: `PascalCase`.
- Métodos, campos y variables: `camelCase`.
- Constantes: `UPPER_SNAKE_CASE`.
- Paquetes: `lowercase`, sin guiones bajos, sin plurales inventados.
- Variables de entorno: `UPPER_SNAKE_CASE` con prefijo del ámbito (`GATEWAY_`, `CAMEIA_`, `FIREBASE_`).
- Identificadores de ruta en `application.yml`: `kebab-case` con el nombre del servicio destino (`cameia-perfil`, `wompi-webhook`).
- Sin abreviaturas: `autenticacion`, no `auth`, en texto; `credentials`, no `creds`, en código.

## Convenciones de idioma

**Regla fundamental:** El compilador lee código en inglés; las personas leen documentación en español.

| Elemento | Idioma | Ejemplo |
|---|---|---|
| Paquetes, clases, métodos, variables | **Inglés** | `FirebaseAuthGlobalFilter`, `resolveStatus()`, `targetAudience` |
| Constantes | **Inglés** `UPPER_SNAKE` | `X_USER_ID`, `PLAN_CLAIM` |
| Nombres de headers HTTP | **Inglés** `X-Pascal-Case` | `X-User-Id`, `X-Request-Id` |
| Comentarios de código (Javadoc) | **Español** | Ver "Documentación de código" |
| Mensajes de log | **Español**, sin datos sensibles | `logger.info("Token verificado para la ruta solicitada")` |
| Cuerpo de error hacia el cliente | **Español** | `{"code":"AUTH_REQUIRED","message":"Token de acceso requerido"}` |
| Commits y PRs | **Español** | `git commit -m "CM-104: Corregir propagación de identidad"` |

**Justificación:** El código convive con compiladores, intérpretes y dependencias internacionales; el inglés es el estándar. La documentación la lee el equipo en un contexto donde el español es natural.

## Documentación de código

El Gateway no expone endpoints propios de negocio, así que no publica OpenAPI: el contrato público lo documenta cada microservicio. Lo que sí documenta este repositorio:

- Javadoc en español en todo filtro, configuración y manejador de errores, explicando qué decide el componente y bajo qué condición.
- La tabla de enrutamiento y la clasificación Caso A / Caso B de cada ruta, en el `README.md` y en la spec correspondiente.
- Toda variable de entorno nueva, en `.env.example`, con nombre, propósito y si es obligatoria u opcional.

## Convenciones técnicas

**Stack base:**

- Java 21, Spring Boot 4.x y Spring Cloud Gateway sobre WebFlux.
- La versión efectiva de Spring Boot es la declarada en `pom.xml`. Hoy es `4.0.8`, mientras el `README.md` y las specs afirman `4.1.1`: es una inconsistencia real y pendiente, no un dato que se pueda citar en cualquier sentido.
- Firebase Admin SDK, exclusivo de este repositorio. Los microservicios no lo usan.
- JUnit 5, Mockito y MockWebServer para pruebas. ArchUnit para las reglas de estructura.
- No agregar dependencias de persistencia, JPA ni clientes de base de datos: las pruebas de arquitectura fallarán, y con razón.

## Verificación de cambios de código

Este repositorio no incluye Maven Wrapper. Todo se ejecuta con Docker, sin JDK ni Maven instalados.

1. Compilación y suite completa de pruebas
```powershell
docker compose run --rm verify
```
2. Construcción de la imagen
```powershell
docker build -t cameia-gateway .
```
3. Arranque local y verificación de salud
```powershell
docker compose up --build -d
curl http://localhost:8080/actuator/health
```

La red compartida debe existir antes del arranque: `docker network create cameia-net`. Ver [docs/DOCKER-LOCAL.md](docs/DOCKER-LOCAL.md).

## Flujo de contribución

- Usar ramas `<tipo>/CM-NNN-<descripcion-kebab-case>` (tipos: `feat`, `fix`, `test`, `docs`, `refactor`, `perf`, `build`, `ci`, `chore`).
- Todo cambio ordinario entra mediante PR hacia `develop` y revisión de una persona distinta del autor.
- Mantener `main` estable y promover cambios desde `develop` mediante Merge commit, en un PR independiente.
- Integrar ramas de trabajo en `develop` mediante Squash.
- Título del PR: `CM-NNN | tipo(scope): resultado`, con sufijo `[IA-ASISTIDO]` si hubo asistencia de IA.
- Actualizar la spec, el `README.md` y este documento en el mismo PR cuando cambien reglas, rutas o contratos.
