# Tareas — CM-260 (un bloque, un PR)

Estado: ejecutado el 6-oct-2026 (104 pruebas en verde con `./mvnw.cmd clean verify`). Comando base: `docker compose run --rm verify` o `./mvnw.cmd test`
(según el `AGENTS.md`). Marcar `[x]` solo con la salida real de las pruebas. Mensaje de commit:
`CM-260 | fix(gateway): declarar charset UTF-8 en el 401 del filtro de autenticación [IA-ASISTIDO]`.

Reglas para todas las tarjetas: no agregar dependencias; no cambiar contratos públicos; comentarios y Javadoc en español, **sin**
`CM-NNN` ni rutas a otros archivos; no registrar tokens ni correos. **Detenerse y reportar** si la tarjeta contradice el código,
falta un dato, una prueba existente se rompe sin causa clara o hace falta algo no listado.
Definición de terminado de cada una: pruebas nuevas en verde, suite completa en verde, `GatewayArchTest` en verde, diff ≤ la estimación.

## [x] T1 · Medir el estado real (REQ-CS-01, pregunta 3) — ≤ 10 min, sin commit

- **Objetivo:** confirmar el defecto con salida real y medir actuator.
- **Archivo:** crear `src/test/java/tech/cameia/gateway/filter/ContentTypeProbeTest.java` **temporal** (se borra al terminar la tarjeta; no se compromete). Copiar la anotación de clase, los campos `webTestClient`, `mockDownstream` y `firebaseAuth` y el `@BeforeEach` de `FirebaseAuthGlobalFilterTest`.
- **Código de referencia:**
  ```java
  @Test void probe() {
      for (String path : List.of("/api/v1/profiles/me", "/actuator/health", "/actuator/info", "/ruta-que-no-existe")) {
          var r = webTestClient.get().uri(path).exchange().returnResult(String.class);
          System.out.println("PROBE " + path + " -> " + r.getStatus() + " " + r.getResponseHeaders().getContentType());
      }
  }
  ```
- **Esperado:** `/api/v1/profiles/me` → `401 application/json` (el defecto). Las otras rutas: anotar el valor tal cual.
- **Salida:** copiar las cuatro líneas `PROBE` al informe del bloque. Si actuator no declara charset → **detenerse y preguntar (pregunta 3)** antes de T2.
- **Trampa:** `/actuator/*` puede devolver 401 si el filtro lo protege; anotarlo igual (también es una respuesta del filtro).

## [x] T2 · Constante y corrección del filtro (REQ-CS-01 a 04, 07) — ≤ 10 min, ≈ 12 líneas

- **Archivo:** `src/main/java/tech/cameia/gateway/filter/FirebaseAuthGlobalFilter.java`. No se tocan los demás.
- **Cambio 1** (junto a las otras constantes, al inicio de la clase):
  ```java
  /**
   * Tipo de contenido de las respuestas JSON que escribe este filtro. {@code MediaType.APPLICATION_JSON}
   * no declara {@code charset}; el estándar de seguridad exige declararlo en toda respuesta con cuerpo.
   */
  private static final MediaType APPLICATION_JSON_UTF8 =
          new MediaType("application", "json", StandardCharsets.UTF_8);
  ```
- **Cambio 2** (línea 460): `setContentType(MediaType.APPLICATION_JSON)` → `setContentType(APPLICATION_JSON_UTF8)`.
- **Cambio 3:** en el Javadoc de `writeUnauthorized`, añadir al primer párrafo: «El cuerpo se declara como `application/json` con charset UTF-8.»
- **Reutiliza:** el import de `MediaType` y `StandardCharsets` ya existen (líneas 17 y 25).
- **Trampa:** no importar nada de `tech.cameia.gateway.exception` (regla de dependencias); no extraer a otra clase.
- **Verificación:** `./mvnw.cmd test -Dtest=FirebaseAuthGlobalFilterTest` en verde (todavía sin aserciones nuevas).

## [x] T3 · Aserciones del 401 del filtro (REQ-CS-01 a 04) — ≤ 15 min, ≈ 20 líneas

- **Archivo:** `src/test/java/tech/cameia/gateway/filter/FirebaseAuthGlobalFilterTest.java`.
- **Cambio:** en `missingAuthorization_returns401` (línea 257), `invalidToken_returns401` (291), `emptyBearerToken_returns401WithoutReachingDownstream` (311), `nonBearerScheme_returns401` (328) y `firebaseIllegalArgument_returns401` (345), insertar justo después de `.expectStatus().isUnauthorized()`:
  ```java
  .expectHeader().valueEquals("Content-Type", "application/json;charset=UTF-8")
  ```
  En `invalidToken_returns401` añadir además `.jsonPath("$.message").isEqualTo("Token de acceso inválido")` (comprueba la tilde bajo UTF-8). Renombrar los cinco métodos agregando el sufijo `AndUtf8Charset` (p. ej. `missingAuthorization_returns401AndUtf8Charset`); `AGENTS.md` pide nombres `escenario_condicion_resultado`.
- **Pruebas esperadas:** las cinco en verde; antes de T2 habrían fallado (si se ejecuta T3 sin T2, deben fallar con `application/json` ≠ `application/json;charset=UTF-8`: comprobarlo una vez con `git stash` del cambio de T2).
- **Debe seguir en verde:** el resto de `FirebaseAuthGlobalFilterTest`, en especial `unauthorizedResponse_includesRequestId` y `downstreamProblemJson_isReturnedUnchanged`.

## [x] T4 · Estados del manejador global (REQ-CS-05) — ≤ 15 min, ≈ 25 líneas

- **Archivo:** `src/test/java/tech/cameia/gateway/exception/GlobalErrorHandlerLoggingTest.java`, justo después de `errorResponse_isUnchanged` (línea 111).
- **Reutiliza:** el método auxiliar `handle(Throwable)` (línea 138) y `CLIENT_TEXT`.
- **Código de referencia:**
  ```java
  /** Cada estado del catálogo y el estado por defecto declaran el charset UTF-8. */
  @ParameterizedTest
  @CsvSource({"401,AUTH_REQUIRED", "404,NOT_FOUND", "502,BAD_GATEWAY",
              "503,SERVICE_UNAVAILABLE", "504,GATEWAY_TIMEOUT"})
  void catalogStatus_declaresUtf8Charset(int status, String code) {
      MockServerWebExchange exchange =
              handle(new ResponseStatusException(HttpStatus.valueOf(status), CLIENT_TEXT));
      assertThat(exchange.getResponse().getHeaders().getContentType().toString())
              .isEqualTo("application/json;charset=UTF-8");
      assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"code\":\"" + code + "\"");
  }

  @Test
  void unexpectedFailure_declaresUtf8Charset() {
      MockServerWebExchange exchange = handle(new IllegalStateException("fallo interno simulado"));
      assertThat(exchange.getResponse().getHeaders().getContentType().toString())
              .isEqualTo("application/json;charset=UTF-8");
      assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"code\":\"INTERNAL_ERROR\"");
  }
  ```
  Imports nuevos: `org.junit.jupiter.params.ParameterizedTest` y `org.junit.jupiter.params.provider.CsvSource` (JUnit Params viene con `junit-jupiter` de `spring-boot-starter-test`; si no resuelve, **detenerse**, no agregar dependencias).
- **Trampa:** `handle(...)` hace `block()`; el cuerpo se lee con `getBodyAsString().block()` como en la línea 108.

## [x] T5 · Regla de arquitectura (REQ-CS-06) — ≤ 10 min, ≈ 20 líneas

- **Archivo nuevo:** `src/test/java/tech/cameia/gateway/arch/ContentTypeArchTest.java`. No se toca `GatewayArchTest`: este analiza también las clases de prueba, y varias pruebas usan `MediaType.APPLICATION_JSON` (p. ej. `FirebaseAuthGlobalFilterTest` líneas 526 y 575), así que la regla fallaría por ellas.
- **Código de referencia:**
  ```java
  /** Impide declarar respuestas JSON sin charset en el código de producción (ASVS 4.1.1). */
  @AnalyzeClasses(packages = "tech.cameia.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
  class ContentTypeArchTest {

      @ArchTest
      static final ArchRule productionCodeDoesNotUseBareApplicationJson =
              noClasses()
                      .should().accessField(MediaType.class, "APPLICATION_JSON")
                      .because("Content-Type JSON sin charset incumple ASVS 4.1.1: usar un MediaType con charset UTF-8");
  }
  ```
  Imports: `com.tngtech.archunit.core.importer.ImportOption`, `org.springframework.http.MediaType`, más los de `GatewayArchTest`.
- **Comprobación obligatoria:** con el cambio de T2 temporalmente revertido, la regla debe **fallar** señalando `FirebaseAuthGlobalFilter`; con T2 aplicado, pasa. Anotar ambas salidas. Si no falla con T2 revertido, detenerse: la regla no sirve y se sustituye por otra forma de guarda (decisión de Paula).

## [x] T2b · Charset de actuator (REQ-CS-08, D4) — decidido por Paula tras T1

- **Medido en T1:** `/actuator/health` y `/actuator/info` → `200 application/vnd.spring-boot.actuator.v3+json` (sin charset); `/api/v1/profiles/me` → `401 application/json`; `/ruta-que-no-existe` → `404 application/json;charset=UTF-8`.
- **Archivos:** `filter/ActuatorCharsetWebFilter.java` (nuevo), `ActuatorCharsetWebFilterTest.java` (nuevo, 6 pruebas) y `actuator_declaresUtf8Charset` en `FirebaseAuthGlobalFilterTest` (health e info).

## [x] T6 · Cierre del bloque — ≤ 10 min

- `docker compose run --rm verify` (o `./mvnw.cmd clean verify`): suite completa en verde; informar número de pruebas.
- Cobertura JaCoCo (`target/site/jacoco/`) de `FirebaseAuthGlobalFilter` y de `GlobalErrorHandler`: reportar líneas y ramas reales y cada una sin cubrir con su razón; no debe bajar la global.
- Revisión (`backend-estandar` §6): `/simplify`, `/code-review high`; `/security-review` no es obligatoria (no toca autenticación ni datos), pero se corre la lista de autochequeo.
- Commit local; el push y el PR hacia `develop` solo en la Fase 2, con el título `CM-260 | fix(gateway): declarar charset UTF-8 en el 401 del filtro de autenticación [IA-ASISTIDO]`, la plantilla completa y la evidencia de las salidas de T1, T3 y T5.
- Tras abrir el PR: tarjeta de Jira `En revisión`.

## Resultado del bloque

- Suite: 104 pruebas, 0 fallos. T3 y T5 se comprobaron fallando con la corrección revertida y en verde con ella.
- Cobertura del código modificado: `ActuatorCharsetWebFilter` 12/12 líneas, 8/8 ramas; `FirebaseAuthGlobalFilter` 87/87 líneas, 41/44 ramas (las 3 ramas sin cubrir ya existían y no son de este cambio); `GlobalErrorHandler` sin cambios de producción (46/49 líneas, 23/30 ramas). Global: 89,9 % líneas, 85,1 % ramas.
