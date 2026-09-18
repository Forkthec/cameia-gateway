# Numeraciones usadas en `specs/`

- **Actualizado:** 15/09/2026
- **Para qué existe:** evitar IDs repetidos entre specs. El 15/09/2026 se detectó que `GW-TBD-10` y
  `GW-TBD-11` estaban asignados dos veces, y hubo que renumerar.
- **Regla:** antes de asignar un ID nuevo de la serie `GW-TBD`, se consulta este archivo y se
  actualiza **en el mismo cambio** que crea el ID.

---

## 1. Specs y HU

| Carpeta | HU | Sprint |
|---|---|---|
| `CM-104-base-tecnica/` | CM-104 | 1 |
| `CM-104-correcciones/` | CM-104 (revisión) | 1 |
| `CM-104-correcciones-OIDC/` | CM-104 (revisión) | 1 |
| `CM-113-integracion-perfil/` | CM-113 | 1 |
| `CM-14-Registro-usuario/` | CM-14 | 1 |

---

## 2. Clarificaciones `GW-TBD` — serie única para todo el repositorio

**Siguiente número libre: `GW-TBD-27`.**

El estado solo se anota donde el spec lo registra; en el resto, el spec es la fuente.

| ID | Spec | Tema | Estado |
|---|---|---|---|
| `GW-TBD-01` | CM-104-base-tecnica | Versión de `spring-cloud-dependencies` compatible con Spring Boot 4.1.1 | Ver spec |
| `GW-TBD-02` | CM-104-base-tecnica | Versión de Firebase Admin SDK | Ver spec |
| `GW-TBD-03` | CM-104-base-tecnica | Puerto final del Gateway | Ver spec |
| `GW-TBD-04` | CM-104-base-tecnica | Claim exacto del plan en el ID Token | Ver spec |
| `GW-TBD-05` | CM-104-base-tecnica | Nombre del claim del plan en Firebase | Ver spec |
| `GW-TBD-06` | CM-104-correcciones | Formato del claim `roles`: cadena o lista | Ver spec |
| `GW-TBD-07` | CM-104-correcciones | Qué hace Cuentas si falta `X-User-Email` | Ver spec |
| `GW-TBD-08` | CM-104-correcciones | Qué microservicio aplica los límites por plan | Ver spec |
| `GW-TBD-09` | CM-104-correcciones | Redundancia entre `roles` y `plan` | Ver spec |
| `GW-TBD-10` | CM-104-correcciones | Estados de error fuera del catálogo (`405` → `INTERNAL_ERROR`) | Ver spec |
| `GW-TBD-11` | CM-104-correcciones | Todo endpoint expuesto de Actuator es público | Ver spec |
| `GW-TBD-12` | CM-104-correcciones-OIDC | URL exacta de Cloud Run de cada microservicio (audience) | Parcial: faltan las URLs |
| `GW-TBD-13` | CM-104-correcciones-OIDC | Audience de microservicios no desplegados | ✅ Cerrado |
| `GW-TBD-14` | CM-104-correcciones-OIDC | Webhook de Wompi: firma del cuerpo frente a `Authorization` | Abierto |
| `GW-TBD-15` | CM-14-Registro-usuario | Quién crea el usuario en Firebase | ✅ Cerrado |
| `GW-TBD-16` | CM-14-Registro-usuario | Envío de correos de verificación y recuperación | ✅ Cerrado |
| `GW-TBD-17` | CM-14-Registro-usuario | Qué puede hacer un usuario con `email_verified=false` | 🟡 Parcial: ruta cerrada en CM-14-verificacion-correo (`POST /api/v1/users/me/verification`); falta si el Gateway bloquea el resto |
| `GW-TBD-18` | CM-14-Registro-usuario | Protección del registro contra abuso | ✅ Cerrado para el Gateway · abierto para DevOps |
| `GW-TBD-19` | CM-14-Registro-usuario | Perfil de desarrollo activo en despliegue | ✅ Cerrado |
| `GW-TBD-20` | CM-14-Registro-usuario | Inventario de health v1 | ✅ Cerrado |
| `GW-TBD-21` | CM-14-Registro-usuario | Respuesta ante correo ya registrado | ✅ Cerrado |
| `GW-TBD-22` | CM-14-Registro-usuario | ID de la HU | ✅ Cerrado |
| `GW-TBD-23` | CM-14-Registro-usuario | Prefijo público o método + ruta exactos | ✅ Cerrado |
| `GW-TBD-24` | CM-104-correcciones-OIDC | Service account del Gateway en Cloud Run *(antes `GW-TBD-10` en ese spec)* | Respondido. Creación pendiente |
| `GW-TBD-25` | CM-104-correcciones-OIDC | Quién concede `roles/run.invoker` *(antes `GW-TBD-11` en ese spec)* | Respondido. Ejecución pendiente |
| `GW-TBD-26` | CM-14-verificacion-correo | Propagar `email_verified` o rechazar con `403` en el Gateway | ✅ Cerrado: se propaga en `X-User-Email-Verified` |

---

## 3. Requisitos — numeración local de cada spec

| Spec | Prefijos y rango |
|---|---|
| CM-104-base-tecnica | `REQ-01` … `REQ-08`, `REQ-NF-01` … `REQ-NF-04` |
| CM-104-correcciones | `REQ-01` … `REQ-15`, `REQ-NF-01` … `REQ-NF-07` |
| CM-113-integracion-perfil | `REQ-01` … `REQ-10` |
| CM-104-correcciones-OIDC | `REQ-OIDC-01` … `REQ-OIDC-09`, `REQ-NF-OIDC-01` … `REQ-NF-OIDC-03` |
| CM-14-Registro-usuario | `REQ-REG-01` … `REQ-REG-10`, `REQ-NF-REG-01` … `REQ-NF-REG-02` |

> `REQ-NN` sin prefijo se repite entre base técnica, correcciones y CM-113. Al citarlos desde otro
> spec, se nombra el spec. Los specs nuevos usan un prefijo propio (`REQ-OIDC`, `REQ-REG`).

---

## 4. Tareas — numeración local de cada spec

| Spec | Rango |
|---|---|
| CM-104-base-tecnica | Casillas sin ID |
| CM-104-correcciones | `T-00` … `T-44`, más `T-21a` y `T-24a` |
| CM-113-integracion-perfil | `T-01` … `T-07` |
| CM-104-correcciones-OIDC | `T-INF-01` … `T-INF-04`, `T-01` … `T-29` |
| CM-14-Registro-usuario | `T-00a` … `T-00q`, `T-01` … `T-24` (`T-13` omitida) |
| CM-14-verificacion-correo | `T-01` … `T-13` |

> Los `T-NN` se repiten entre specs por diseño: se citan siempre junto al spec.
