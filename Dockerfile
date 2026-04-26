# ============================================================
# Multi-stage Dockerfile for Spring Boot
# ============================================================

# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -q    # Cache dependencies layer
COPY src ./src
RUN mvn clean package -DskipTests -q

# Stage 2: Runtime (minimal JRE image)
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Create non-root user (security best practice)
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

COPY --from=builder /build/target/*.jar app.jar

# Install curl for healthcheck
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

USER appuser
EXPOSE 9090

# JVM tuning for containers
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseG1GC", \
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
