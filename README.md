# EDA Lab - Event-Driven Architecture Learning Platform

A learning and reference implementation of Event-Driven Architecture (EDA) using Java 21 and Spring Boot 3.x.

## Architecture Overview

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Ingestion     │────▶│   Validation    │────▶│   Enrichment    │
│    Service      │     │    Service      │     │    Service      │
└────────┬────────┘     └────────┬────────┘     └────────┬────────┘
         │                       │                       │
         │        RabbitMQ       │                       │
         └───────────┬───────────┴───────────────────────┘
                     │
                     ▼
              ┌─────────────────┐
              │     Audit       │
              │    Service      │
              └─────────────────┘
```

## Services

| Service            | Port | Description                                    |
|--------------------|------|------------------------------------------------|
| ingestion-service  | 8081 | REST API for document submission               |
| validation-service | 8082 | Validates documents, emits validated/rejected  |
| enrichment-service | 8083 | Enriches validated documents                   |
| audit-service      | 8084 | Stores immutable audit log of all events       |

## Infrastructure

| Component          | Port(s)      | Description                |
|--------------------|--------------|----------------------------|
| RabbitMQ           | 5672, 15672  | Message broker + Management UI |
| postgres-ingestion | 5433         | Database for ingestion     |
| postgres-validation| 5434         | Database for validation    |
| postgres-enrichment| 5435         | Database for enrichment    |
| postgres-audit     | 5436         | Database for audit         |

## Quick Start

### Prerequisites

- Docker & Docker Compose
- Java 21 (for local development)
- Maven 3.9+ (or use included wrapper)

### Option 1: Run Everything with Docker Compose

```bash
# Build and start all services
docker compose up --build -d

# Check status
docker compose ps

# View logs
docker compose logs -f

# Stop everything
docker compose down
```

### Option 2: Run Infrastructure + Services from IDE

```bash
# Start only infrastructure (RabbitMQ + Databases)
docker compose -f docker-compose.infra.yml up -d

# Then run services from your IDE with these settings:
# - Ingestion:  DB_HOST=localhost DB_PORT=5433
# - Validation: DB_HOST=localhost DB_PORT=5434
# - Enrichment: DB_HOST=localhost DB_PORT=5435
# - Audit:      DB_HOST=localhost DB_PORT=5436
```

### Build from Source

```bash
# Build all modules
./mvnw clean package -DskipTests

# Build specific service
./mvnw clean package -pl ingestion-service -am -DskipTests
```

## Health Check Endpoints

Once running, verify services are healthy:

```bash
# Check each service
curl http://localhost:8081/api/health  # Ingestion
curl http://localhost:8082/api/health  # Validation
curl http://localhost:8083/api/health  # Enrichment
curl http://localhost:8084/api/health  # Audit

# Spring Actuator endpoints
curl http://localhost:8081/actuator/health
```

## RabbitMQ Management

Access RabbitMQ Management UI at: http://localhost:15672

- Username: `guest`
- Password: `guest`

## Project Structure

```
eda-lab/
├── pom.xml                          # Parent POM
├── docker-compose.yml               # Full stack (infra + services)
├── docker-compose.infra.yml         # Infrastructure only
├── common/                          # Shared DTOs, events, utilities
│   ├── pom.xml
│   └── src/main/java/
├── ingestion-service/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
│       ├── main/
│       │   ├── java/
│       │   └── resources/
│       │       ├── application.yml
│       │       └── db/migration/
│       └── test/
├── validation-service/
├── enrichment-service/
└── audit-service/
```

## Key Patterns Implemented

- **Transactional Outbox**: Reliable event publishing with database-first approach
- **Idempotent Consumer**: Inbox pattern to handle duplicate messages
- **Retry + DLQ**: Resilient message processing with dead letter queues
- **Database per Service**: Data isolation and independent deployability

## Configuration

All services use environment variables for configuration:

| Variable         | Default    | Description              |
|------------------|------------|--------------------------|
| DB_HOST          | localhost  | PostgreSQL host          |
| DB_PORT          | 5432       | PostgreSQL port          |
| DB_NAME          | (varies)   | Database name            |
| DB_USER          | postgres   | Database username        |
| DB_PASSWORD      | postgres   | Database password        |
| RABBITMQ_HOST    | localhost  | RabbitMQ host            |
| RABBITMQ_PORT    | 5672       | RabbitMQ port            |
| RABBITMQ_USER    | guest      | RabbitMQ username        |
| RABBITMQ_PASSWORD| guest      | RabbitMQ password        |
| SERVER_PORT      | (varies)   | HTTP server port         |

## Java 21 Features

This project uses modern Java 21 features for clean, safe, and performant code:

- ✅ **Records** - Immutable events and DTOs
- ✅ **Sealed Classes** - Type-safe event hierarchy
- ✅ **Pattern Matching** - Exhaustive event handling
- ✅ **Virtual Threads** - High-throughput I/O operations

See [JAVA21_FEATURES.md](JAVA21_FEATURES.md) for detailed examples and best practices.

## License

This project is for educational purposes.
