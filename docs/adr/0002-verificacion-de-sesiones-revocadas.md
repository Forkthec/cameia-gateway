# ADR 0002 · Verificación de sesiones revocadas

## Estado

Aceptada. Decisión de Backend, 13 de octubre de 2026.

## Contexto

Cerrar la sesión en todos los dispositivos revoca los tokens de renovación del usuario en Firebase. Para que un dispositivo que ya
tiene un token vigente deje de entrar, el Gateway tiene que preguntarle a Firebase en cada petición si el token fue revocado, o si la
cuenta fue deshabilitada o eliminada. Esa pregunta obliga al Admin SDK a leer el usuario con la cuenta de servicio del Gateway.

La primera vez que se activó esa comprobación, todas las personas quedaron sin acceso: la cuenta de servicio no tenía permiso para
leer usuarios, cada lectura fallaba y el filtro respondía 401 «Token de acceso inválido» a todas las peticiones. El 401 ocultaba que
la causa era de despliegue y no del token.

## Decisión

- **Revocación en cada petición, sin caché.** Cada petición privada verifica el token con la comprobación de revocación activada. Un
  caché dejaría entrar durante su vigencia a un dispositivo cuya sesión ya se cerró.
- **401 sin distinguir la causa.** Un token con formato inválido, vencido, de una sesión revocada, de una cuenta deshabilitada, de una
  cuenta eliminada o de otro tenant responde el mismo 401 `AUTH_REQUIRED` «Token de acceso inválido». La causa exacta queda solo en el
  log, con el `requestId` y sin el token. Así no se revela a quien tenga el token si la cuenta fue deshabilitada o borrada, y Frontend
  maneja una sola respuesta: volver a «Iniciar sesión».
- **503 `AUTH_UNAVAILABLE` si Firebase no puede confirmar.** Cualquier otro fallo de Firebase al verificar (no responde, tiempo agotado,
  cuota, permisos, claves públicas no descargables) responde 503 con el código `AUTH_UNAVAILABLE` y «Servicio de autenticación no
  disponible». Es un código propio para que Frontend distinga «la sesión ya no vale» (401) de «inténtalo de nuevo» (503), y la sesión
  de la persona se conserva. El fallo se registra en ERROR con su traza.
- **Permiso comprobado al arrancar.** Al iniciar, el Gateway lee un usuario que no existe. Si Firebase responde «usuario no encontrado»,
  el permiso está concedido; si niega el permiso, no reconoce la credencial o no encuentra el proyecto, el arranque falla con un mensaje
  que nombra el rol `roles/firebaseauth.viewer`. En Cloud Run la revisión nueva no recibe tráfico y sigue sirviendo la anterior. Una
  caída pasajera de Firebase solo se avisa en el log para no impedir que el servicio escale.
- **Tiempos de espera.** Como cada petición privada hace una lectura de red, el Admin SDK se inicializa con 3 s de conexión y 5 s de
  lectura, los mismos de Cuentas.

## Consecuencias

- La cuenta de servicio del Gateway necesita `roles/firebaseauth.viewer` antes de desplegar esta versión.
- Cada petición privada añade una lectura a Firebase; con los topes, una Firebase lenta no deja la petición colgada más de 8 s por intento.
- Frontend recibe un código nuevo, `AUTH_UNAVAILABLE`, aditivo al catálogo; el 401 y su mensaje no cambian.

## Alternativas descartadas

- **Un interruptor por variable de entorno para la comprobación.** Cualquier persona con acceso a la configuración podría apagar la
  verificación de revocación sin que se note.
- **Solo el rol de IAM, sin comprobar al arrancar.** Si el rol faltara, cada petición respondería 503 hasta revertir a mano.
- **Responder 401 cuando Firebase falla.** Una caída de Firebase cerraría la sesión de todas las personas.
- **Aceptar el token sin comprobar la revocación cuando Firebase falla.** Un dispositivo perdido seguiría entrando mientras Firebase
  esté caído.
- **Un código de 401 por causa (`SESSION_REVOKED`, `ACCOUNT_DISABLED`, `ACCOUNT_NOT_FOUND`).** Ninguna pantalla lo usa, cambia el
  catálogo cerrado y expone el estado de la cuenta.
