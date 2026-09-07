# Tasks — CM-104: Base técnica del API Gateway

- **HU asociada:** CM-104
- **Fecha:** 07/09/2026
- **Estado:** pendiente de ejecución
- **Flujo SDD:** Fase 4 — Checkboxes (plan.md aprobado)
- **Rama:** `CM-104-base-tecnica-gateway` → `develop`

Marcar cada tarea al completarla. No pasar a la siguiente sección hasta que
`docker compose run --rm verify` pase en verde al final de la sección.

---

## Bloque 0 — Rama Git

- [ ] Crear rama `CM-104-base-tecnica-gateway` desde `develop`
- [ ] Verificar con `git status` que no hay cambios sin commitear antes de arrancar

---

## Bloque 1 — Esqueleto del proyecto Maven

- [ ] Crear `pom.xml` con:
  - [ ] Parent `spring-boot-starter-parent:4.1.1`
  - [ ] `groupId: co.edu.unicauca.cameia`, `artifactId: cameia-gateway`
  - [ ] BOM `spring-cloud-dependencies:2025.0.x` (verificar versión exacta en spring.io)
  - [ ] Dependencia `spring-cloud-starter-gateway` (sin versión — la gestiona el BOM)
  - [ ] Dependencia `firebase-admin:9.4.x` (verificar versión exacta en Maven Central)
  - [ ] Dependencia `spring-boot-starter-actuator`
  - [ ] Dependencia `spring-boot-starter-test` scope test
  - [ ] Dependencia `archunit-junit5:1.5.0` scope test
  - [ ] Plugin `spring-boot-maven-plugin`
  - [ ] Propiedad `<java.version>21</java.version>`
- [ ] Crear clase `GatewayApplication.java` en `co.edu.unicauca.cameia.gateway`
  con `@SpringBootApplication` y método `main`

---

## Bloque 2 — Configuración de entorno

- [ ] Crear `src/main/resources/application.yml` con:
  - [ ] Rutas de Spring Cloud Gateway (6 rutas según tabla de spec.md REQ-04)
  - [ ] Timeout global (`${GATEWAY_TIMEOUT_MS:30000}`)
  - [ ] CORS global (REQ-NF-01)
  - [ ] Actuator (`health,info` expuestos sin auth)
  - [ ] Logging raíz y paquete gateway
- [ ] Crear `src/main/resources/application-local.yml` (overrides vacíos, placeholder)
- [ ] Crear `.env.example` con todas las variables del §4 del spec + §6 del plan,
  con comentarios descriptivos por sección
- [ ] Verificar que `.env` está en `.gitignore` (añadir si no está)
- [ ] Verificar que `*.json` en `src/test/resources/` NO está en `.gitignore`
  (el noop de Firebase sí se commitea)

---

## Bloque 3 — Componentes Java

- [ ] Crear `GatewayProperties.java` en `config/`
  - [ ] `@ConfigurationProperties("gateway")`
  - [ ] `@Validated`
  - [ ] Campo `corsAllowedOrigin` con `@NotBlank`
  - [ ] Campo `timeoutMs` con `@NotNull`
- [ ] Crear `FirebaseConfig.java` en `config/`
  - [ ] Inicializa `FirebaseApp` en `@PostConstruct` usando `GOOGLE_APPLICATION_CREDENTIALS`
    y `FIREBASE_PROJECT_ID`
  - [ ] `@Bean FirebaseAuth firebaseAuth()` → `FirebaseAuth.getInstance()`
  - [ ] Falla de arranque si credenciales son inválidas (excepción no capturada)
- [ ] Crear `FirebaseAuthGlobalFilter.java` en `filter/`
  - [ ] Implementa `GlobalFilter`, `Ordered` (prioridad `HIGHEST_PRECEDENCE`)
  - [ ] Recibe `FirebaseAuth` por constructor (inyección)
  - [ ] Constante `PLAN_CLAIM = "plan"`
  - [ ] Lista `PUBLIC_PATHS` con `/webhooks/wompi`, `/actuator/health`, `/actuator/info`
  - [ ] Flujo: ruta pública → pass-through; sin Bearer → 401; token inválido → 401;
    token válido → extrae uid + plan, propaga headers, elimina `Authorization` original
  - [ ] Usa `Schedulers.boundedElastic()` para la llamada bloqueante a Firebase
  - [ ] Si claim `plan` ausente → omite header `X-User-Plan` (no propaga vacío)
- [ ] Crear `GlobalErrorHandler.java` en `exception/`
  - [ ] Implementa `WebExceptionHandler`
  - [ ] Da formato JSON `{"code":"AUTH_REQUIRED","message":"..."}` al 401
  - [ ] Orden después del `DefaultErrorWebExceptionHandler` de Spring

---

## Bloque 4 — Tests

- [ ] Crear `src/test/resources/firebase-noop.json`
  (JSON de service account con campos ficticios — sin secretos reales)
- [ ] Crear `src/test/resources/application-test.yml`
  - [ ] `SPRING_PROFILES_ACTIVE: test`
  - [ ] URLs downstream apuntando a puertos aleatorios de MockWebServer
  - [ ] `FIREBASE_PROJECT_ID: test-project`
  - [ ] `GOOGLE_APPLICATION_CREDENTIALS: classpath:firebase-noop.json`
- [ ] Crear `TestFirebaseConfig.java` (clase `@TestConfiguration`)
  - [ ] `@Bean @Primary FirebaseAuth firebaseAuth()` → `mock(FirebaseAuth.class)`
  - [ ] Permite que los tests inyecten el mock sin inicializar Firebase real
- [ ] Crear `FirebaseAuthGlobalFilterTest.java`
  - [ ] `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `WebTestClient`
  - [ ] MockWebServer para downstream (verifica que la solicitud llega o no)
  - [ ] Caso 1: token válido con claim `plan=PREMIUM` → 200; headers `X-User-Id` y
    `X-User-Plan: PREMIUM` presentes en la solicitud downstream
  - [ ] Caso 2: token válido sin claim `plan` → 200; `X-User-Plan` ausente en downstream
  - [ ] Caso 3: sin header `Authorization` → 401; body `{"code":"AUTH_REQUIRED",...}`
  - [ ] Caso 4: token malformado → 401
  - [ ] Caso 5: `POST /webhooks/wompi` sin token → llega al downstream; sin `X-User-Id`
  - [ ] Caso 6: `GET /actuator/health` sin token → 200
- [ ] Crear `GatewayStartupTest.java`
  - [ ] Verifica que sin `FIREBASE_PROJECT_ID` el contexto falla al arrancar
    (`assertThatThrownBy` sobre `SpringApplication.run`)
- [ ] Crear `GatewayArchTest.java` (ArchUnit)
  - [ ] Ninguna clase del gateway importa `jakarta.persistence.*`
  - [ ] Ninguna clase del gateway importa `org.springframework.data.jpa.*`
  - [ ] Paquete `filter` no importa paquete `config` (sólo recibe beans inyectados)

---

## Bloque 5 — Docker

- [ ] Crear `Dockerfile` multi-stage
  - [ ] Etapa `builder`: `maven:3.9-eclipse-temurin-21-alpine`, `mvn package -DskipTests`
  - [ ] Etapa `runtime`: `eclipse-temurin:21-jre-alpine`, copia el jar, `ENTRYPOINT`
- [ ] Crear `docker-compose.yml`
  - [ ] Servicio `verify`: usa etapa `builder`, ejecuta `mvn verify`,
    monta volumen `maven-cache:/root/.m2`, incluye variables de entorno mínimas de test
  - [ ] Volumen `maven-cache` declarado

---

## Bloque 6 — Verificación final

- [ ] Ejecutar `docker compose run --rm verify` → debe terminar en verde (BUILD SUCCESS)
- [ ] Verificar que `GET /actuator/health` responde 200 (arrancar localmente con `.env`)
- [ ] Verificar que no hay secretos reales en ningún archivo del diff
  (`git diff --name-only` + revisar `firebase-noop.json`)
- [ ] Actualizar `README.md` con comandos reales:
  - [ ] Cómo copiar `.env.example` a `.env` y rellenarlo
  - [ ] `docker compose run --rm verify` para tests
  - [ ] Cómo arrancar en local
  - [ ] URL del health check
- [ ] Completar bitácora IA el mismo día

---

## Bloque 7 — Pull Request

- [ ] Commit final: `feat(gateway): base técnica CM-104 [IA-ASISTIDO]`
- [ ] Verificar título de PR: `CM-104 | feat(gateway): base técnica [IA-ASISTIDO]`
- [ ] PR apunta a `develop`, squash merge
