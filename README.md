# cameia-gateway

API Gateway de CAMEIA. Proporciona el punto de entrada controlado para la aplicación web y enruta solicitudes hacia los microservicios del MVP.

> **Estado:** CM-104 implementado — base técnica operativa (Spring Cloud Gateway + Firebase auth + Docker). Ver `specs/104-base-tecnica/` para el historial SDD.

## Alcance del Sprint 1

- Crear la estructura técnica y dependencias del Gateway.
- Preparar enrutamiento hacia los servicios realmente incluidos en el incremento.
- Definir controles transversales solo cuando sus contratos y criterios estén aprobados.

No implementa reglas de negocio de Cuentas, Perfil, Entrevista, Auditoría o Voz.

## Responsabilidades

- Recibir las solicitudes de la aplicación web.
- Verificar y propagar identidad/autorización según la decisión de Firebase.
- Enrutar hacia el servicio responsable sin acceder a sus bases de datos.
- Aplicar controles transversales aprobados: validación, CORS, límites o trazabilidad.
- Exponer respuestas y errores consistentes sin revelar información sensible.

## Contexto arquitectónico

```mermaid
flowchart LR
    W[cameia-web] --> G[cameia-gateway]
    G --> C[cameia-cuentas]
    G --> P[cameia-perfil]
    G --> E[cameia-entrevista]
    G --> A[cameia-auditoria]
    G --> V[cameia-voz]
    G -. verificación prevista .-> F[Firebase]
```

## Tecnología

| Elemento | Versión |
|---|---|
| Lenguaje | Java 21 |
| Framework | Spring Boot 4.1.1 / Spring Cloud Gateway (WebFlux) |
| Auth | Firebase Admin SDK |
| Build | Maven (sin instalación local requerida — usar Docker) |
| Ejecución objetivo | Contenedor OCI en Cloud Run |

## Contratos y dependencias

| Dependencia | Header propagado | Notas |
|---|---|---|
| `cameia-web` | — | Consumidor; origen CORS configurable |
| `cameia-cuentas` | `X-User-Id`, `X-User-Plan` | Sprint 1; también recibe `/webhooks/wompi` sin auth |
| `cameia-perfil` | `X-User-Id`, `X-User-Plan` | Sprint 1 |
| `cameia-entrevista` | `X-User-Id`, `X-User-Plan` | Sprint 1 |
| `cameia-voz` | `X-User-Id`, `X-User-Plan` | Sprint 2 (ruta declarada) |
| `cameia-auditoria` | `X-User-Id`, `X-User-Plan` | Sprint 2/3 (ruta declarada) |
| Firebase Auth | — | Verifica ID Token; extrae uid y claim `plan` |

El custom claim en Firebase se llama `plan` (string `"FREE"` | `"PREMIUM"`).
Lo escribe cameia-cuentas al activar una suscripción.

## Ejecución local

**Requisitos:** Docker Desktop. No se necesita JDK ni Maven instalado.

### 1. Configurar variables de entorno

```bash
cp .env.example .env
# Editar .env con los valores reales (Firebase, URLs de servicios)
```

### 2. Ejecutar los tests

```bash
docker compose run --rm verify
```

### 3. Construir la imagen

```bash
docker build -t cameia-gateway .
```

### 4. Arrancar en local

```bash
docker run --env-file .env -p 8080:8080 cameia-gateway
```

### 5. Verificar que está vivo

```bash
curl http://localhost:8080/actuator/health
# Respuesta esperada: {"status":"UP"}
```

## Configuración y seguridad

- No guardar secretos, credenciales Firebase ni `.env` en Git.
- Aplicar mínimo privilegio y no propagar datos que el servicio destino no necesite.
- No registrar tokens, CV, transcripciones ni payloads sensibles completos.
- Documentar variables por nombre y propósito en `.env.example` cuando sean aprobadas.

## Calidad esperada

- Pruebas de enrutamiento, errores, autenticación y autorización aplicables.
- Fallos seguros ante token inválido, destino no disponible o timeout.
- Formato, lint, pruebas, build y seguridad en CI cuando existan comandos reales.

## Contribución

- Rama estable: `main`.
- Rama de integración: `develop`.
- Ramas de trabajo: `<tipo>/CM-NNN-<descripcion-kebab-case>`.
- Los cambios ordinarios se integran a `develop` mediante Pull Request.
- La promoción a `main` utiliza un PR independiente.
- El autor no puede ser la única aprobación.

## Cuándo actualizar este README

Actualizarlo en el mismo PR que cambie propósito, stack, comandos, variables, rutas, autenticación, contratos, estructura, pruebas, despliegue o responsables. Si el cambio no requiere actualización, justificarlo en la plantilla del PR.