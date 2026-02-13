# Architecture Decision Records (ADR)

## ADR-001: Pragmatic Use of Java 21 Features

**Date:** 2026-02-13  
**Status:** Accepted  
**Decision Makers:** Project Team

---

### Context

Java 21 offers many new features (Records, Sealed Classes, Pattern Matching, Virtual Threads). We needed to decide: use them everywhere or selectively?

### Decision

**Use Java 21 features pragmatically - where they enforce architectural constraints.**

#### What We Use

| Component | Technology | Reason |
|-----------|-----------|--------|
| **Events** | Records | Immutability required by EDA |
| **Event Hierarchy** | Sealed Interface | Fixed set of events |
| **DTOs** | Lombok @Data/@Builder | Familiar Spring Boot style |
| **JPA Entities** | Lombok @Data | Standard Spring Data JPA |
| **Event Routing** | Pattern Matching | Type safety + exhaustiveness |

### Rationale

1. **Events MUST be immutable** (EDA best practice)
   - Records enforce this at compile-time
   - Alternative: `@Value` from Lombok (but Records are Java-native)

2. **Event set is fixed** (not open-ended)
   - Sealed interfaces make this explicit
   - Enables exhaustive pattern matching

3. **DTOs don't need same constraints**
   - Lombok is familiar to Spring developers
   - `@Builder` pattern is convenient
   - Easier to extend and test

4. **JPA entities need mutability**
   - Hibernate requires setters
   - Records don't work with JPA
   - Lombok is the standard

### Consequences

**Positive:**
- ✅ Events are immutable (can't be accidentally modified)
- ✅ Compiler ensures all events are handled in switches
- ✅ Code remains readable and familiar to Spring developers
- ✅ Clear separation: immutable events, flexible DTOs

**Negative:**
- ⚠️ Mixed approach (Records + Lombok in same project)
- ⚠️ Team needs to understand when to use which

**Mitigation:**
- Clear documentation in `JAVA21_FEATURES.md`
- Code comments explaining choices
- This ADR as reference

---

## ADR-002: Microservices Independence

**Date:** 2026-02-13  
**Status:** Accepted

### Decision

Each service is **fully independent** - not a multi-module Maven project where services depend on parent POM.

#### Structure

```
eda-lab/
├── pom.xml              # Optional aggregator (NOT a parent)
├── common/              # Independent library
│   └── pom.xml         # Parent: spring-boot-starter-parent
├── ingestion-service/   # Independent service
│   └── pom.xml         # Parent: spring-boot-starter-parent
│                       # Dependency: common via Maven coordinates
├── validation-service/  # Independent service
├── enrichment-service/  # Independent service
└── audit-service/       # Independent service
```

### Rationale

**Microservices principles:**
- Each service can be deployed independently
- No build-time coupling between services
- Services only share `common` library (events, DTOs)
- Can move to polyrepo later without refactoring

**Docker builds:**
- Each service only copies: `common` + itself
- No need to know about other services
- Faster, isolated builds

### Consequences

**Positive:**
- ✅ True microservices isolation
- ✅ Can build each service separately
- ✅ Easy to move to separate repositories
- ✅ No coupling between services

**Trade-offs:**
- Need to `mvn install` common first (or in Docker build)
- Shared configuration duplicated across services
- Root POM is optional (for convenience only)

---

## ADR-003: Event Immutability

**Date:** 2026-02-13  
**Status:** Accepted

### Decision

All events MUST be immutable.

### Implementation

Using Java 21 Records:
```java
public record DocumentUploadedEvent(
    UUID eventId,
    String eventType,
    UUID aggregateId,
    Instant timestamp,
    String documentName,
    Long fileSize
) implements BaseEvent { }
```

### Rationale

**EDA Best Practices:**
1. Events represent **facts that happened** - they can't change
2. Events may be stored in event store - immutability ensures consistency
3. Events may be replayed - immutability ensures correctness
4. Events may be shared across threads - immutability ensures safety

**Why Records over Lombok @Value:**
- Records are Java-native (no library dependency for events)
- Clearer intent (explicitly immutable)
- Better IDE support (Java 21+)
- More concise syntax

### Consequences

**Positive:**
- ✅ Thread-safe event handling
- ✅ Can't accidentally modify events
- ✅ Safe to share events between services
- ✅ Compiler enforces immutability

**Guidelines:**
- All events extend sealed interface `BaseEvent`
- All events use Record syntax
- DTOs use Lombok (different requirements)

---

## ADR-004: Maven in Docker Builds

**Date:** 2026-02-13  
**Status:** Accepted

### Decision

Use **official Maven Docker image** for building services, not Maven Wrapper.

#### Dockerfile Structure

```dockerfile
# Build stage - Use official Maven image
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

# Copy only what's needed: common + this service
COPY common/pom.xml common/pom.xml
COPY common/src common/src
COPY ingestion-service/pom.xml ingestion-service/pom.xml
COPY ingestion-service/src ingestion-service/src

# Maven is pre-installed - just run it
RUN mvn clean install -f common/pom.xml -DskipTests -B
RUN mvn clean package -f ingestion-service/pom.xml -DskipTests -B

# Runtime stage - minimal JRE
FROM eclipse-temurin:21-jre-alpine
COPY --from=builder /app/ingestion-service/target/*.jar app.jar
```

### Rationale

**Why NOT Maven Wrapper in Docker:**
- ❌ Downloads Maven every build (slow)
- ❌ Extra files to copy (`.mvn/`, `mvnw`, `mvnw.cmd`)
- ❌ Unnecessary complexity in Docker context

**Why Official Maven Image:**
- ✅ Maven pre-installed (faster builds)
- ✅ Clean Dockerfile (only copy pom + src)
- ✅ Better layer caching
- ✅ Production-grade standard practice

**Maven Wrapper still used for:**
- ✅ Local development (`./mvnw clean install`)
- ✅ Ensures consistent Maven version across team
- ✅ No need to install Maven locally

### Consequences

**Development workflow:**
```bash
# Local development - use wrapper
./mvnw clean install

# Docker build - uses official image
docker compose up --build
```

**Benefits:**
- Faster Docker builds (no Maven download)
- Cleaner Dockerfiles
- Standard practice for production builds

**Trade-offs:**
- Maven version specified in two places:
  - `.mvn/wrapper/maven-wrapper.properties` (local)
  - `Dockerfile` base image (Docker)
- Need to keep versions in sync

---

## Summary

**Guiding Principle:**
> Use modern features to **enforce architectural patterns**, not for novelty.

**Focus:**
- EDA patterns (Outbox, Idempotency, Event-driven workflows)
- Type safety where it matters (events)
- Familiarity where it helps (DTOs, entities)
- Best practices always win over trendy code
