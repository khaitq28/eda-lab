# Build stage - Use official Maven image
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /app

# Copy only common module (shared library)
COPY common/pom.xml common/pom.xml
COPY common/src common/src

# Copy only audit service
COPY audit-service/pom.xml audit-service/pom.xml
COPY audit-service/src audit-service/src

# Build common library first (install to local Maven repo in container)
RUN mvn clean install -f common/pom.xml -DskipTests -B

# Build audit service
RUN mvn clean package -f audit-service/pom.xml -DskipTests -B

# Runtime stage - Minimal JRE image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Add non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy the built artifact
COPY --from=builder /app/audit-service/target/*.jar app.jar

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8084/actuator/health || exit 1

EXPOSE 8084

ENTRYPOINT ["java", "-jar", "app.jar"]
