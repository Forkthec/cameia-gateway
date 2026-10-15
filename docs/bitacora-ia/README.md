# Bitácora de IA

Esta carpeta guarda la **entrada** de cada decisión en la que una persona cambió el rumbo de una propuesta de la IA (alcance, arquitectura, seguridad, costo, calidad o proceso): el prompt armado con rol, contexto, tarea, condiciones y formato. La **salida** es la spec del repositorio (`specs/CM-NNN-*/spec.md`, sección de decisiones, con la decisión humana, el porqué y las alternativas descartadas); la bitácora la enlaza y no la copia.

Cada entrada es un archivo `NN_tema_prompt.md`, con `NN` consecutivo.

| Entrada | Decisión | Salida |
|---|---|---|
| [01_alinear-estandar-backend_prompt.md](01_alinear-estandar-backend_prompt.md) | Alinear los documentos y el estándar de los cuatro repositorios de Backend | [Spec de CM-283](../../specs/CM-283-AlinearEstandar/spec.md) |
| [02_sesiones-revocadas-en-el-gateway_prompt.md](02_sesiones-revocadas-en-el-gateway_prompt.md) | Verificación de sesiones revocadas: permiso comprobado al arrancar, 503 con código propio y 401 sin causa visible | [Spec de CM-291](../../specs/CM-291-CerrarSesionEnTodosLosDispositivos/spec.md), P-1 a P-3 |
