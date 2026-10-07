# Errores del servicio

## Formato

Las respuestas de error siguen la [sección 6 del estándar](estandar-backend.md#6-errores). Esta página lista lo que el servicio emite hoy.

## Códigos que el servicio emite

El Gateway conserva su formato `{"code","message"}` con un catálogo cerrado. Todas las respuestas llevan `Content-Type: application/json;charset=UTF-8` y el encabezado `X-Request-Id`. El mensaje es siempre un texto fijo del código: nunca se copia el de una excepción, que va solo al log con su `X-Request-Id`.

| Código | HTTP | Mensaje | Origen | Prueba |
|---|---|---|---|---|
| `AUTH_REQUIRED` | 401 | Token de acceso requerido | `FirebaseAuthGlobalFilter`: la ruta es de Caso A y falta `Authorization`, el esquema no es Bearer o el token viene vacío | `FirebaseAuthGlobalFilterTest`: `missingAuthorization_returns401AndUtf8Charset`, `emptyBearerToken_returns401AndUtf8CharsetWithoutReachingDownstream`, `nonBearerScheme_returns401AndUtf8Charset` |
| `AUTH_REQUIRED` | 401 | Token de acceso inválido | `FirebaseAuthGlobalFilter`: Firebase rechaza el token (`FirebaseAuthException` o `IllegalArgumentException`) | `FirebaseAuthGlobalFilterTest`: `invalidToken_returns401AndUtf8Charset`, `firebaseIllegalArgument_returns401AndUtf8Charset` |
| `AUTH_REQUIRED` | 401 | Token de acceso requerido o inválido | `GlobalErrorHandler`: red de seguridad para una `ResponseStatusException` con estado 401 que no escribió el filtro | `GlobalErrorHandlerLoggingTest`: `catalogStatus_declaresUtf8Charset` |
| `NOT_FOUND` | 404 | Recurso no encontrado | `GlobalErrorHandler`: ninguna ruta coincide con la petición | `GlobalErrorHandlerLoggingTest`: `errorResponse_isUnchanged`; `GatewayErrorMappingTest`: `unknownRoute_isLoggedAtInfoWithoutStackTrace` |
| `BAD_GATEWAY` | 502 | Respuesta inválida del servicio destino | `GlobalErrorHandler`: el destino cortó la conexión o respondió algo ininterpretable (excepción de `reactor.netty`) | `GlobalErrorHandlerLoggingTest`: `catalogStatus_declaresUtf8Charset` |
| `SERVICE_UNAVAILABLE` | 503 | Servicio destino no disponible | `GlobalErrorHandler`: el destino no es alcanzable (`ConnectException` o `UnknownHostException`); `OidcSigningGlobalFilter`: no se pudo obtener el token OIDC y no se reenvía nada | `GatewayErrorMappingTest`: `unreachableDownstream_returns503`; `OidcSigningFilterTest`: `tokenSourceFailure_returns503WithoutForwarding` |
| `GATEWAY_TIMEOUT` | 504 | El servicio destino no respondió a tiempo | `GlobalErrorHandler`: el destino superó `GATEWAY_TIMEOUT_MS` (`TimeoutException`) | `GatewayErrorMappingTest`: `unresponsiveDownstream_returns504` |
| `INTERNAL_ERROR` | 500 | Error interno del gateway | `GlobalErrorHandler`: cualquier fallo que no es de red ni trae un estado del catálogo | `GlobalErrorHandlerLoggingTest`: `unexpectedFailure_declaresUtf8Charset` |

Una `ResponseStatusException` con un estado que no está en el catálogo responde 500 `INTERNAL_ERROR`. Los errores del microservicio destino (por ejemplo un 404 o un 422 de Cuentas) no pasan por este catálogo: el Gateway los reenvía tal cual.

## Respuestas sin código

Ninguna.

## Cómo se agrega un código

Un código nuevo se agrega aquí con la spec que lo introduce, junto a su excepción de negocio, su estado, su mensaje y su prueba. Un código publicado no se reutiliza ni se renombra.
