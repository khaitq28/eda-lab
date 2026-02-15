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

Key patterns:
- Event-driven read model
- Eventually consistent projection

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
| **Transactional Outbox** | ✅ Implemented | **Critical** | ingestion-service, validation-service | Ensures at-least-once delivery |
| **Idempotent Consumer** | ✅ Implemented | **Critical** | validation-service | Uses processed_events table |
| **Retry + Exponential Backoff** | ✅ Implemented | **Critical** | validation-service (consumer), both services (outbox publisher) | 5 retries for consumer, 10 for publisher |
| **Dead Letter Queue (DLQ)** | ✅ Implemented | **Critical** | validation-service | Routes failed messages after retries |
| **At-Least-Once Delivery** | ✅ Implemented | **Critical** | All services | Guaranteed by Outbox + Idempotency |
| **Eventual Consistency** | ✅ Implemented | **Critical** | System-wide | Services update asynchronously |
| **Business vs Technical Failures** | ✅ Implemented | **High** | validation-service | Business failures don't retry |
| **Saga Pattern (Compensation)** | ❌ Not Implemented | **High** | - | Future: For rollback scenarios |
| **Event Sourcing** | ❌ Not Implemented | **Medium** | - | Out of scope for this project |
| **CQRS** | ❌ Not Implemented | **Medium** | - | Partially: audit-service as read model |

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
- validation-service consumer: Retries technical failures
- Both outbox publishers: Retry failed publishes

**Key Distinction:**
- Technical failures: Retry (DB down, network timeout)
- Business failures: Don't retry (invalid format, validation rules)

### 7.4 Multi-Instance Safety (⚠️ Single Instance Only)

**Current Limitation:**
- Services assume single instance deployment
- Multiple instances would cause duplicate processing in Outbox Publisher

**Future Enhancement (Production):**
- Use `SELECT FOR UPDATE SKIP LOCKED` for Outbox Publisher
- Distributed locking (Redis, Database advisory locks)
- Leader election (only leader publishes)

See Section 9 for details.

---

## 8. Lessons Learned

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

---

## 9. Scalability and Multi-Instance Deployment

### 9.1 Current State (Single Instance)

**Works correctly with:**
- ✅ 1 instance of each service

**Assumptions:**
- Only one instance polls outbox_events table
- Only one instance publishes pending events

### 9.2 Multi-Instance Issues

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

### 9.3 Solutions for Multi-Instance Safety

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

### 9.4 Implementation Recommendation

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

### 9.5 Consumer Idempotency (Already Safe) ✅

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

## 10. Testing Strategy

### 10.1 Local Development

- Docker Compose is used to run:
  - RabbitMQ
  - PostgreSQL for each service
  - All services
