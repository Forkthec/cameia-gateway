# Plan técnico — CM-104: Base técnica del API Gateway

- **HU asociada:** CM-104
- **Fecha:** 07/09/2026
- **Estado:** propuesta pendiente de aprobación
- **Flujo SDD:** Fase 3 — Diseño técnico (no hay código hasta que esto esté aprobado)
- **Prerequisito:** `spec.md` v1 aprobado

---

## 1. Decisiones de tecnología

### 1.1 Versiones de dependencias

| Artefacto | Versión | Justificación |
|---|---|---|
| `spring-boot-starter-parent` | **4.1.1** | Línea base del equipo (igual que cameia-perfil) |
| `spring-cloud-dependencies` BOM | **2025.0.x** (última estable al implementar) | Release train oficial para Spring Boot 4.x. Verificar en [spring.io/projects/spring-cloud](https://spring.io/projects/spring-cloud) antes de fijar la versión exacta en el pom |
| `firebase-admin` | **9.4.x** (última estable Maven Central) | Sin versión hardcodeada aquí — se fija al implementar para tomar la más reciente que no sea RC |
| `archunit-junit5` | **1.5.0** | Mismo que cameia-perfil |
| `spring-boot-starter-actuator` | gestionada por parent | `health` e `info` expuestos, sin auth |

### 1.2 Por qué Spring Cloud Gateway (WebFlux) y no Spring MVC

El gateway no tiene lógica de negocio: el 95 % del trabajo es configuración declarativa
de rutas + un filtro de auth transversal. Spring Cloud Gateway (SCG) provee todo eso fuera
de la caja sobre WebFlux/Reactor:

- Las rutas se definen en YAML sin código Java adicional.
- El `GlobalFilter` aplica automáticamente a todas las rutas marcadas como protegidas.
- SCG maneja el proxy HTTP completo (streaming, headers, timeouts) sin que nosotros
  implementemos el reenvío.
- WebFlux es no bloqueante: la verificación de Firebase (I/O de red) no bloquea hilos.

**Consecuencia de arquitectura:** No hay capa JPA, ni flyway, ni base de datos. El pom
no incluye `spring-boot-starter-data-jpa`, `postgresql` ni `flyway`.

---

## 2. Estructura de paquetes

```
co.edu.unicauca.cameia.gateway
├── GatewayApplication.java                  // @SpringBootApplication
├── config/
│   ├── FirebaseConfig.java                  // inicializa FirebaseApp al arrancar
│   └── GatewayProperties.java               // @ConfigurationProperties("gateway") — env vars tipadas
├── filter/
│   └── FirebaseAuthGlobalFilter.java        // GlobalFilter de SCG — aplica a todas las rutas protegidas
└── exception/
    └── GlobalErrorHandler.java              // WebExceptionHandler — da forma al 401 como JSON
```

No hay capas `domain`, `application`, `infrastructure` ni `presentation` porque el gateway
no tiene modelo de dominio ni persistencia. ArchUnit verificará que `filter` no importe `config`
(directividad de dependencias) y que ninguna clase importe `jakarta.persistence.*`.

### 2.1 Convención de nombres

| Tipo | Convención | Ejemplo |
|---|---|---|
| Clases | PascalCase | `FirebaseAuthGlobalFilter` |
| Métodos y variables | camelCase | `extractUserPlan` |
| Constantes | UPPER_SNAKE | `X_USER_ID_HEADER` |
| Paquetes | lowercase sin guiones | `co.edu.unicauca.cameia.gateway.filter` |
| Config YAML (gateway) | `gateway.` prefix | `gateway.cors.allowed-origin` |
| Env vars | UPPER_SNAKE Spring estándar | `GATEWAY_CORS_ALLOWED_ORIGIN` |

---

## 3. Diseño de componentes clave

### 3.1 `GatewayProperties` — fail-fast en variables obligatorias

```
@ConfigurationProperties("gateway")
@Validated
clase GatewayProperties:
  @NotBlank String corsAllowedOrigin
  @NotNull Duration timeout

// Las URLs downstream se leen con @Value + @NotBlank sobre variables Spring estándar
// (CAMEIA_CUENTAS_URL → spring.cloud.gateway.routes[n].uri)
// El fail-fast viene de que SCG no puede construir la ruta si la URI es null/blank.
// Para FIREBASE_PROJECT_ID y GOOGLE_APPLICATION_CREDENTIALS el fail-fast está en
// FirebaseConfig: si fallan al inicializar, la app no arranca.
```

### 3.2 `FirebaseConfig` — inicialización única de Firebase Admin SDK

```
@Configuration
clase FirebaseConfig:
  @PostConstruct init():
    si FirebaseApp.getApps() está vacío:
      credentials = GoogleCredentials.getApplicationDefault()
                  // GOOGLE_APPLICATION_CREDENTIALS apunta al JSON de la service account
      options = FirebaseOptions.builder()
                  .setCredentials(credentials)
                  .setProjectId(${FIREBASE_PROJECT_ID})
                  .build()
      FirebaseApp.initializeApp(options)
    // Si falla (archivo no existe, proyecto incorrecto) → excepción en arranque → falla rápido
```

> **Testabilidad:** Para tests unitarios e integración, inyectamos un `FirebaseAuth` mockeado
> en lugar de llamar a `FirebaseAuth.getInstance()`. Ver §4.

### 3.3 `FirebaseAuthGlobalFilter` — filtro de autenticación

```
orden = Ordered.HIGHEST_PRECEDENCE   // corre antes de los filtros internos de SCG

filter(ServerWebExchange exchange, GatewayFilterChain chain):
  request = exchange.getRequest()

  // 1. ¿La ruta está en la lista de exclusiones (public paths)?
  si isPublicPath(request.getPath()):
    return chain.filter(exchange)   // sin verificar token

  // 2. Extraer Bearer token
  authHeader = request.getHeaders().getFirst("Authorization")
  si authHeader es null o no empieza con "Bearer ":
    return writeUnauthorized(exchange, "Token de acceso requerido")

  idToken = authHeader.substring(7)

  // 3. Verificar con Firebase (operación async → Mono)
  return Mono.fromCallable(() -> firebaseAuth.verifyIdToken(idToken))
    .subscribeOn(Schedulers.boundedElastic())   // Firebase SDK es bloqueante
    .flatMap(decodedToken -> {
        uid = decodedToken.getUid()
        plan = decodedToken.getClaims().get("plan")  // puede ser null

        mutatedRequest = request.mutate()
          .header("X-User-Id", uid)
          .header("Authorization", "")          // eliminar token original (REQ-07)

        si plan no es null:
          mutatedRequest.header("X-User-Plan", plan.toString())

        mutatedRequest.build()
        return chain.filter(exchange.mutate().request(mutatedRequest.build()).build())
    })
    .onErrorResume(FirebaseAuthException.class, e ->
        writeUnauthorized(exchange, "Token de acceso inválido"))

writeUnauthorized(exchange, message):
  exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED)
  body = {"code":"AUTH_REQUIRED","message": message}
  return exchange.getResponse().writeWith(Mono.just(body encodificado como DataBuffer))
```

**Rutas públicas** (no pasan por verificación de Firebase):

```java
private static final Set<String> PUBLIC_PATHS = Set.of(
    "/webhooks/wompi",
    "/actuator/health",
    "/actuator/info"
);
```

### 3.4 Configuración de rutas — `application.yml`

```yaml
spring:
  cloud:
    gateway:
      routes:
        # Sprint 1 — activas
        - id: cameia-cuentas
          uri: ${CAMEIA_CUENTAS_URL}
          predicates:
            - Path=/api/v1/users/**
        - id: cameia-perfil
          uri: ${CAMEIA_PERFIL_URL}
          predicates:
            - Path=/api/v1/profiles/**
        - id: cameia-entrevista
          uri: ${CAMEIA_ENTREVISTA_URL}
          predicates:
            - Path=/api/v1/interviews/**
        # Ruta pública (Sprint 3, declarada ahora)
        - id: wompi-webhook
          uri: ${CAMEIA_CUENTAS_URL}
          predicates:
            - Path=/webhooks/wompi
            - Method=POST
        # Sprint 2/3 — declaradas; 503 hasta que el servicio exista
        - id: cameia-voz
          uri: ${CAMEIA_VOZ_URL:http://cameia-voz:8080}
          predicates:
            - Path=/api/v1/voice-service/**
        - id: cameia-auditoria
          uri: ${CAMEIA_AUDITORIA_URL:http://cameia-auditoria:8080}
          predicates:
            - Path=/api/v1/audit/**
      httpclient:
        response-timeout: ${GATEWAY_TIMEOUT_MS:30000}ms

management:
  endpoints:
    web:
      exposure:
        include: ${MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE:health,info}

logging:
  level:
    root: ${LOG_LEVEL:INFO}
    co.edu.unicauca.cameia.gateway: DEBUG
```

### 3.5 CORS — `application.yml` (global, no en Java)

```yaml
spring:
  cloud:
    gateway:
      globalcors:
        cors-configurations:
          '[/**]':
            allowed-origins:
              - ${GATEWAY_CORS_ALLOWED_ORIGIN:http://localhost:5173}
            allowed-methods:
              - GET
              - POST
              - PUT
              - PATCH
              - DELETE
              - OPTIONS
            allowed-headers:
              - Authorization
              - Content-Type
              - X-User-Id
              - X-User-Plan
            allow-credentials: true
            max-age: 3600
```

---

## 4. Estrategia de tests

El gateway **no tiene base de datos** → no hay H2, no hay Flyway en tests.

### 4.1 Tests de contrato del filtro (`FirebaseAuthGlobalFilterTest`)

- **Tipo:** `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `WebTestClient`
- **Mocking:** Se inyecta un `FirebaseAuth` falso (Mockito) vía `@TestConfiguration`
  que devuelve un `FirebaseToken` mockeado cuando el token de prueba es válido,
  y lanza `FirebaseAuthException` cuando es inválido.
- **Casos cubiertos:**
  - Token válido con claim `plan=PREMIUM` → 200 al downstream, headers presentes
  - Token válido sin claim `plan` → 200 al downstream, header `X-User-Plan` ausente
  - Header `Authorization` ausente → 401
  - Token malformado → 401
  - `POST /webhooks/wompi` sin token → llega al downstream sin `X-User-Id`
  - `GET /actuator/health` sin token → 200

- **Mock del downstream:** `MockWebServer` (OkHttp3, disponible en scope test) o
  `WireMockServer` para simular que cameia-cuentas/perfil/entrevista responden 200.

### 4.2 Tests de configuración de arranque

- `GatewayStartupTest`: verifica que sin `FIREBASE_PROJECT_ID` el contexto falla.
  Implementado con `@SpringBootTest` + `assertThatThrownBy` sobre `ApplicationContextException`.

### 4.3 Tests de arquitectura (ArchUnit)

```java
@AnalyzeClasses(packages = "co.edu.unicauca.cameia.gateway")
clase GatewayArchTest:
  // filter no importa config (excepto FirebaseAuth que se inyecta por constructor)
  // ningún paquete importa jakarta.persistence
  // ningún paquete importa org.springframework.data.jpa
```

### 4.4 Testabilidad del filtro Firebase

El filtro recibe `FirebaseAuth` por constructor (no usa `FirebaseAuth.getInstance()`),
lo que permite inyectar un mock sin inicializar Firebase Admin SDK en tests.

```java
// En producción (FirebaseConfig):
@Bean
FirebaseAuth firebaseAuth() { return FirebaseAuth.getInstance(); }

// En tests (@TestConfiguration):
@Bean
FirebaseAuth firebaseAuth() { return mock(FirebaseAuth.class); }
```

---

## 5. Estructura de archivos a crear

```
cameia-gateway/
├── pom.xml
├── .env                          // NO se commitea (en .gitignore)
├── .env.example                  // sí se commitea — plantilla con descripción
├── docker-compose.yml            // servicio 'verify' que corre mvn verify dentro del contenedor
├── Dockerfile                    // imagen builder + imagen runtime (JRE 21 slim)
├── src/
│   ├── main/
│   │   ├── java/co/edu/unicauca/cameia/gateway/
│   │   │   ├── GatewayApplication.java
│   │   │   ├── config/
│   │   │   │   ├── FirebaseConfig.java
│   │   │   │   └── GatewayProperties.java
│   │   │   ├── filter/
│   │   │   │   └── FirebaseAuthGlobalFilter.java
│   │   │   └── exception/
│   │   │       └── GlobalErrorHandler.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── application-local.yml   // overrides locales
│   └── test/
│       ├── java/co/edu/unicauca/cameia/gateway/
│       │   ├── filter/
│       │   │   └── FirebaseAuthGlobalFilterTest.java
│       │   ├── config/
│       │   │   └── GatewayStartupTest.java
│       │   └── arch/
│       │       └── GatewayArchTest.java
│       └── resources/
│           └── application-test.yml    // desactiva firebase init, apunta downstream a MockWebServer
```

### 5.1 Dockerfile (multi-stage)

```
Etapa 1 — builder: maven:3.9-eclipse-temurin-21
  COPY pom.xml + src
  RUN mvn package -DskipTests

Etapa 2 — runtime: eclipse-temurin:21-jre-alpine
  COPY --from=builder target/*.jar app.jar
  ENTRYPOINT ["java", "-jar", "/app.jar"]
```

### 5.2 docker-compose.yml (servicio verify)

```yaml
services:
  verify:
    build:
      context: .
      target: builder          // usa solo la etapa builder (no necesita runtime)
    command: mvn verify
    volumes:
      - maven-cache:/root/.m2  // caché de dependencias entre ejecuciones
    environment:
      // variables mínimas para que el contexto de test arranque
      SPRING_PROFILES_ACTIVE: test
      FIREBASE_PROJECT_ID: test-project
      GOOGLE_APPLICATION_CREDENTIALS: /workspace/src/test/resources/firebase-noop.json
      CAMEIA_CUENTAS_URL: http://localhost:9001
      CAMEIA_PERFIL_URL: http://localhost:9002
      CAMEIA_ENTREVISTA_URL: http://localhost:9003
      SERVER_PORT: 0
volumes:
  maven-cache:
```

> `firebase-noop.json` es un JSON de credenciales de service account con campos vacíos/ficticios
> comprometido en `src/test/resources/` — **no contiene secretos reales**.
> Su único propósito es que el SDK no falle al parsear el archivo; el bean `FirebaseAuth`
> en tests es un mock de Mockito que nunca llama a Firebase.

---

## 6. `.env.example` — contenido planeado

```dotenv
# ── Identidad del servicio ──────────────────────────────────────────────────
SPRING_APPLICATION_NAME=cameia-gateway
SPRING_PROFILES_ACTIVE=local        # local | prod
SERVER_PORT=8080

# ── Firebase (OBLIGATORIO) ──────────────────────────────────────────────────
FIREBASE_PROJECT_ID=                 # ID del proyecto en Firebase Console
GOOGLE_APPLICATION_CREDENTIALS=     # Ruta absoluta al JSON de la service account

# ── URLs de microservicios downstream (OBLIGATORIO en prod) ─────────────────
CAMEIA_CUENTAS_URL=http://cameia-cuentas:8080
CAMEIA_PERFIL_URL=http://cameia-perfil:8080
CAMEIA_ENTREVISTA_URL=http://cameia-entrevista:8080

# ── URLs de microservicios futuros (Sprint 2/3, opcional ahora) ─────────────
# CAMEIA_VOZ_URL=http://cameia-voz:8080
# CAMEIA_AUDITORIA_URL=http://cameia-auditoria:8080

# ── CORS ────────────────────────────────────────────────────────────────────
GATEWAY_CORS_ALLOWED_ORIGIN=http://localhost:5173   # origen de cameia-web en local

# ── Timeouts ────────────────────────────────────────────────────────────────
GATEWAY_TIMEOUT_MS=30000            # ms; default 30 s

# ── Logging y Actuator ──────────────────────────────────────────────────────
LOG_LEVEL=INFO
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info
```

---

## 7. Riesgos y mitigaciones

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| Versión de `spring-cloud-dependencies` incompatible con SB 4.1.1 | Media | Verificar en spring.io antes de escribir el pom; si no hay release estable, usar snapshot con flag explícito en el pom |
| `FirebaseAuth.verifyIdToken` es bloqueante y satura el event loop | Alta (si no se trata) | Usar `Schedulers.boundedElastic()` para mover la llamada fuera del reactor thread (diseñado así en §3.3) |
| Claim `plan` con nombre distinto al acordado (`plan`) | Media | Coordinación explícita con cameia-cuentas; la constante del nombre del claim estará en una sola clase (`FirebaseAuthGlobalFilter.PLAN_CLAIM`) para cambiarla en un lugar |
| Tests pasan localmente pero falla `docker compose run --rm verify` por caché de Maven | Baja | El volumen `maven-cache` en docker-compose.yml persiste la caché entre ejecuciones |
