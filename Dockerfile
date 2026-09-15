# ── Etapa 1: builder ────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

# Pre-descarga de dependencias para aprovechar el caché de capas
COPY pom.xml .
RUN mvn dependency:go-offline -B --no-transfer-progress

COPY src ./src
RUN mvn package -DskipTests -B --no-transfer-progress

# ── Etapa 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

# REQ-NF-06 — usuario sin privilegios. La app no escribe en disco; el chown cubre /app.
RUN addgroup -S cameia && adduser -S cameia -G cameia && chown -R cameia:cameia /app
USER cameia

EXPOSE 8080

# REQ-NF-05 — la imagen trae su propia comprobación, no solo Compose.
# Mismo comando e intervalos que el healthcheck de docker-compose.yml. wget viene en alpine.
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=12 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
