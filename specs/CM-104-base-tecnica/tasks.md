# Tasks — CM-104: Base técnica del API Gateway

- **HU asociada:** CM-104
- **Fecha:** 07/09/2026
- **Estado:** completado — implementado y mergeado a develop
- **Flujo SDD:** Fase 4 — Checkboxes (plan.md aprobado)
- **Rama:** `CM-104-base-tecnica-gateway` → `develop`

Marcar cada tarea al completarla. No pasar a la siguiente sección hasta que
`docker compose run --rm verify` pase en verde al final de la sección.

---

## Bloque 0 — Rama Git

- [x] Crear rama `CM-104-base-tecnica-gateway` desde `develop`
- [x] Verificar con `git status` que no hay cambios sin commitear antes de arrancar

---

## Bloque 1 — Esqueleto del proyecto Maven

- [x] Crear `pom.xml` con:
  - [x] Parent `spring-boot-starter-parent:4.1.1`
  - [x] `groupId: tech.cameia`, `artifactId: cameia-gateway`
  - [x] BOM `spring-cloud-dependencies:2025.0.x` (verificar versión exacta en spring.io)
  - [x] Dependencia `spring-cloud-starter-gateway` (sin versión — la gestiona el BOM)
  - [x] Dependencia `firebase-admin:9.4.x` (verificar versión exacta en Maven Central)
  - [x] Dependencia `spring-boot-starter-actuator`
  - [x] Dependencia `spring-boot-starter-test` scope test
  - [x] Dependencia `archunit-junit5:1.5.0` scope test
  - [x] Plugin `spring-boot-maven-plugin`
  - [x] Propiedad `<java.version>21</java.version>`
- [x] Crear clase `GatewayApplication.java` en `tech.cameia.gateway`
  con `@SpringBootApplication` y método `main`

---

## Bloque 2 — Configuración de entorno

- [x] Crear `src/main/resources/application.yml` con:
  - [x] Rutas de Spring Cloud Gateway (6 rutas según tabla de spec.md REQ-04)
  - [x] Timeout global (`${GATEWAY_TIMEOUT_MS:30000}`)
  - [x] CORS global (REQ-NF-01)
  - [x] Actuator (`health,info` expuestos sin auth)
  - [x] Logging raíz y paquete gateway
- [x] Crear `src/main/resources/application-local.yml` (overrides vacíos, placeholder)
- [x] Crear `.env.example` con todas las variables del §4 del spec + §6 del plan,
  con comentarios descriptivos por sección
- [x] Verificar que `.env` está en `.gitignore` (añadir si no está)
- [x] Verificar que `*.json` en `src/test/resources/` NO está en `.gitignore`
  (el noop de Firebase sí se commitea)

---

## Bloque 3 — Componentes Java

- [x] Crear `GatewayProperties.java` en `config/`
  - [x] `@ConfigurationProperties("gateway")`
  - [x] `@Validated`
  - [x] Campo `corsAllowedOrigin` con `@NotBlank`
  - [x] Campo `timeoutMs` con `@NotNull`
- [x] Crear `FirebaseConfig.java` en `config/`
  - [x] Inicializa `FirebaseApp` en `@PostConstruct` usando `GOOGLE_APPLICATION_CREDENTIALS`
    y `FIREBASE_PROJECT_ID`
  - [x] `@Bean FirebaseAuth firebaseAuth()` → `FirebaseAuth.getInstance()`
  - [x] Falla de arranque si credenciales son inválidas (excepción no capturada)
- [x] Crear `FirebaseAuthGlobalFilter.java` en `filter/`
  - [x] Implementa `GlobalFilter`, `Ordered` (prioridad `HIGHEST_PRECEDENCE`)
  - [x] Recibe `FirebaseAuth` por constructor (inyección)
  - [x] Constante `PLAN_CLAIM = "plan"`
  - [x] Lista `PUBLIC_PATHS` con `/webhooks/wompi`, `/actuator/health`, `/actuator/info`
  - [x] Flujo: ruta pública → pass-through; sin Bearer → 401; token inválido → 401;
    token válido → extrae uid + plan, propaga headers, elimina `Authorization` original
  - [x] Usa `Schedulers.boundedElastic()` para la llamada bloqueante a Firebase
  - [x] Si claim `plan` ausente → omite header `X-User-Plan` (no propaga vacío)
- [x] Crear `GlobalErrorHandler.java` en `exception/`
  - [x] Implementa `WebExceptionHandler`
  - [x] Da formato JSON `{"code":"AUTH_REQUIRED","message":"..."}` al 401
  - [x] Orden después del `DefaultErrorWebExceptionHandler` de Spring

---

## Bloque 4 — Tests

- [x] Crear `src/test/resources/firebase-noop.json`
  (JSON de service account con campos ficticios — sin secretos reales)
- [x] Crear `src/test/resources/application-test.yml`
  - [x] `SPRING_PROFILES_ACTIVE: test`
  - [x] URLs downstream apuntando a puertos aleatorios de MockWebServer
  - [x] `FIREBASE_PROJECT_ID: test-project`
  - [x] `GOOGLE_APPLICATION_CREDENTIALS: classpath:firebase-noop.json`
- [x] Crear `TestFirebaseConfig.java` (clase `@TestConfiguration`)
  - [x] `@Bean @Primary FirebaseAuth firebaseAuth()` → `mock(FirebaseAuth.class)`
  - [x] Permite que los tests inyecten el mock sin inicializar Firebase real
- [x] Crear `FirebaseAuthGlobalFilterTest.java`
  - [x] `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `WebTestClient`
  - [x] MockWebServer para downstream (verifica que la solicitud llega o no)
  - [x] Caso 1: token válido con claim `plan=PREMIUM` → 200; headers `X-User-Id` y
    `X-User-Plan: PREMIUM` presentes en la solicitud downstream
  - [x] Caso 2: token válido sin claim `plan` → 200; `X-User-Plan` ausente en downstream
  - [x] Caso 3: sin header `Authorization` → 401; body `{"code":"AUTH_REQUIRED",...}`
  - [x] Caso 4: token malformado → 401
  - [x] Caso 5: `POST /webhooks/wompi` sin token → llega al downstream; sin `X-User-Id`
  - [x] Caso 6: `GET /actuator/health` sin token → 200
- [x] Crear `GatewayStartupTest.java`
  - [x] Verifica que sin `FIREBASE_PROJECT_ID` el contexto falla al arrancar
    (`assertThatThrownBy` sobre `SpringApplication.run`)
- [x] Crear `GatewayArchTest.java` (ArchUnit)
  - [x] Ninguna clase del gateway importa `jakarta.persistence.*`
  - [x] Ninguna clase del gateway importa `org.springframework.data.jpa.*`
  - [x] Paquete `filter` no importa paquete `config` (sólo recibe beans inyectados)

---

## Bloque 5 — Docker

- [x] Crear `Dockerfile` multi-stage
  - [x] Etapa `builder`: `maven:3.9-eclipse-temurin-21-alpine`, `mvn package -DskipTests`
  - [x] Etapa `runtime`: `eclipse-temurin:21-jre-alpine`, copia el jar, `ENTRYPOINT`
- [x] Crear `docker-compose.yml`
  - [x] Servicio `verify`: usa etapa `builder`, ejecuta `mvn verify`,
    monta volumen `maven-cache:/root/.m2`, incluye variables de entorno mínimas de test
  - [x] Volumen `maven-cache` declarado

---

## Bloque 6 — Verificación final

- [x] Ejecutar `docker compose run --rm verify` → debe terminar en verde (BUILD SUCCESS)
- [x] Verificar que `GET /actuator/health` responde 200 (arrancar localmente con `.env`)
- [x] Verificar que no hay secretos reales en ningún archivo del diff
  (`git diff --name-only` + revisar `firebase-noop.json`)
- [x] Actualizar `README.md` con comandos reales:
  - [x] Cómo copiar `.env.example` a `.env` y rellenarlo
  - [x] `docker compose run --rm verify` para tests
  - [x] Cómo arrancar en local
  - [x] URL del health check
- [x] Completar bitácora IA el mismo día

---

## Bloque 7 — Pull Request

- [x] Commit final: `feat(gateway): base técnica CM-104 [IA-ASISTIDO]`
- [x] Verificar título de PR: `CM-104 | feat(gateway): base técnica [IA-ASISTIDO]`
- [x] PR apunta a `develop`, squash merge
