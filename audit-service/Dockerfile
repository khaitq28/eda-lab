# Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Copy Maven wrapper and pom files
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY common/pom.xml common/
COPY audit-service/pom.xml audit-service/

# Download dependencies (cached layer)
RUN ./mvnw dependency:go-offline -pl audit-service -am -B

# Copy source code
COPY common/src common/src
COPY audit-service/src audit-service/src

# Build the application
RUN ./mvnw clean package -pl audit-service -am -DskipTests -B

# Runtime stage
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
