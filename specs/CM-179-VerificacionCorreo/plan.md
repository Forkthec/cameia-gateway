# Plan — CM-179-VerificacionCorreo, pieza 3 (cameia-gateway)

Base: `origin/develop` `53fbe30` (11-oct-2026), con CM-260 ya fusionada. Estado: aprobado por Paula con la spec el 11-oct-2026. Un PR, independiente del de Cuentas: se pueden hacer en paralelo y desplegar en cualquier orden (Cuentas ya responde 403 a la activación sin verificar).

## 1. Rama y commits

La rama `CM-179-verificacion-correo-gateway` existe en local con 6 commits de spec, 5 por detrás de `develop` y sin remoto. Antes de T-3.1 se guarda su SHA en `ejecucion/evidencia/CM-179/` de la raíz y se rebasa sobre `origin/develop`.

| # | Commit (sin `[IA-ASISTIDO]`; trailer `Co-Authored-By`) | Tarjetas | Para el revisor |
|---|---|---|---|
| 1 | `CM-179 \| docs(gateway): spec, plan y tarjetas del 403 por correo sin verificar` | ya hecho | Criterio |
| 2 | `CM-179 \| test(gateway): pruebas del rechazo por correo sin verificar` | T-3.1 | Criterio: las rutas elegidas |
| 3 | `CM-179 \| feat(gateway): rechazar con 403 los tokens con el correo sin verificar` | T-3.2 | Criterio: `shouldReject` e `isVerificationRoute`; mecánico: `writeError` |
| 4 | `CM-179 \| docs(gateway): regla del correo sin verificar, catálogo y Postman` | T-3.3, T-3.4 | Mecánico |

Verificación por tarjeta solo de `FirebaseAuthGlobalFilterTest`; una sola vez al final, `clean verify`, cobertura y Newman.

## 2. Cómo se aborda

1. **Pruebas primero** (T-3.1): fallan por la razón correcta (hoy reenvía).
2. **Un punto de decisión** (T-3.2): el `flatMap` de la línea 244 elige entre escribir el 403 o reenviar. Funciones privadas `shouldReject`, `isVerificationRoute`, `writeEmailNotVerified` y `writeError` (que también usa el 401).
3. **Documentación y Postman** (T-3.3, T-3.4).

## 3. Archivos

| Acción | Archivo | Qué |
|---|---|---|
| Modificar | `src/main/java/tech/cameia/gateway/filter/FirebaseAuthGlobalFilter.java` | Constantes; `flatMap`; cuatro funciones privadas; `writeUnauthorized` delega en `writeError`; Javadoc de la clase |
| Modificar | `src/test/java/tech/cameia/gateway/filter/FirebaseAuthGlobalFilterTest.java` | Pruebas nuevas |
| Modificar | `CLAUDE.md` | Líneas 129, 134 y 232 (la regla y la fila de `GW-TBD-17`) |
| Modificar | `specs/NUMERACIONES.md` | Líneas 50 y 59 |
| Modificar | `docs/errores.md` | Fila de `EMAIL_NOT_VERIFIED` |
| Modificar | `docs/cameia-gateway-perfil.postman_collection.json` y la guía que la acompañe | Carpeta «Correo sin verificar» con el emulador |
| No se tocan | `AGENTS.md` (solo remite a `CLAUDE.md`), `docs/COMO-FUNCIONA.md` (no menciona la regla: comprobado con `git grep`), `docs/estandar-backend.md` (CM-296), `GlobalErrorHandler`, `application*.yml`, `pom.xml`, `Dockerfile`, `.github/**`, specs históricas de CM-14 | |

## 4. Reutiliza

`readEmailVerified`, `APPLICATION_JSON_UTF8`, el patrón de `writeUnauthorized` (cuerpo `{code,message}`, `X-Request-Id`, `WARN`), `mockToken`, `mockDownstream`, `webTestClient` y `OutputCaptureExtension` de la clase de pruebas, y el emulador del compose. Sin clases ni dependencias nuevas.

## 5. Riesgos

| Riesgo | Mitigación |
|---|---|
| Una sesión abierta con token `false` pierde el acceso salvo a la verificación | Es lo pedido; Frontend ya cierra la sesión (PR #70) y debe aplicar RT-02-CA06 |
| Se rompe una prueba existente con `email_verified=false` | Las tres que lo usan van a la ruta exceptuada (líneas 619 a 680); T-3.1 las corre antes de tocar el filtro |
| La comparación literal del `Content-Type` de Testing | Aviso con el despliegue (spec §3) |
| El orden de los filtros cambia y el OIDC firma antes del 403 | La prueba exige `mockDownstream.getRequestCount()` sin cambio; `OidcSigningFilterTest` sigue en verde |

## 6. Estimación

≈ 3 h, un PR.
