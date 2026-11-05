# CM-340 · Plan

Spec aprobada el 24-oct-2026 (D-1 = A, D-2 = A). Base: `origin/develop` `84d7e02`.

## Enfoque

Un solo cambio de configuración y sus pruebas. Sin código de producción nuevo, sin dependencias, sin migración.

## Archivos

| Archivo | Cambio |
|---|---|
| `src/main/resources/application.yml` | Agregar `Idempotency-Key` a `allowedHeaders` (líneas 87 a 90) |
| `src/test/java/tech/cameia/gateway/config/GlobalCorsConfigTest.java` | Ampliar con las pruebas de la spec §7 |
| `CLAUDE.md` (línea 151) | Decir que `Idempotency-Key` sí se admite y por qué (clave de repetición, no identidad) |
| `docs/COMO-FUNCIONA.md` (líneas 422 y 428) | Lista de cabeceras admitidas y la prueba automática del rechazo de `X-User-*` |

No se tocan: `exposedHeaders` (queda como `GW-TBD-10`), rutas, filtros, `GlobalErrorHandler`, Entrevista, la web.

## Reutiliza

`GlobalCorsConfigTest` (mismo `@SpringBootTest`, `TestFirebaseConfig`, `WebTestClient` y propiedades de origen). No se crea clase.
Si crece, cada prueba usa un método auxiliar privado `preflight(origin, headers, path)`; no se extrae fuera de la clase.

## Orden y commits

| Commit | Contenido | Verificación |
|---|---|---|
| 1 | `CM-340 \| fix(gateway): admitir Idempotency-Key en el CORS`: tarjeta T1 (pruebas primero en rojo, luego el YAML) | `GlobalCorsConfigTest` en verde |
| 2 | `CM-340 \| docs(gateway): cabeceras admitidas por CORS`: tarjeta T2 | lectura |

Después, una vez sobre la punta: `./mvnw.cmd clean verify`, cobertura de lo tocado y comprobación en el entorno (spec §8).

## Riesgos

| Riesgo | Mitigación |
|---|---|
| Aserción de estado del rechazo escrita de memoria | Primero se ejecuta el preflight rechazado y se fija lo que responde; la aserción firme es la ausencia de `Access-Control-Allow-Origin` |
| El origen de la web de staging no está en la variable del entorno | Se ve en la comprobación real; la variable es de DevOps, no se lee ni se imprime |
| `Allow-Headers` devuelve el nombre en otra capitalización | Comparar sin distinguir mayúsculas |
| Cambios locales sin commit en el Gateway de Paula (`.gitignore`, `mvnw.cmd`, `specs/CM-296-*`) | Worktree aparte desde `origin/develop`; la spec de CM-340 se copia dentro |

## Atributos de calidad

Los de la spec §6; la evidencia es la prueba en rojo antes del cambio, la suite en verde y los tres preflights reales.
