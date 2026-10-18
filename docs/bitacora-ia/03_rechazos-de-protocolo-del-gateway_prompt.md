# Entrada 03 · Rechazos de protocolo del Gateway

- **Fecha:** 2026-10-17 · **Herramienta:** Claude Code · **Responsable:** Backend
- **Salida:** [Spec de CM-335](../../specs/CM-335-ErroresDeProtocoloPrevisibles/spec.md), decisión D-1
- **Decisión humana (HITL):** Paula, 17-oct-2026: opción A (limitación documentada con pruebas que la vigilan), la recomendada. Revisión de esta entrada: PENDIENTE (Paula)

## Qué cambió el rumbo

Al revisar qué responde el Gateway a las peticiones mal formadas que Reactor Netty rechaza antes de cualquier filtro, la IA propuso tres
opciones y Paula eligió no escribir código de producción:

- **A (elegida):** documentar cada rechazo con su estado y su causa, y vigilarlo con pruebas de sockets reales, para que una
  actualización de la librería que lo altere haga fallar una prueba.
- **B (descartada):** un manejador de Netty propio que responda JSON con `code`; depende de nombres internos de la librería y toca el
  único punto de entrada de todo el tráfico.
- **C (descartada):** B más validar la dirección antes de pasarla a WebFlux.

## Prompt

```
Rol: eres ingeniero de Backend de CAMEIA y trabajas para la dueña del Backend. Revisas los errores previsibles del Gateway conforme a su
estándar de calidad y a su regla de errores visibles.

Contexto: el Gateway responde 400, 414 y 431 con el cuerpo vacío y sin código cuando Reactor Netty o el adaptador de WebFlux rechazan
una petición (carácter de control en un encabezado, encabezados o dirección demasiado largos, %zz en la ruta o la consulta), y la
documentación dice «Respuestas sin código: Ninguna». Ningún navegador produce esas peticiones.

Tarea: compara (A) documentar y vigilar con pruebas de sockets, (B) responder JSON con code desde un manejador de Netty propio y (C) B
más validar la dirección antes de pasarla a WebFlux. Para cada una di costo, riesgo en el único punto de entrada de todo el tráfico y
beneficio para una persona usuaria, y recomienda una.

Condiciones: toda afirmación sobre los límites o los estados se mide con sockets contra el jar empaquetado; los códigos y textos de B no
se asumen sin confirmación; la decisión queda en la spec con su porqué y las alternativas descartadas.

Formato: una tabla con las tres opciones, su costo y el veredicto, y la redacción final de los requisitos de la opción elegida.
```
