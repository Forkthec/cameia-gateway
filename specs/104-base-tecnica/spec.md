# Spec — CM-104: Base técnica del API Gateway

- **HU asociada:** CM-104 — Crear la estructura técnica y dependencias del Gateway
- **Sprint:** 1
- **Fecha:** 07/09/2026
- **Estado:** propuesta pendiente de aprobación
- **Flujo SDD:** Fase 1 — Requisitos EARS (no hay código hasta que esto esté aprobado)
- **Fuentes:** C2 `27082026_01_Contenedores(C2).txt`, C3 `29082026_1_Componentes(C3).txt`,
  glosario v3, familias de endpoints Sprint 1, onboarding `onboarding-api-gateway.md`,
  `cameia-perfil/AGENTS.md` (referencia de estándares), `README.md` de cameia-gateway.

---

## 1. Contexto

El API Gateway es el único punto de entrada HTTP del sistema CAMEIA. Recibe todas las
solicitudes de `cameia-web` (React 19), verifica la identidad mediante Firebase Admin SDK,
propaga los custom claims a los microservicios downstream y enruta cada solicitud al
servicio propietario. No tiene base de datos, no contiene lógica de negocio y no replica
reglas de ningún contexto.

**Stack aprobado:** Java 21 / Spring Boot 4.1.1 / Spring Cloud Gateway (WebFlux).

---

## 2. Requisitos funcionales — notación EARS

### REQ-01 — Enrutamiento autenticado (rutas protegidas)

```
Cuando el cliente envía una solicitud HTTP con cabecera
`Authorization: Bearer <ID_TOKEN>` a una ruta protegida,
el sistema verifica el token con Firebase Admin SDK,
y si el token es válido extrae los custom claims
(uid, plan = FREE | PREMIUM) y reenvía la solicitud
al microservicio downstream añadiendo los headers
`X-User-Id: <uid>` y `X-User-Plan: <FREE|PREMIUM>`.
```

### REQ-02 — Rechazo de token inválido o ausente

```
Si el cliente envía una solicitud a una ruta protegida
sin cabecera `Authorization` o con un token inválido,
el sistema responde 401 Unauthorized con cuerpo
`{"code":"AUTH_REQUIRED","message":"Token de acceso requerido o inválido"}`
sin reenviar la solicitud al downstream.
```

### REQ-03 — Enrutamiento público (sin autenticación Firebase)

```
Cuando la solicitud es `POST /webhooks/wompi`,
el sistema la enruta directamente a cameia-cuentas
sin verificar el token de Firebase
y sin añadir headers `X-User-Id` ni `X-User-Plan`.
```

> **Nota de alcance:** `POST /webhooks/wompi` corresponde funcionalmente a HU-8.*
> (Sprint 3). Este requisito se incluye en la base técnica para configurar la excepción
> pública correctamente desde el inicio y no requerir cambios de gateway en Sprint 3.
> El endpoint no será operativo hasta que cameia-cuentas implemente el Controller
> Webhook Wompi (C3 de Cuentas).

### REQ-04 — Tabla de enrutamiento Sprint 1

```
El sistema enruta las siguientes familias de rutas
hacia el microservicio propietario configurado por variable de entorno:
```

| Patrón de ruta | Microservicio destino | Auth | Sprint activo |
|---|---|---|---|
| `/api/v1/users/**` | cameia-cuentas | Firebase | Sprint 1 |
| `/api/v1/profiles/**` | cameia-perfil | Firebase | Sprint 1 |
| `/api/v1/interviews/**` | cameia-entrevista | Firebase | Sprint 1 |
| `POST /webhooks/wompi` | cameia-cuentas | Ninguna | Sprint 3 |
| `/api/v1/voice-service/**` | cameia-voz | Firebase | Sprint 2 |
| `/api/v1/audit/**` | cameia-auditoria | Firebase | Sprint 2/3 |

> El gateway configura todas las rutas desde Sprint 1. Las rutas cuyo downstream aún
> no existe simplemente devuelven 503 hasta que el servicio esté disponible; no
> requieren cambios en el gateway en sprints posteriores.

### REQ-05 — Health check público

```
El sistema expone `GET /actuator/health` sin autenticación de Firebase
y responde 200 con `{"status":"UP"}` cuando el servicio está operativo.
```

### REQ-06 — Arranque forzado por configuración

```
Si cualquiera de las variables de entorno obligatorias
(lista completa en §4) está ausente al arrancar,
el sistema falla el arranque con mensaje descriptivo
en lugar de iniciar con configuración parcialmente rota.
```

### REQ-07 — No propagación de información sensible

```
El sistema nunca reenvía la cabecera `Authorization` original
(que contiene el ID Token de Firebase) a los microservicios downstream.
Solo se propagan `X-User-Id` y `X-User-Plan`.
```

### REQ-08 — Sin lógica de negocio

```
Mientras el sistema actúa como gateway,
no modifica el cuerpo de ninguna solicitud ni respuesta,
no consulta ninguna base de datos propia,
y no aplica reglas de negocio de Cuentas, Perfil, Entrevista,
Auditoría ni Voz.
```

---

## 3. Requisitos no funcionales

### REQ-NF-01 — CORS

```
El sistema aplica CORS permitiendo el origen configurado por variable de entorno
(`GATEWAY_CORS_ALLOWED_ORIGIN`) en todas las rutas,
con métodos `GET, POST, PUT, PATCH, DELETE, OPTIONS`
y cabeceras `Authorization, Content-Type, X-User-Id, X-User-Plan`.
```

### REQ-NF-02 — Timeout upstream

```
El sistema aplica un timeout de respuesta de 30 segundos
a cada solicitud reenviada al downstream.
Si el downstream no responde en ese tiempo,
el sistema responde 504 Gateway Timeout.
```

### REQ-NF-03 — Tests corren en Docker

```
Cuando se ejecuta `docker compose run --rm verify`,
el sistema compila, ejecuta la suite de pruebas y
reporta el resultado sin requerir JDK ni Maven instalado localmente.
```

### REQ-NF-04 — Sin secretos en Git

```
Ninguna clave de Firebase, credencial de servicio ni URL de downstream
se comete en el repositorio.
Todas las variables se documentan en `.env.example`
con nombre, propósito y si son obligatorias u opcionales.
```

---

## 4. Variables de entorno

### Obligatorias (el servicio no arranca sin ellas)

| Variable | Propósito |
|---|---|
| `SPRING_APPLICATION_NAME` | Nombre del servicio (`cameia-gateway`) |
| `SERVER_PORT` | Puerto donde escucha el gateway |
| `FIREBASE_PROJECT_ID` | ID del proyecto Firebase para validar tokens |
| `GOOGLE_APPLICATION_CREDENTIALS` | Ruta al archivo de credenciales de la service account de Firebase |
| `CAMEIA_CUENTAS_URL` | URL base de cameia-cuentas (p.ej. `http://cameia-cuentas:8080`) |
| `CAMEIA_PERFIL_URL` | URL base de cameia-perfil |
| `CAMEIA_ENTREVISTA_URL` | URL base de cameia-entrevista |

### Opcionales con default sensato

| Variable | Default | Propósito |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | Perfil de Spring activo |
| `GATEWAY_CORS_ALLOWED_ORIGIN` | `http://localhost:5173` | Origen permitido por CORS (React dev) |
| `GATEWAY_TIMEOUT_MS` | `30000` | Timeout de respuesta al downstream (ms) |
| `LOG_LEVEL` | `INFO` | Nivel de log raíz |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health,info` | Endpoints de Actuator expuestos |

### Para sprints posteriores (declarar en `.env.example`, no obligatorias en Sprint 1)

| Variable | Propósito |
|---|---|
| `CAMEIA_VOZ_URL` | URL base de cameia-voz (Sprint 2) |
| `CAMEIA_AUDITORIA_URL` | URL base de cameia-auditoria (Sprint 2/3) |

---

## 5. Lo que este spec NO decide (abierto)

| ID | Tema | Impacto | Quién decide |
|---|---|---|---|
| `GW-TBD-01` | Versión exacta de `spring-cloud-dependencies` compatible con Spring Boot 4.1.1 | `pom.xml` | Arquitectura — se verifica en plan.md antes de escribir el pom |
| `GW-TBD-02` | Versión de Firebase Admin SDK | `pom.xml` | Se toma la última estable disponible en Maven Central al momento de implementar |
| `GW-TBD-03` | Puerto final del gateway | `SERVER_PORT` | Equipo DevOps (como en perfil, el puerto es provisional hasta asignación) |
| `GW-TBD-04` | Claim exacto del plan en el ID Token de Firebase | `X-User-Plan` | Cuentas — el custom claim lo escribe cameia-cuentas al activar suscripción |
| `GW-TBD-05` | Nombre del claim en Firebase (`plan`, `subscription_plan`, otro) | Header `X-User-Plan` | Cuentas + arquitectura |

**Resolución GW-TBD-04 y GW-TBD-05 (decidido para evitar problemas de integración):**
- Nombre del custom claim en Firebase: `plan` (string `"FREE"` | `"PREMIUM"`).
  El equipo de cameia-cuentas debe escribir exactamente ese nombre al activar la suscripción.
- Si el claim `plan` no existe en el token (usuario sin suscripción activa o cameia-cuentas aún no lo escribe):
  el gateway **omite el header `X-User-Plan`** completamente — no propaga `""` ni `"FREE"`.
  Los microservicios downstream tratan la ausencia del header como plan desconocido/sin suscripción,
  y aplican sus propias reglas de negocio. Esto evita que el gateway invente un plan que no tiene.

Los ítems `GW-TBD-01`, `GW-TBD-02` y `GW-TBD-03` se resuelven en plan.md (versiones de dependencias y puerto).

---

## 6. Criterio de terminado (DoD)

- [ ] `docker compose run --rm verify` en verde (compilación + pruebas)
- [ ] `GET /actuator/health` responde 200 en local
- [ ] Token Firebase válido → solicitud llega al downstream con `X-User-Id` y `X-User-Plan`
- [ ] Token ausente → 401 sin llegar al downstream (prueba de contrato)
- [ ] Token inválido → 401 sin llegar al downstream (prueba de contrato)
- [ ] `POST /webhooks/wompi` llega al downstream sin headers de identidad (prueba de contrato)
- [ ] `.env.example` documenta todas las variables de §4
- [ ] Arranque sin variable obligatoria → falla con mensaje descriptivo (prueba de configuración)
- [ ] Ningún secreto real en el diff del PR
- [ ] Ninguna URL de downstream hardcodeada en el código
- [ ] `README.md` actualizado con comandos reales de ejecución y verificación
- [ ] Bitácora IA rellenada el mismo día (si hubo asistencia de IA)
- [ ] Título del PR: `CM-104 | feat(gateway): base técnica [IA-ASISTIDO]`
