# Docker Build Guide

## 🏗️ Build Architecture

We use **multi-stage Docker builds** with the official Maven image for optimal performance.

### Build Flow

```
┌─────────────────────────────────────────────────────────────┐
│ Stage 1: Builder (maven:3.9.6-eclipse-temurin-21-alpine)  │
│                                                             │
│  1. Copy common POM + src                                  │
│  2. Build common library (mvn install)                     │
│  3. Copy service POM + src                                 │
│  4. Build service JAR (mvn package)                        │
│                                                             │
│  Result: Target/*.jar (~50MB)                              │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│ Stage 2: Runtime (eclipse-temurin:21-jre-alpine)          │
│                                                             │
│  1. Copy only JAR from builder                             │
│  2. Non-root user for security                             │
│  3. Health check configured                                │
│                                                             │
│  Result: Final image (~200MB)                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 📦 What Gets Copied

### ✅ What We Copy (Minimal)

```dockerfile
COPY common/pom.xml common/pom.xml       # POM only
COPY common/src common/src                # Source code
COPY ingestion-service/pom.xml ...       # Service POM
COPY ingestion-service/src ...           # Service source
```

### ❌ What We DON'T Copy

```
❌ .mvn/                  # Not needed (Maven pre-installed)
❌ mvnw / mvnw.cmd        # Not needed (Maven pre-installed)
❌ target/                # Generated, not source
❌ .git/                  # Not needed for build
❌ Other services         # Each service builds independently
```

---

## 🚀 How to Build

### Build All Services
```bash
docker compose up --build -d
```

### Build Single Service
```bash
# From root directory
docker build -f ingestion-service/Dockerfile -t ingestion-service .

# Run it
docker run -p 8081:8081 \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=5433 \
  -e RABBITMQ_HOST=host.docker.internal \
  ingestion-service
```

### Build for Production (with tags)
```bash
docker build -f ingestion-service/Dockerfile \
  -t myregistry.com/ingestion-service:1.0.0 \
  -t myregistry.com/ingestion-service:latest \
  .
```

---

## 🎯 Why This Architecture?

### 1. **True Isolation**
Each service only knows about:
- ✅ `common` library (shared events/DTOs)
- ✅ Itself
- ❌ Other services (completely isolated)

### 2. **Fast Builds**
```
First build:  ~2 minutes (downloads dependencies)
Rebuild:      ~30 seconds (Docker layer caching)
Code change:  ~10 seconds (only rebuilds changed layers)
```

### 3. **Small Images**
```
Builder stage:  ~500MB (includes Maven, JDK, build tools)
Runtime image:  ~200MB (only JRE + JAR)
                ↓
                Deployed to production
```

### 4. **Security**
```dockerfile
# Non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Read-only filesystem (can be enabled)
# Minimal attack surface (only JRE, no build tools)
```

---

## 📊 Build Performance

### Maven Wrapper Approach (OLD) ❌
```dockerfile
COPY .mvn/ .mvn/
COPY mvnw ./
RUN ./mvnw clean install ...
```
- Downloads Maven every build: **+30 seconds**
- Extra files copied: **+5 seconds**
- Total overhead: **~35 seconds per build**

### Official Maven Image (NEW) ✅
```dockerfile
FROM maven:3.9.6-eclipse-temurin-21-alpine
RUN mvn clean install ...
```
- Maven pre-installed: **0 seconds**
- Clean, minimal copies: **faster**
- **~35 seconds saved per build**

---

## 🔄 Development Workflow

### Local Development (IDE)
```bash
# Use infrastructure only
docker compose -f docker-compose.infra.yml up -d

# Build with Maven wrapper (local)
./mvnw clean install

# Run from IDE or command line
cd ingestion-service
../mvnw spring-boot:run
```

### Docker Development
```bash
# Full stack with Docker
docker compose up --build -d

# Watch logs
docker compose logs -f ingestion-service

# Restart after code changes
docker compose up --build ingestion-service
```

---

## 🐳 Docker Compose Integration

All services use the same Dockerfile pattern:

```yaml
services:
  ingestion-service:
    build:
      context: .                              # Root directory
      dockerfile: ingestion-service/Dockerfile  # Service-specific
    environment:
      DB_HOST: postgres-ingestion
      RABBITMQ_HOST: rabbitmq
```

**Context is root** because:
- ✅ Can access `common/` directory
- ✅ Can access service directory
- ✅ Each service builds independently

---

## 🎓 Best Practices Applied

1. ✅ **Multi-stage builds** - Small runtime images
2. ✅ **Official base images** - Trusted, maintained
3. ✅ **Layer caching** - Fast rebuilds
4. ✅ **Non-root user** - Security best practice
5. ✅ **Health checks** - Kubernetes-ready
6. ✅ **Alpine Linux** - Minimal footprint
7. ✅ **Explicit versions** - Reproducible builds

---

## 🔍 Troubleshooting

### Build fails with "common not found"
```bash
# Make sure context is root directory
docker build -f ingestion-service/Dockerfile .
                                              ↑
                                         Context = root
```

### Slow builds
```bash
# Enable BuildKit for parallel builds
export DOCKER_BUILDKIT=1
docker compose up --build
```

### Maven dependency issues
```bash
# Clear Maven cache in builder
docker build --no-cache -f ingestion-service/Dockerfile .
```

---

## 📝 Summary

**What we achieved:**
- ✅ Fast Docker builds (Maven pre-installed)
- ✅ Clean Dockerfiles (only pom + src)
- ✅ True microservices isolation
- ✅ Production-ready multi-stage builds
- ✅ Maven wrapper for local development
- ✅ Official Maven image for Docker

**Result:** Professional-grade Docker setup for microservices! 🚀
