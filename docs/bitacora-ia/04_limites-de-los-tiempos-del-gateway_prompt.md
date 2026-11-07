# Entrada 04 · Límites de los tiempos máximos del Gateway

- **Fecha:** 2026-11-07 · **Herramienta:** Claude Code · **Responsable:** Backend
- **Salida:** [Spec de CM-357](../../specs/CM-357-TiempoMaximoEntrevistas/spec.md), decisiones D-2, D-5 y D-6
- **Decisión humana (HITL):** Paula, 7-nov-2026: D-2, tope de 600000 ms en la variable de entrevistas (opción B); D-5, rechazar la variable definida y vacía (opción A, la recomendada); D-6, mismo tope de 600000 ms en la variable global (opción recomendada). Revisión de esta entrada: PENDIENTE (Paula)

## Qué cambió el rumbo

El criterio de la tarea solo pedía «entero mayor que 0». En tres puntos la IA propuso opciones y Paula eligió:

- **D-2, tope de la variable de entrevistas:** Paula eligió poner un máximo de 600000 ms (10 minutos) para que un descuido no
  retenga conexiones, en vez de aceptar cualquier entero positivo.
- **D-5, variable definida y vacía:** el marcador `${VAR:120000}` solo aplica el valor por omisión si la variable no existe; con la
  variable vacía la ruta recibiría un texto vacío y fallaría en cada petición. Paula eligió que el Gateway no arranque y diga por
  qué (regla de errores visibles), en vez de tratarla como ausente con código de configuración extra.
- **D-6, tope de la variable global:** una prueba con una petición real mostró que un valor enorme de `GATEWAY_TIMEOUT_MS` (el mayor
  `long`) hace que Reactor falle con `ArithmeticException: long overflow` y responda el 500 genérico en cada petición. Paula eligió el
  mismo tope de 600000 ms y el mismo mensaje en las dos variables, en vez de 3600000 ms o de un tope solo técnico.

## Prompt

```
Rol: eres ingeniero de Backend de CAMEIA y trabajas para la dueña del Backend. Revisas la validación de las variables de tiempo del
Gateway conforme a su estándar de calidad y a su regla de errores visibles.

Contexto: el Gateway lee GATEWAY_TIMEOUT_MS (global, 30 s) y GATEWAY_INTERVIEWS_TIMEOUT_MS (entrevistas, 120 s) como metadatos de la
ruta que se interpretan al atender cada petición. El criterio de la tarea pide que un valor 0, negativo o no numérico impida el
arranque con un mensaje claro.

Tarea: para cada variable decide el rango válido, qué hacer cuando está definida y vacía, y qué pasa con un valor enorme. Compara las
opciones, su riesgo para la persona usuaria y recomienda una.

Condiciones: toda afirmación sobre el comportamiento se mide con una petición real al Gateway arrancado; ningún fallo previsible
termina en el error genérico; los textos de los mensajes se fijan en la spec; la decisión queda con su porqué y las alternativas
descartadas.

Formato: una tabla por decisión con las opciones, sus consecuencias y la recomendación, y la redacción final de los requisitos.
```
