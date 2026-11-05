# Plan — CM-291 en `cameia-gateway`: rechazar los tokens revocados

Requisitos: `spec.md` de esta carpeta, REQ-291-10 a REQ-291-20. Las tarjetas escriben la opción A de las decisiones P-1, P-2 y P-3,
decididas por Paula el 13-oct-2026. El rol IAM que exige la tarjeta G-2 ya está concedido.

## 1. Enfoque

| Pieza | Decisión | Por qué |
|---|---|---|
| `FirebaseAuthGlobalFilter` | **Extender**: `verifyIdToken(token, true)`; separar los rechazos del token (401) de la imposibilidad de verificar (503); causa en el log | Es la única pieza que verifica tokens (`CLAUDE.md` §2) |
| `FirebaseConfig` | **Extender**: 3000 ms de conexión y 5000 ms de lectura en `FirebaseOptions` | Hoy no tiene tiempo de espera; con la revocación, cada petición hace una llamada de red |
| `FirebaseRevocationPermissionCheck` | **Crear** en `config` | Falla el arranque sin el permiso de lectura de usuarios: impide repetir el 401 general. No cabe en `FirebaseConfig.init` porque `FirebaseConfigTest` lo ejecuta y haría una llamada de red real |
| `application.yml` | **Extender**: ruta `cameia-cuentas-auth` `Path=/api/v1/auth/**` | El endpoint de Cuentas vive fuera de `/api/v1/users/**` |
| Catálogo | **Extender** con `AUTH_UNAVAILABLE` (503) en `docs/errores.md` | Un código por causa (P-2 A) |
| ADR | **Crear** `docs/adr/0002-verificacion-de-sesiones-revocadas.md` | Decisión de contrato y de seguridad |

Sin dependencias nuevas, sin cambiar `GlobalErrorHandler`, `PUBLIC_ROUTES` ni la firma OIDC.

## 2. Archivos

| Archivo | Acción |
|---|---|
| `src/main/java/tech/cameia/gateway/config/FirebaseConfig.java` | Modificar |
| `src/main/java/tech/cameia/gateway/config/FirebaseRevocationPermissionCheck.java` | Crear |
| `src/main/java/tech/cameia/gateway/filter/FirebaseAuthGlobalFilter.java` | Modificar |
| `src/main/resources/application.yml` | Modificar |
| `src/test/java/tech/cameia/gateway/config/FirebaseConfigTest.java` | Modificar: 1 prueba |
| `src/test/java/tech/cameia/gateway/config/FirebaseRevocationPermissionCheckTest.java` | Crear |
| `src/test/java/tech/cameia/gateway/filter/FirebaseAuthGlobalFilterTest.java` | Modificar: 18 simulaciones a `verifyIdToken(x, true)`, 1 prueba ajustada, 14 nuevas |
| `src/test/java/tech/cameia/gateway/exception/GatewayErrorMappingTest.java`, `.../filter/OidcSigningFilterTest.java` | Modificar: 1 simulación cada uno |
| `docs/cameia-gateway-perfil.postman_collection.json` | Modificar: carpeta nueva |
| `docs/errores.md`, `docs/adr/0002-verificacion-de-sesiones-revocadas.md`, `docs/COMO-FUNCIONA.md` (solo si describe la verificación), `CLAUDE.md` | Modificar o crear |
| `docs/bitacora-ia/02_sesiones-revocadas-en-el-gateway_prompt.md`, `docs/bitacora-ia/README.md` | Crear y modificar |

## 3. Orden y commits

| Commit | Tarjeta | Mensaje |
|---|---|---|
| 1 | G-1 | `CM-291 \| feat(gateway): tiempos de espera del Admin SDK de Firebase` |
| 2 | G-2 | `CM-291 \| feat(gateway): no arrancar sin permiso para leer usuarios de Firebase` |
| 3 | G-3 | `CM-291 \| feat(gateway): rechazar con 401 los tokens revocados y responder 503 si Firebase no confirma` |
| 4 | G-4 | `CM-291 \| feat(gateway): ruta de cierre de sesión en todos los dispositivos hacia Cuentas` |
| 5 | G-5 | `CM-291 \| test(gateway): colección de Postman de la revocación de sesiones` |
| 6 | G-6 | `CM-291 \| docs(gateway): catálogo, ADR 0002, CLAUDE.md y bitácora de la revocación` |

G-3 es el commit que pide más criterio en la revisión; G-1, G-4 y el reemplazo de las simulaciones son mecánicos.

## 4. Riesgos

| Riesgo | Mitigación |
|---|---|
| Repetir el 401 general | Causa demostrada (spec §3); rol IAM antes de fusionar (A-1); el Gateway no arranca sin permiso (G-2); un fallo de Firebase ya no es 401 (G-3) |
| Las pruebas existentes simulan `verifyIdToken(String)` y el filtro pasa a llamar `verifyIdToken(String, boolean)`: la simulación devolvería `null` | G-3 reemplaza las 20 simulaciones antes de cambiar el filtro (trampa verificada: 18 en `FirebaseAuthGlobalFilterTest`, 1 en `GatewayErrorMappingTest`, 1 en `OidcSigningFilterTest`) |
| `invalidToken_returns401AndUtf8Charset` lanza `mock(FirebaseAuthException.class)`, cuyo código de autenticación es `null`: con la regla nueva daría 503 | G-3 lo cambia por una excepción real con `AuthErrorCode.INVALID_ID_TOKEN` |
| Latencia de una llamada extra por petición | Medida en staging (DES-02) y en Newman (`< 2000 ms`) |
| Alguien retira el rol IAM | Antes de fusionar se comprueba en la política IAM; si falta, la revisión nueva no arranca y Cloud Run sigue con la anterior |

## 5. Integración

- Sin base de datos. `docs/errores.md` y `CLAUDE.md` los tocará también CM-296 (alineación): conflicto de filas, se resuelve al rebasar.
- Rama `CM-291-cerrar-sesion-en-todos-los-dispositivos` desde `origin/develop` del Gateway. No se apila con Cuentas: son repositorios
  distintos. El flujo completo en local (G-5) necesita la rama de Cuentas en marcha.
- Prueba en staging (tras fusionar y desplegar, por Backend): CA-1.8.12 (`GET /api/v1/users/me` con una sesión vigente → 200),
  CA-1.8.9 (revocar y reusar el token viejo → 401) y DES-02; CA-1.8.10 y CA-1.8.11 los ejecuta Testing con la cuenta que prepara DevOps.
