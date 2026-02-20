# Project: EDA Document Processing Platform

## 1. Goal

This project is a learning and reference implementation of an Event-Driven Architecture (EDA) using Java and Spring Boot, focusing on real-world enterprise patterns:

- Event-Driven Microservices
- Transactional Outbox pattern for reliable event publishing
- Idempotent Consumers
- Retry and Dead Letter Queue (DLQ)
- Asynchronous, eventually consistent workflows
- Integration testing with Testcontainers
- Observability of message flows and data state

The business domain is intentionally simple. The primary objective is to learn and demonstrate architecture and reliability patterns used in large-scale systems (banking, insurance, enterprise platforms).

---

## 2. Tech Stack

- Java 21
- Spring Boot 3.x
- RabbitMQ (message broker)
- PostgreSQL (one database per service)
- Docker & Docker Compose (local development and system runs)
- Testcontainers (integration testing)
- Flyway (database migrations)
- Maven (build tool)

---

## 3. Architecture Principles

- Microservices architecture
- Each service owns its own database (no shared database)
- No synchronous REST calls between services
- All inter-service communication is done via events (RabbitMQ)
- Services are loosely coupled and communicate asynchronously
- The system is eventually consistent
- Reliability patterns are mandatory:
  - Producers use the Transactional Outbox pattern
  - Consumers must be idempotent
  - Retry with backoff must be configured for message consumption
  - Dead Letter Queue (DLQ) must be used for poison messages
- Events are integration events, not internal domain events
- The database remains the source of truth for each service

---

## 4. Services

### 4.1 Ingestion Service

Responsibilities:
- Expose a REST API to submit a new Document
- Persist the Document in its own database
- Create an Outbox event in the same transaction
- Publish `DocumentUploaded` events to RabbitMQ via an Outbox publisher

Key patterns:
- Transactional Outbox
- Reliable event publishing

---

### 4.2 Validation Service

Responsibilities:
- Consume `DocumentUploaded` events
- Validate the document (simulated business rules)
- Be idempotent (ignore already processed events)
- On success, emit `DocumentValidated`
- On business failure, emit `DocumentRejected`
- On technical failure, rely on retry and eventually DLQ

Key patterns:
- Idempotent Consumer
- Retry with backoff
- Dead Letter Queue

---

### 4.3 Enrichment Service

Responsibilities:
- Consume `DocumentValidated` events
- Perform enrichment (simulated: classification, metadata extraction, etc.)
- Be idempotent
- Emit `DocumentEnriched` events

Key patterns:
- Chained asynchronous processing
- Idempotent Consumer

---

### 4.4 Audit Service

Responsibilities:
- Consume all document-related events:
  - DocumentUploaded
  - DocumentValidated
  - DocumentRejected
  - DocumentEnriched
- Store an immutable audit log of all events
- Act as a read model / observer service
- Provide REST API for querying event history

Key patterns:
- Event-driven read model
- Eventually consistent projection
- Wildcard routing pattern (document.*)

---

### 4.5 Notification Service

Responsibilities:
- Consume document events that require user notification:
  - DocumentValidated
  - DocumentRejected
  - DocumentEnriched
- Send notifications to users (email, SMS, push - simulated)
- Store notification history for audit
- Be idempotent (avoid duplicate notifications)

Key patterns:
- Fan-out pattern (multiple services consume same event)
- Idempotent Consumer
- Retry with backoff
- Dead Letter Queue

---

## 5. Events

The main integration events in the system:

- DocumentUploaded
- DocumentValidated
- DocumentRejected
- DocumentEnriched

Event structure guidelines:
- Each event has:
  - eventId (UUID)
  - eventType
  - aggregateId (documentId)
  - timestamp
  - payload (JSON)
- Events are immutable
- Consumers must assume events can be delivered more than once

---

## 6. Data Ownership

- Each service has its own PostgreSQL database
- No service is allowed to read or write another service's database
- Data is synchronized between services only via events

---

## 7. Reliability Patterns

### Implementation Status

| Pattern | Status | Importance | Services | Notes |
|---------|--------|------------|----------|-------|
| **Transactional Outbox** | ✅ Implemented | **Critical** | ingestion, validation, enrichment | Ensures at-least-once delivery |
| **Idempotent Consumer** | ✅ Implemented | **Critical** | validation, enrichment, audit, notification | Uses processed_events or UNIQUE constraint |
| **Retry + Exponential Backoff** | ✅ Implemented | **Critical** | All consumers, all outbox publishers | 5 retries for consumers, 10 for publishers |
| **Dead Letter Queue (DLQ)** | ✅ Implemented | **Critical** | All consumers | Routes failed messages after retries |
| **At-Least-Once Delivery** | ✅ Implemented | **Critical** | System-wide | Guaranteed by Outbox + Idempotency |
| **Eventual Consistency** | ✅ Implemented | **Critical** | System-wide | Services update asynchronously |
| **Business vs Technical Failures** | ✅ Implemented | **High** | validation-service | Business failures don't retry |
| **Multi-Instance Safety** | ✅ Implemented | **High** | All outbox publishers | SELECT FOR UPDATE SKIP LOCKED |
| **Fan-out Pattern** | ✅ Implemented | **High** | notification, audit | Multiple services consume same event |
| **Wildcard Routing** | ✅ Implemented | **Medium** | audit-service | Consumes all document.* events |
| **Saga Pattern (Compensation)** | ❌ Not Implemented | **High** | - | Future: For rollback scenarios |
| **Event Sourcing** | ❌ Not Implemented | **Medium** | - | Out of scope for this project |
| **CQRS** | ❌ Partial | **Medium** | audit-service | Read model for event history |

### 7.1 Transactional Outbox (✅ Implemented)

**Purpose:** Ensure reliable event publishing even if message broker is down.

**How it works:**
- Business data and Outbox event are stored in the same transaction
- A background publisher process reads pending Outbox records and publishes them to RabbitMQ
- After successful publish, the Outbox record is marked as SENT
- Retry with exponential backoff (10s, 20s, 40s, 80s, ...)

**Implemented in:**
- ingestion-service: Publishes DocumentUploaded
- validation-service: Publishes DocumentValidated, DocumentRejected
- enrichment-service: Publishes DocumentEnriched

**Key Tables:**
- `outbox_events` (status: PENDING, SENT, FAILED)
- Includes retry fields: retry_count, next_retry_at, last_error

### 7.2 Idempotent Consumer (✅ Implemented)

**Purpose:** Handle duplicate message deliveries (at-least-once delivery).

**How it works:**
- Each consumer maintains a "processed_events" table
- Before processing a message, the consumer checks if the eventId was already processed
- If yes, the message is ACKed and ignored safely
- If no, process the message and insert eventId into processed_events

**Implemented in:**
- validation-service: Uses processed_events table
- enrichment-service: Uses processed_events table
- audit-service: Uses UNIQUE constraint on event_id
- notification-service: Uses UNIQUE constraint on event_id

**Critical Design:**
- Check idempotency FIRST (read-only)
- Save to processed_events AFTER successful processing
- Same transaction ensures atomicity

### 7.3 Retry and Dead Letter Queue (DLQ) (✅ Implemented)

**Purpose:** Handle transient failures and isolate poison messages.

**How it works:**
- Message consumption configured with retry and exponential backoff
- Consumer retries: 5 attempts (1s, 2s, 4s, 8s, 10s)
- Outbox publisher retries: 10 attempts (10s, 20s, 40s, 80s, ...)
- After max retries, message sent to Dead Letter Queue
- Operators can inspect DLQ and manually reprocess

**Implemented in:**
- All consumers: Retry technical failures (validation, enrichment, audit, notification)
- All outbox publishers: Retry failed publishes (ingestion, validation, enrichment)

**Key Distinction:**
- Technical failures: Retry (DB down, network timeout)
- Business failures: Don't retry (invalid format, validation rules)

### 7.4 Multi-Instance Safety (✅ Implemented)

**Implementation:**
- ✅ All outbox publishers use `SELECT FOR UPDATE SKIP LOCKED`
- ✅ Each instance locks different rows → no duplicate publishing
- ✅ Consumers are automatically safe (RabbitMQ + idempotency)

**How it works:**
- PostgreSQL row-level locking prevents duplicate event publishing
- Each instance processes different events
- Safe for horizontal scaling

See Section 10 for details.

### 7.5 Fan-out Pattern (✅ Implemented)

**Purpose:** Multiple services consume the same event type.

**Implementation:**
- DocumentValidated → enrichment-service AND notification-service
- DocumentRejected → notification-service AND audit-service
- DocumentEnriched → notification-service AND audit-service

**Benefits:**
- Decoupled services
- Independent scaling
- Easy to add new consumers

### 7.6 Wildcard Routing (✅ Implemented)

**Purpose:** Consume all events matching a pattern.

**Implementation:**
- audit-service binds to `document.*` wildcard
- Receives ALL document events automatically
- No code changes needed for new event types

**Benefits:**
- Complete audit trail
- Future-proof (new events auto-captured)
- Simplified topology

---

## 8. Observability and Traceability

### 8.1 Overview

Full observability implementation for distributed tracing and structured logging across all microservices.

**Status:** ✅ **Fully Implemented**

**Key Components:**
- ✅ JSON structured logging to STDOUT
- ✅ Correlation ID propagation across all services
- ✅ MDC (Mapped Diagnostic Context) for automatic context propagation
- ✅ Consistent logging conventions
- ✅ Ready for log aggregation (ELK + Filebeat - to be added)

### 8.2 JSON Structured Logging

**Purpose:** Machine-readable logs for log aggregation tools (Kibana, Datadog, Splunk, CloudWatch).

**Implementation:**
- Uses `logstash-logback-encoder` for JSON output
- One JSON object per log line
- Includes standard fields: `timestamp`, `level`, `logger`, `message`, `service`
- Includes MDC fields: `correlationId`, `eventId`, `documentId`, `routingKey`, `eventType`

**Example log output:**
```json
{
  "@timestamp": "2026-02-17T23:30:08.197+0000",
  "message": "EVENT_RECEIVED",
  "logger_name": "com.eda.lab.validation.messaging.consumer.DocumentUploadedConsumer",
  "level": "INFO",
  "eventId": "9b25f391-98bd-4583-8f24-bf3ba2dcc878",
  "correlationId": "test-correlation-1771371006",
  "documentId": "b4fa8fc2-5ded-402a-b2e0-e38f817074c0",
  "eventType": "DocumentUploaded",
  "routingKey": "document.uploaded",
  "service": "validation-service"
}
```

**Implemented in:** All 5 services (ingestion, validation, enrichment, audit, notification)

### 8.3 Correlation ID Pattern

**Purpose:** Trace a single request or document flow through all services in the distributed system.

**How it works:**

```
POST /documents (X-Correlation-Id header)
    ↓
ingestion-service (generates if missing, persists in DB)
    ↓ RabbitMQ message headers + payload
validation-service (extracts from headers, includes in outbound events)
    ↓ RabbitMQ message headers + payload
enrichment-service (extracts from headers, includes in outbound events)
    ↓ RabbitMQ message headers + payload
audit-service (extracts from headers, stores in DB)
notification-service (extracts from headers, stores in DB)
```

**Implementation Details:**

| Service | Correlation ID Handling |
|---------|------------------------|
| **ingestion-service** | - Accepts optional `X-Correlation-Id` header<br>- Generates UUID if missing<br>- Persists in `documents.correlation_id`<br>- Includes in `DocumentUploadedEvent` payload<br>- Sets in RabbitMQ message headers |
| **validation-service** | - Extracts from MDC (set by `MessageMdcContext`)<br>- Includes in `DocumentValidated` / `DocumentRejected` payload<br>- Sets in RabbitMQ message headers via OutboxPublisher |
| **enrichment-service** | - Extracts from MDC<br>- Includes in `DocumentEnriched` payload<br>- Sets in RabbitMQ message headers via OutboxPublisher |
| **audit-service** | - Extracts from MDC<br>- Persists in `audit_log.correlation_id` |
| **notification-service** | - Extracts from MDC<br>- Persists in `notification_history.correlation_id` |

**Benefits:**
- ✅ End-to-end tracing of document lifecycle
- ✅ Debug issues by finding all logs for a single correlation ID
- ✅ Identify bottlenecks or failures in the event flow
- ✅ Production-ready for distributed tracing tools

### 8.4 MDC (Mapped Diagnostic Context)

**Purpose:** Automatically include contextual information in every log statement without manual passing.

**How it works:**
- Thread-local storage for log context
- Set once at the entry point (REST request or RabbitMQ message)
- All logs in that thread include the context automatically
- Cleared after processing completes

**Implementation:**

**For REST requests (ingestion-service):**
```java
@Component
public class RequestMdcInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, ...) {
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MdcKeys.CORRELATION_ID, correlationId);
        return true;
    }
    
    @Override
    public void afterCompletion(...) {
        MDC.remove(MdcKeys.CORRELATION_ID);
    }
}
```

**For RabbitMQ consumers (all consumer services):**
```java
try (var mdc = MessageMdcContext.of(message.getMessageProperties())) {
    // All logs in this block include correlationId, eventId, documentId, etc.
    log.info("EVENT_RECEIVED");
    // ... processing ...
    log.info("EVENT_PROCESSED");
}
// MDC automatically cleared here (AutoCloseable)
```

**Key Classes:**
- `MdcKeys`: Centralized constants for MDC keys
- `MessageMdcContext`: AutoCloseable utility for RabbitMQ message context
- `RequestMdcInterceptor`: Spring MVC interceptor for REST requests

### 8.5 Logging Conventions

**Consistent log messages across all services for easier monitoring:**

| Event | Log Level | Message | Context |
|-------|-----------|---------|---------|
| Event received | INFO | `EVENT_RECEIVED` | eventType, routingKey, eventId, correlationId, documentId |
| Event processing | DEBUG | `EVENT_PROCESSING` | eventType, correlationId, documentId |
| Event processed | INFO | `EVENT_PROCESSED` | validationResult, outboxEventId, correlationId |
| Idempotent skip | INFO | `EVENT_SKIPPED_IDEMPOTENT` | eventId, correlationId |
| Outbox publish success | INFO | `OUTBOX_PUBLISH_SUCCESS` | eventId, aggregateId, correlationId |
| Outbox publish failed | ERROR | `OUTBOX_PUBLISH_FAIL` | eventId, aggregateId, retryCount, correlationId |
| Business validation failed | WARN | `BUSINESS_VALIDATION_FAILED` | reason, correlationId, documentId |
| Technical failure | ERROR | `TECHNICAL_FAILURE` | error message, correlationId |
| DLQ routing | ERROR | Retry exhausted, sending to DLQ | eventId, correlationId |

### 8.6 Database Persistence

**Correlation ID is persisted in key tables for long-term traceability:**

| Service | Table | Column |
|---------|-------|--------|
| ingestion-service | `documents` | `correlation_id VARCHAR(255)` |
| audit-service | `audit_log` | `correlation_id VARCHAR(255)` |
| notification-service | `notification_history` | `correlation_id VARCHAR(255)` |

**Indexes created:**
- `idx_documents_correlation_id` on `documents(correlation_id)`
- `idx_audit_log_correlation_id` on `audit_log(correlation_id)`

### 8.7 Log Aggregation — ELK Stack + Filebeat (✅ Implemented)

**Architecture:**
```
Docker Containers (5 services + RabbitMQ + PostgreSQL)
    ↓ STDOUT (JSON logs)
Filebeat (reads Docker container logs, parses JSON)
    ↓
Elasticsearch (single-node, indexes eda-logs-*)
    ↓
Kibana (http://localhost:5601 — search, filter, dashboards)
```

**Components:**

| Component | Image | Purpose | Port |
|-----------|-------|---------|------|
| Elasticsearch | `elasticsearch:8.13.4` | Log storage & search engine | 9200 |
| Kibana | `kibana:8.13.4` | Web UI for searching & visualizing logs | 5601 |
| Filebeat | `filebeat:8.13.4` | Collects & ships container logs to ES | — |

**Filebeat Configuration (`elk/filebeat.yml`):**
- Input: `type: container` — reads from `/var/lib/docker/containers/`
- `decode_json_fields`: parses the JSON log body so fields like `correlationId`, `service`, `level` become searchable ES fields (not plain text)
- `add_docker_metadata`: adds `container.name`, `container.image.name`
- `drop_event`: excludes ELK containers themselves (avoid recursive logging)
- Index pattern: `eda-logs-YYYY.MM.DD` (1 shard, 0 replicas for dev)

**How to Use:**

```bash
# Start everything (services + ELK)
docker compose up -d

# Wait ~60s for Kibana to be ready, then open:
# http://localhost:5601

# In Kibana:
# 1. Go to Management → Data Views → Create data view
# 2. Name: "EDA Logs", Index pattern: "eda-logs-*", Timestamp field: @timestamp
# 3. Go to Discover → select "EDA Logs" data view
```

**Example Kibana Searches (KQL):**
```
correlationId: "test-correlation-123"
service: "validation-service"
level: "ERROR"
documentId: "b4fa8fc2-5ded-402a-b2e0-e38f817074c0"
message: "EVENT_RECEIVED" and service: "enrichment-service"
container.name: "eda-ingestion-service"
```

**Searchable Fields (indexed from JSON logs):**

| Field | Source | Example |
|-------|--------|---------|
| `correlationId` | MDC | `test-correlation-123` |
| `eventId` | MDC | `9b25f391-...` |
| `documentId` | MDC | `b4fa8fc2-...` |
| `service` | logback customField | `validation-service` |
| `level` | logback | `INFO`, `ERROR`, `DEBUG` |
| `message` | logback | `EVENT_RECEIVED` |
| `eventType` | MDC | `DocumentUploaded` |
| `routingKey` | MDC | `document.uploaded` |
| `container.name` | Docker metadata | `eda-validation-service` |

**Memory Notes:**
- Elasticsearch heap: 512 MB (`-Xms512m -Xmx512m`)
- If Docker Desktop runs low on memory, increase in Docker Desktop Settings → Resources → Memory (recommend ≥ 8 GB for full stack)

### 8.8 Testing Observability

**Test script:** `test-observability.sh`

**What it does:**
1. Uploads a document with a test correlation ID
2. Waits for event propagation (10 seconds)
3. Searches Docker logs for the correlation ID across all services
4. Verifies JSON format
5. Verifies document ID appears in all relevant services

**Example usage:**
```bash
./test-observability.sh
```

**Expected output:**
- ✅ Same correlation ID in all services
- ✅ Document ID traceable across services
- ✅ JSON formatted logs
- ✅ Event flow: ingestion → validation → enrichment → audit/notification

**Manual tracing:**
```bash
CORRELATION_ID="your-correlation-id-here"

# Trace across all services
docker logs eda-ingestion-service 2>&1 | grep "$CORRELATION_ID"
docker logs eda-validation-service 2>&1 | grep "$CORRELATION_ID"
docker logs eda-enrichment-service 2>&1 | grep "$CORRELATION_ID"
docker logs eda-audit-service 2>&1 | grep "$CORRELATION_ID"
docker logs eda-notification-service 2>&1 | grep "$CORRELATION_ID"
```

### 8.9 Files Modified for Observability

**Common module:**
- `common/pom.xml` (added logstash-logback-encoder)
- `common/src/main/java/com/eda/lab/common/observability/MdcKeys.java` (new)
- `common/src/main/java/com/eda/lab/common/observability/MessageMdcContext.java` (new)

**All services:**
- `*/pom.xml` (added logstash-logback-encoder)
- `*/src/main/resources/logback-spring.xml` (new)

**Ingestion service:**
- `ingestion-service/src/main/resources/db/migration/V4__add_correlation_id_to_documents.sql` (new)
- `ingestion-service/src/main/java/com/eda/lab/ingestion/config/RequestMdcInterceptor.java` (new)
- `ingestion-service/src/main/java/com/eda/lab/ingestion/config/WebMvcConfig.java` (new)
- Updated: `DocumentService`, `OutboxPublisher`, `Document` entity, `DocumentResponse` DTO

**Validation service:**
- Updated: `DocumentUploadedConsumer`, `OutboxPublisher`

**Enrichment service:**
- Updated: `DocumentValidatedConsumer`, `OutboxPublisher`

**Audit service:**
- `audit-service/src/main/resources/db/migration/V2__add_correlation_id_to_audit_log.sql` (new)
- Updated: `DocumentEventConsumer`, `AuditLog` entity

**Notification service:**
- `notification-service/src/main/resources/db/migration/V2__add_correlation_id_to_notification_history.sql` (new)
- Updated: `DocumentEventConsumer`, `NotificationHistory` entity

**Documentation:**
- `OBSERVABILITY_COMPLETE.md` (comprehensive observability guide)

---

## 9. Lessons Learned

### 8.1 Critical Bugs Found During Development

1. **Idempotency Timing Issue**
   - Problem: Saving to processed_events BEFORE processing
   - Impact: Race condition with retries
   - Fix: Save AFTER successful processing

2. **Missing Outbox Event for Success Path**
   - Problem: DocumentValidated event not created
   - Impact: Enrichment service never notified
   - Fix: Create outbox event for both success and failure

3. **Retry Timing Ignored**
   - Problem: Query didn't check next_retry_at
   - Impact: Retry every 2s instead of exponential backoff
   - Fix: Added `next_retry_at <= now` condition to query

### 8.2 Best Practices Applied

- ✅ Sealed interfaces for type-safe events (Java 21)
- ✅ Records for immutable events (Java 21)
- ✅ Lombok for entities and DTOs
- ✅ Comprehensive indexes on outbox/processed tables
- ✅ Separate business vs technical exception types
- ✅ Extensive logging for observability
- ✅ Configuration externalized to application.yml
- ✅ JSON structured logging with correlation IDs
- ✅ MDC for automatic context propagation
- ✅ Consistent logging conventions across services

---

## 10. Scalability and Multi-Instance Deployment

### 10.1 Current State (Single Instance)

**Works correctly with:**
- ✅ 1 instance of each service

**Assumptions:**
- Only one instance polls outbox_events table
- Only one instance publishes pending events

### 10.2 Multi-Instance Issues

#### Issue 1: Consumer Side (✅ Safe)

**Question:** Can 3 validation-service instances consume the same event?

**Answer:** ✅ **NO - RabbitMQ handles this automatically**

```
RabbitMQ Queue: document.uploaded.q
    ├─ validation-service instance 1 (consumes message A)
    ├─ validation-service instance 2 (consumes message B)
    └─ validation-service instance 3 (consumes message C)
```

**How RabbitMQ distributes messages:**
- Round-robin by default
- Each message delivered to ONE consumer only
- Prefetch count controls how many messages per instance

**Consumer side is SAFE for multiple instances!** ✅

#### Issue 2: Outbox Publisher Side (❌ NOT Safe)

**Question:** Can 3 validation-service instances poll the same outbox table?

**Answer:** ❌ **YES - This causes duplicate publishing**

```
Problem:
┌──────────────────────────────────────────┐
│ outbox_events table                      │
│ id=1, event_id=abc, status=PENDING      │
└──────────────────────────────────────────┘
            ↓         ↓         ↓
    Instance 1   Instance 2   Instance 3
    (polls DB)   (polls DB)   (polls DB)
            ↓         ↓         ↓
    Gets event   Gets event   Gets event
            ↓         ↓         ↓
    Publishes    Publishes    Publishes
            ↓         ↓         ↓
    3 duplicate messages in RabbitMQ! ❌
```

**Outbox publisher is NOT SAFE for multiple instances!** ❌

### 10.3 Solutions for Multi-Instance Safety

#### Solution 1: Database Locking (Recommended) ✅

**Use `SELECT FOR UPDATE SKIP LOCKED`:**

```sql
SELECT * FROM outbox_events 
WHERE status = 'PENDING' 
AND (next_retry_at IS NULL OR next_retry_at <= NOW())
ORDER BY created_at ASC 
LIMIT 50
FOR UPDATE SKIP LOCKED;  -- PostgreSQL 9.5+
```

**How it works:**
```
Instance 1 locks rows 1-50
Instance 2 tries to lock same rows → SKIPPED (locks busy)
Instance 2 locks rows 51-100 instead
Instance 3 locks rows 101-150

Result: Each instance processes different events ✅
```

**Pros:**
- ✅ Simple to implement
- ✅ No external dependencies
- ✅ Works with existing PostgreSQL

**Cons:**
- ❌ Requires PostgreSQL 9.5+ (or equivalent in other DBs)
- ❌ May have performance impact under high contention

#### Solution 2: Distributed Lock (Alternative)

**Use Redis or Database Advisory Locks:**

```java
@Scheduled(fixedDelay = 2000)
public void publishPendingEvents() {
    // Try to acquire lock
    boolean lockAcquired = redisLock.tryLock("outbox-publisher-lock", 5, TimeUnit.SECONDS);
    
    if (!lockAcquired) {
        log.debug("Another instance is publishing, skipping");
        return;
    }
    
    try {
        // Publish events
        ...
    } finally {
        redisLock.unlock("outbox-publisher-lock");
    }
}
```

**Pros:**
- ✅ Works across different databases
- ✅ Fine-grained control

**Cons:**
- ❌ Requires Redis or similar
- ❌ More complex
- ❌ Additional infrastructure

#### Solution 3: Leader Election

**Only leader instance publishes:**

```java
@Component
public class OutboxPublisher {
    private final LeaderElection leaderElection;
    
    @Scheduled(fixedDelay = 2000)
    public void publishPendingEvents() {
        if (!leaderElection.isLeader()) {
            log.debug("Not leader, skipping publish");
            return;
        }
        
        // Only leader publishes
        ...
    }
}
```

**Pros:**
- ✅ Simple logic (only leader works)
- ✅ No database contention

**Cons:**
- ❌ Requires leader election mechanism
- ❌ Single point of failure (until leader re-election)
- ❌ Underutilizes instances

### 10.4 Implementation Recommendation

**For Production:**

1. **Use Solution 1 (SELECT FOR UPDATE SKIP LOCKED)** - Best balance of simplicity and reliability

**Changes needed:**

```java
// In OutboxEventRepository.java
@Query(value = """
    SELECT * FROM outbox_events 
    WHERE status = 'PENDING' 
    AND (next_retry_at IS NULL OR next_retry_at <= :now)
    ORDER BY created_at ASC 
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
List<OutboxEvent> findPendingEventsWithLock(@Param("now") Instant now, @Param("limit") int limit);
```

**Testing Multi-Instance:**

```bash
# Start 3 instances of validation-service
docker compose up --scale validation-service=3

# Upload documents and verify:
# - Only one instance publishes each event
# - No duplicate messages in RabbitMQ
# - All instances share the load
```

### 10.5 Consumer Idempotency (Already Safe) ✅

**Even with multiple instances, idempotency ensures safety:**

```
Instance 1 processes event (eventId=abc)
    ↓
Saves to processed_events ✅

If somehow Instance 2 gets duplicate:
    ↓
Checks processed_events → eventId=abc exists
    ↓
Skips processing (idempotent) ✅
```

**Consumers are safe for multiple instances because:**
- ✅ RabbitMQ round-robin distribution
- ✅ Idempotency check (processed_events)
- ✅ Database uniqueness constraint on event_id

---

## 11. Testing Strategy

### 11.1 Local Development

- Docker Compose is used to run:
  - RabbitMQ
  - PostgreSQL for each service
  - All services
