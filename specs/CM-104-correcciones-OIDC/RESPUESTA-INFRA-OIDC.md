# Respuesta a SOLICITUD-INFRA-OIDC — lo que ya se puede confirmar

**Fecha:** 11/09/2026 · **De:** Paula Andrea Muñoz Delgado (DevOps) · **Para:** Backend/Gateway
(Vela) · **Referencia:** `comunicaciones/solicitudes/SOLICITUD-INFRA-OIDC.md`, Tarea Jira **CM-143**

---

Gracias por mandarla con tiempo — respondo las 4 preguntas del resumen, lo que ya se puede cerrar
hoy y lo que sigue bloqueado.

## 1. Service account del Gateway

De acuerdo con la recomendación de Backend: **una service account por servicio**, no compartida.
Se crea en el proyecto `cameia-app` (el mismo donde vive todo lo demás) cuando se aprovisione Cloud
Run — hoy no existe todavía porque el proyecto sigue sin Billing activo (ver bloqueo más abajo). No
hace falta ningún archivo de clave de tu lado, como ya asumía la solicitud.

## 2. Quién concede `roles/run.invoker`

Yo — soy Owner confirmado del proyecto `cameia-app`. En cuanto cada microservicio esté desplegado
en Cloud Run, aplico el binding por servicio (no uno global) apenas exista la service account del
Gateway.

## 3. URL exacta de cada microservicio / dominio propio delante

**Todavía no hay URLs reales**: Cloud Run no está aprovisionado (Billing de GCP bloqueado — ver
`comunicaciones/11092026_interno_bloqueos-sprint-72h.md`, bloqueo 1). En cuanto se resuelva, te paso
las URLs `*.run.app` exactas de cada servicio.

Sobre el dominio propio, decisión final (no provisional, para no tener que retocar esto después):

- **`cameia.app`** (ya comprado) → frontend, vía Firebase Hosting.
- **`api.cameia.app`** → reservado para la URL pública del **Gateway** cuando exista Cloud Run (es
  el único servicio que un cliente externo llama directamente). Se mapea con Cloud Run Domain
  Mapping en cuanto el Gateway esté desplegado — no cambia nada del lado de Backend.
- **`cameia-cuentas`, `cameia-perfil`, `cameia-entrevista`, `cameia-voz`, `cameia-auditoria`: nunca
  van a tener dominio propio.** Nadie los llama excepto el Gateway, autenticado con `run.invoker` —
  no hay motivo para exponerlos con un dominio público. Su *audience* OIDC es y va a seguir siendo
  su URL real de Cloud Run (`*.run.app`), de forma permanente, no "por ahora". Esto es justo lo que
  evita el problema que señalabas (audience mal configurado = 401 silencioso): al no ponerles nunca
  un dominio propio, ese valor no vuelve a cambiar.

Con esto no debería hacer falta retocar la configuración de audience de ningún microservicio más
adelante — la única URL que cambiará alguna vez es la del propio Gateway (de `*.run.app` a
`api.cameia.app`), y esa la maneja Frontend/consumidores externos, no el Gateway hacia sus
microservicios.

## 4. `cameia-voz` y `cameia-auditoria`

Ya está decidido (no es nuevo por esto): ninguno de los dos se despliega en esta ventana — no tienen
código todavía. Sus rutas en el Gateway quedan como marcador hasta que existan; no hace falta
quitarlas ni resolver la variable ahora.

---

## Lo que sigue bloqueado, no por falta de respuesta

Todo lo de arriba depende de que Cloud Run exista, y eso depende de Billing de GCP, que sigue sin
resolverse (posible restricción de la organización `unicauca.edu.co` sobre cuentas de facturación).
En cuanto se active, aviso con las URLs reales y armamos las service accounts.

## Seguimiento

Tarea Jira **CM-143** (sprint activo) trackea esto. Cuando Cloud Run exista, actualizo ahí y aviso
por este mismo canal.
