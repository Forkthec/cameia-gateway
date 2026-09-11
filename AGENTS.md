# AGENTS.md — reglas de código de `cameia-gateway`

> Reglas operativas de **este** repositorio. Deriva de
> `03092026_v1_reglas-codigo-backend-cameia.md` v1.3, pero contiene **solo lo que aplica al
> API Gateway**: quien trabaje aquí no necesita cargar las reglas de los otros cinco repositorios.
>
> Si algo de aquí contradice al documento de reglas del equipo, manda el documento del equipo
> y hay que avisarlo.

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
5. **La bitácora de IA se llena el mismo día.** Ver §7.

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

- **Autenticar** cada petición con Firebase Admin SDK (extrae UID y plan del token JWT)
- **Propagar** `X-User-Id` y `X-User-Plan` a los microservicios downstream
- **Eliminar** el header `Authorization` antes de reenviar (los micros no leen Firebase)
- **Enrutar** `/api/v1/profiles/**` → cameia-perfil, etc.
- **CORS** global para el frontend (cameia-web)

**No** es responsable de: lógica de negocio, persistencia, cálculo de cuota ni validación de datos de dominio.

- Stack: Java 21, Spring Boot 4.0.8, **Spring Cloud Gateway 5.0.3** (WebFlux reactivo), Firebase Admin SDK.
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
├── filter          GlobalFilter — lógica de auth y propagación de headers
└── exception       GlobalErrorHandler — formato JSON de errores HTTP
```

**Sin paquete `domain`, `application`, `service` ni `persistence`.** El gateway no tiene modelo de dominio ni base de datos. Toda la lógica de enrutamiento vive en `application.yml`.

### Reglas de dependencia

```text
filter ──usa──> config (recibe beans por inyección de constructor)
exception      independiente
```

- `filter` **no importa** `exception` directamente.
- Ningún paquete importa `jakarta.persistence` ni `org.springframework.data.jpa`. ArchUnit lo verifica.

---

## 4. Componentes existentes

| Clase | Paquete | Qué hace |
|---|---|---|
| `GatewayApplication` | raíz | `@SpringBootApplication`, punto de entrada |
| `FirebaseConfig` | `config` | Inicializa `FirebaseApp` en `@PostConstruct`; falla en arranque si las credenciales son inválidas (fail-fast) |
| `GatewayProperties` | `config` | `@ConfigurationProperties("gateway")` — `corsAllowedOrigin`, `timeoutMs` |
| `FirebaseAuthGlobalFilter` | `filter` | Valida Bearer token, extrae UID y plan, propaga headers, elimina `Authorization` |
| `GlobalErrorHandler` | `exception` | Formatea 401 como `{"code":"AUTH_REQUIRED","message":"..."}` |

**No se crea ninguna clase que no esté en esta tabla o en el spec aprobado de la HU.**

---

## 5. Rutas HTTP — declaradas en `application.yml`, no en código

| Ruta | Downstream | Estado |
|---|---|---|
| `/api/v1/profiles/**` | `${CAMEIA_PERFIL_URL}` | Activo (CM-113) |
| `/api/v1/accounts/**` | `${CAMEIA_CUENTAS_URL}` | Configurado, sin servicio aún |
| `/api/v1/interviews/**` | `${CAMEIA_ENTREVISTA_URL}` | Configurado, sin servicio aún |
| `/webhooks/wompi` | — | Público (sin auth) |
| `/actuator/health` | local | Público |
| `/actuator/info` | local | Público |

Añadir una ruta nueva **no requiere código Java**, solo editar `application.yml` y abrir PR.

---

## 6. Filtro de autenticación — reglas de implementación

El `FirebaseAuthGlobalFilter` es el componente más crítico. Reglas que no se tocan sin spec:

1. **Rutas públicas** (`PUBLIC_PATHS`): `POST /webhooks/wompi`, `/actuator/health`, `/actuator/info` — pasan sin token.
2. **Sin Bearer** → 401 con código `AUTH_REQUIRED`.
3. **Token inválido** → 401.
4. **Token válido** → extrae UID (`uid`), extrae claim `plan` si existe, propaga:
   - `X-User-Id: <uid>`
   - `X-User-Plan: <plan>` (solo si el claim existe; nunca se propaga vacío)
   - **Elimina `Authorization`** del request antes de reenviar al downstream.
5. La llamada a `FirebaseAuth.verifyIdToken()` es bloqueante → se ejecuta en `Schedulers.boundedElastic()`.

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
4. Header `Authorization` propagado al downstream (siempre se elimina).
5. `X-User-Plan` propagado vacío o nulo.
6. Secreto real (`private_key`, token, contraseña) en cualquier archivo versionado.

---

## 8. Pruebas

| Tipo | Herramienta | Regla |
|---|---|---|
| Integración del filtro | `@SpringBootTest(RANDOM_PORT)` + `WebTestClient` + `MockWebServer` | Cada caso del filtro (token válido, sin token, token inválido, ruta pública) tiene prueba |
| Arranque fail-fast | `SpringApplication.run` en `assertThatThrownBy` | Verifica que sin `FIREBASE_PROJECT_ID` el contexto no arranca |
| Arquitectura | ArchUnit | Sin JPA, sin dependencias circulares entre paquetes |

- Firebase real se reemplaza en tests con `@TestConfiguration` que produce un `mock(FirebaseAuth.class)`.
- El archivo `src/test/resources/firebase-noop.json` se versiona (sin secretos reales).
- `SPRING_PROFILES_ACTIVE: test` desactiva `FirebaseConfig` (`gateway.firebase.enabled=false`).

Cómo ejecutar sin instalar Java ni Maven:

```bash
docker compose run --rm verify
```

Resultado esperado: `BUILD SUCCESS` con 10 pruebas en verde.

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
| `CAMEIA_PERFIL_URL` | fijo en compose: `http://cameia-perfil-app:8082` |
| `CAMEIA_CUENTAS_URL` | `http://cameia-cuentas-app:8081` |
| `CAMEIA_ENTREVISTA_URL` | `http://cameia-entrevista-app:8083` |

**`FIREBASE_KEY_PATH` vacío** → compose usa `/dev/null` como fallback → el gateway **no arranca** (fail-fast por diseño). El compañero de frontend necesita el JSON de Firebase de la responsable del gateway.

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
- [ ] `Authorization` no se propaga al downstream
- [ ] `X-User-Plan` no se propaga vacío
- [ ] Ninguna importación de `jakarta.persistence` ni JPA
- [ ] `.env.example` actualizado si cambiaron variables
- [ ] Bitácora del §10 con la fila correspondiente
- [ ] Revisión pedida a alguien distinto de la autora
