# ADR 0001 · Código de error y `requestId` en la respuesta de error

## Estado

Aceptada. Decisión de Backend, 5 de octubre de 2026.

## Contexto

El Gateway es el primer punto de la cadena y responde errores propios (token ausente, servicio caído, tiempo agotado) con `{"code","message"}` y un catálogo cerrado de códigos.

## Decisión

El Gateway conserva ese formato y su catálogo, que está en [errores.md](../errores.md). Genera el `X-Request-Id` cuando el cliente no lo envía y lo devuelve en el encabezado de cada respuesta; los servicios lo toman como `requestId`.

## Consecuencias

Los servicios adoptan `code`, `requestId` y `errors[].code` según la sección 6 del [estándar](../estandar-backend.md); el Gateway no cambia su formato. Los códigos `AUTH_REQUIRED` y los demás del catálogo se conservan tal cual.

## Alternativas descartadas

- `codigoCameia` y `correlationId`: nombres propios sin ventaja sobre los miembros de extensión de la norma.
- Un formato de error propio distinto de `ProblemDetail`: obligaría a cambiar todos los clientes.
