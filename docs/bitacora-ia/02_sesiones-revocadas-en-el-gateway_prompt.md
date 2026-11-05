# Entrada 02 · Sesiones revocadas en el Gateway

- **Fecha:** 2026-10-13 · **Herramienta:** Claude Code · **Responsable:** Backend
- **Salida:** [Spec de CM-291](../../specs/CM-291-CerrarSesionEnTodosLosDispositivos/spec.md), decisiones P-1 a P-3
- **Decisión humana (HITL):** Paula, 13-oct-2026: opción A en las tres. Revisión de esta entrada: PENDIENTE (Paula)

## Qué cambió el rumbo

Al diseñar la verificación de revocación en el Gateway, la IA propuso tres decisiones que cambian el comportamiento visible y el
despliegue, y Paula eligió en cada una la opción recomendada, sin interruptor de configuración:

- **P-1:** la verificación se activa con el permiso de lectura de usuarios de Firebase concedido antes de fusionar, y el Gateway no
  arranca sin ese permiso; se descartó un interruptor por variable de entorno porque permitiría apagar la verificación sin que se note.
- **P-2:** si Firebase no puede confirmar el token, el Gateway responde 503 con un código propio y no cierra la sesión; se descartó el
  401, que cerraría la sesión de todas las personas ante una caída de Firebase.
- **P-3:** el 401 de un token revocado, de una cuenta deshabilitada o de una eliminada conserva el mismo código de hoy y la causa exacta
  queda solo en el log, para no revelar el estado de la cuenta a quien tenga el token.

## Prompt

```
Rol: eres ingeniero de Backend de CAMEIA y trabajas para la dueña del Backend. Diseñas la verificación de sesiones revocadas del
Gateway, conforme a su estándar de calidad y a su regla de errores visibles.

Contexto: hoy el Gateway valida el token de Firebase sin comprobar la revocación; un dispositivo perdido sigue entrando hasta que el
token vence. Al activar la comprobación por primera vez todas las personas quedaron sin acceso (401 general) porque la cuenta de
servicio no podía leer usuarios.

Tarea: para cada una de estas tres decisiones, compara las alternativas con su riesgo y recomienda una: (P-1) cómo se activa la
verificación sin repetir el 401 general; (P-2) qué responde el Gateway si Firebase no puede confirmar el token; (P-3) qué código
lleva el 401 de un token revocado, de una cuenta deshabilitada o de una eliminada.

Condiciones: una dependencia caída nunca cierra la sesión de nadie; ninguna respuesta revela si la cuenta fue deshabilitada o borrada;
un código por causa solo si alguna pantalla lo usa; toda afirmación sobre Firebase se prueba contra el emulador o se declara como no
conocida; cada decisión queda en la spec con su porqué y las alternativas descartadas.

Formato: una tabla por decisión con las opciones, su efecto y el veredicto, y la redacción final del requisito que cambia.
```
