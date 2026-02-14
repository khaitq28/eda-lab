# Consumer Implementation Guide - Validation Service

## Overview

This guide explains the **production-grade consumer implementation** in the Validation Service, demonstrating EDA best practices for reliable message consumption.

The implementation covers three critical reliability patterns:
1. **Idempotent Consumer**: Prevents duplicate processing in at-least-once delivery
2. **Retry with Exponential Backoff**: Handles transient failures gracefully
3. **Dead Letter Queue (DLQ)**: Captures permanently failed messages for investigation

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│ Ingestion Service                                       │
│ - Publishes DocumentUploaded to doc.events exchange    │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
            ┌────────────────┐
            │ RabbitMQ       │
            │ Exchange: doc.events (topic)                │
            │   └─> routing key: document.uploaded       │
            │       └─> Queue: document.uploaded.q       │
            └────────────────┬───────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│ Validation Service Consumer                            │
│                                                         │
│ 1. Receive message from queue                          │
│ 2. Extract eventId (messageId)                         │
│ 3. Check processed_events table (idempotency)          │
│ 4. If already processed => ACK and skip                │
│ 5. If new => INSERT into processed_events              │
│ 6. Process business logic (validate document)          │
│ 7. On success => Commit transaction + ACK              │
│ 8. On failure => Rollback + Retry (exponential)        │
│                                                         │
│ Retry Schedule (5 attempts):                           │
│ - Attempt 1: Immediate                                 │
│ - Attempt 2: +1s delay                                 │
│ - Attempt 3: +2s delay                                 │
│ - Attempt 4: +4s delay                                 │
│ - Attempt 5: +8s delay                                 │
│                                                         │
│ After 5 attempts => Message goes to DLQ                │
└─────────────────────────────────────────────────────────┘
                     │
                     ▼ (on failure after retries)
            ┌────────────────┐
            │ Dead Letter Queue                           │
            │ Exchange: doc.dlx (direct)                  │
            │   └─> Queue: document.uploaded.dlq         │
            │                                             │
            │ Manual inspection & reprocessing by ops    │
            └─────────────────────────────────────────────┘
```

---

## Components

### 1. RabbitMQ Configuration (`RabbitMQConfig.java`)

**Purpose**: Declares RabbitMQ topology and configures retry behavior.

#### Topology Declared

```java
// Main exchange (shared with ingestion-service)
TopicExchange: doc.events (durable)

// Main consumer queue
Queue: document.uploaded.q
  - Durable: true
  - Dead Letter Exchange: doc.dlx
  - Dead Letter Routing Key: document.uploaded.dlq
  - TTL: 7 days
  - Max Length: 10,000 messages

// Binding
document.uploaded.q -> doc.events (routing key: document.uploaded)

// Dead Letter Exchange
DirectExchange: doc.dlx (durable)

// Dead Letter Queue
Queue: document.uploaded.dlq (durable)

// DLQ Binding
document.uploaded.dlq -> doc.dlx (routing key: document.uploaded.dlq)
```

#### Retry Configuration

```java
@Bean
public RetryOperationsInterceptor retryInterceptor() {
    return RetryInterceptorBuilder.stateless()
        .maxAttempts(5)                    // Total 5 attempts
        .backOffOptions(
            1000,   // initialInterval: 1 second
            2.0,    // multiplier: exponential backoff
            10000   // maxInterval: cap at 10 seconds
        )
        .recoverer(new RejectAndDontRequeueRecoverer())  // Go to DLQ after retries
        .build();
}
```

**Key Design Decisions**:
- **Stateless Retry**: No state stored between retries (simpler, works across restarts)
- **Exponential Backoff**: Prevents overwhelming failing systems
- **RejectAndDontRequeueRecoverer**: After retries, message goes to DLX (not back to main queue)
- **AUTO Acknowledgment**: Spring handles ACK/NACK based on method success/exception

---

### 2. Idempotency Table (`processed_events`)

**Purpose**: Tracks which events have been processed to prevent duplicates.

#### Schema

```sql
CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,           -- Ensures uniqueness
    processed_at TIMESTAMP NOT NULL,     -- Audit trail
    event_type VARCHAR(100),              -- For monitoring
    aggregate_id UUID                     -- For correlation
);

CREATE INDEX idx_processed_events_processed_at ON processed_events(processed_at);
CREATE INDEX idx_processed_events_aggregate_id ON processed_events(aggregate_id);
```

#### Why This Design?

1. **event_id as PRIMARY KEY**: Database enforces uniqueness (no duplicates possible)
2. **Minimal Schema**: Only what's needed for idempotency (fast inserts/lookups)
3. **Optional Metadata**: event_type and aggregate_id for debugging/monitoring
4. **Indexes**: Support monitoring queries and archival

---

### 3. ProcessedEvent Entity & Repository

**Entity**:
```java
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {
    @Id
    private UUID eventId;
    private Instant processedAt;
    private String eventType;
    private UUID aggregateId;
}
```

**Repository**:
```java
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
    boolean existsById(UUID eventId);  // Idempotency check
    long countProcessedBetween(Instant start, Instant end);  // Monitoring
    long deleteByProcessedAtBefore(Instant cutoff);  // Archival
}
```

---

### 4. DocumentUploadedConsumer

**Purpose**: Consumes DocumentUploaded events with idempotency, retry, and DLQ.

#### Message Flow

```java
@RabbitListener(queues = RabbitMQConfig.DOCUMENT_UPLOADED_QUEUE)
@Transactional
public void handleDocumentUploaded(Message message) {
    // 1. Extract eventId from message properties
    UUID eventId = UUID.fromString(message.getMessageProperties().getMessageId());
    
    // 2. Idempotency check
    if (processedEventRepository.existsById(eventId)) {
        log.info("Event already processed (idempotent skip): eventId={}", eventId);
        return;  // ACK and skip
    }
    
    // 3. Mark as processed FIRST (within transaction)
    processedEventRepository.save(ProcessedEvent.of(eventId, eventType, aggregateId));
    
    // 4. Parse event payload
    DocumentUploadedEvent event = objectMapper.readValue(payloadJson, DocumentUploadedEvent.class);
    
    // 5. Business logic
    validateDocument(event.aggregateId(), event.documentName());
    
    // 6. Transaction commits => ACK message
}
```

#### Why Insert BEFORE Processing?

**Critical Pattern**: Always insert into `processed_events` BEFORE executing business logic.

**Reasoning**:
- **If processing succeeds**: Both insert and business logic commit together ✅
- **If processing fails**: Transaction rolls back (including insert), retry will attempt again ✅
- **If insert succeeds but processing fails**: Next retry sees event as processed, skips it ❌ **WRONG!**

**Correct Order**:
```java
// ✅ CORRECT
processedEventRepository.save(processedEvent);  // Step 1
validateDocument(documentId);                   // Step 2
// Both in same @Transactional method
```

**Incorrect Order**:
```java
// ❌ WRONG
validateDocument(documentId);                   // Step 1
processedEventRepository.save(processedEvent);  // Step 2
// If Step 1 fails, Step 2 never executes, retry will process again (OK)
// But if Step 1 succeeds and Step 2 fails, we lose the event! (BAD)
```

#### Simulated Validation Logic

For testing purposes, the consumer uses a simple rule:
- **If documentId ends with even hex digit (0,2,4,6,8,a,c,e)** => VALIDATED ✅
- **If documentId ends with odd hex digit (1,3,5,7,9,b,d,f)** => Technical failure ❌ (triggers retry/DLQ)

```java
private void validateDocument(UUID documentId, String documentName) {
    String uuidStr = documentId.toString().replace("-", "");
    char lastChar = uuidStr.charAt(uuidStr.length() - 1);
    int lastDigit = Character.digit(lastChar, 16);
    
    if (lastDigit % 2 == 0) {
        log.info("Document VALIDATED: documentId={}", documentId);
        // TODO: Emit DocumentValidated event (next step)
    } else {
        log.error("Simulated validation technical failure: documentId={}", documentId);
        throw new RuntimeException("Simulated validation technical failure");
    }
}
```

---

## Observability

### Logging Levels

The consumer logs at different levels for different scenarios:

```java
// INFO: Normal operations
log.info("Received message: eventId={}, eventType={}, aggregateId={}", ...);
log.info("Event already processed (idempotent skip): eventId={}", eventId);
log.info("Document VALIDATED: documentId={}", documentId);
log.info("Successfully processed DocumentUploaded event: eventId={}", eventId);

// DEBUG: Detailed processing steps
log.debug("Marked event as processed: eventId={}", eventId);
log.debug("Validating document: documentId={}", documentName);

// ERROR: Failures (will retry or go to DLQ)
log.error("Message received without messageId. Cannot ensure idempotency.");
log.error("Simulated validation technical failure: documentId={}", documentId);
log.error("Error processing DocumentUploaded event (will retry if attempts remain)", e);
```

### Log Patterns to Monitor

**Success Pattern**:
```
INFO  - Received message: eventId=abc123, eventType=DocumentUploaded, aggregateId=doc456
DEBUG - Marked event as processed: eventId=abc123
DEBUG - Validating document: documentId=doc456, documentName=test.pdf
INFO  - Document VALIDATED: documentId=doc456
INFO  - Successfully processed DocumentUploaded event: eventId=abc123, documentId=doc456
```

**Idempotent Skip Pattern**:
```
INFO - Received message: eventId=abc123, eventType=DocumentUploaded, aggregateId=doc456
INFO - Event already processed (idempotent skip): eventId=abc123, aggregateId=doc456
```

**Retry Pattern**:
```
INFO  - Received message: eventId=xyz789, eventType=DocumentUploaded, aggregateId=doc999
DEBUG - Marked event as processed: eventId=xyz789
DEBUG - Validating document: documentId=doc999, documentName=test.pdf
ERROR - Simulated validation technical failure: documentId=doc999
ERROR - Error in listener (will retry if attempts remain)
... (1 second delay)
INFO  - Received message: eventId=xyz789, eventType=DocumentUploaded, aggregateId=doc999
INFO  - Event already processed (idempotent skip): eventId=xyz789, aggregateId=doc999
```

**Wait, why idempotent skip on retry?**

Because we inserted into `processed_events` BEFORE the failure! This is actually a **bug in the current implementation** for technical failures.

**Fix**: We need to handle this more carefully. See "Production Improvements" below.

---

## Testing

### 1. Start the System

```bash
# Terminal 1: Start infrastructure
cd /Users/quangkhai/Desktop/DATA/WORKSPACE/eda-lab
docker compose -f docker-compose.infra.yml up

# Terminal 2: Build and start services
docker compose up --build
```

### 2. Upload a Document (via Ingestion Service)

```bash
# Upload document with even-ending UUID (will validate successfully)
curl -X POST http://localhost:8081/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{
    "name": "test-validation.pdf",
    "contentType": "application/pdf",
    "fileSize": 1024000,
    "metadata": {"test": "validation"},
    "uploadedBy": "test-user"
  }'
```

**Expected Response**:
```json
{
  "id": "12345678-1234-1234-1234-123456789abc",  // Note: ends with 'c' (even)
  "name": "test-validation.pdf",
  ...
}
```

### 3. Check Validation Service Logs

```bash
docker logs validation-service -f
```

**Expected Logs**:
```
INFO  - Received message: eventId=..., eventType=DocumentUploaded, aggregateId=12345678-...
DEBUG - Marked event as processed: eventId=...
DEBUG - Validating document: documentId=12345678-..., documentName=test-validation.pdf
INFO  - Document VALIDATED: documentId=12345678-...
INFO  - Successfully processed DocumentUploaded event: eventId=...
```

### 4. Verify Idempotency Table

```bash
# Connect to validation database
docker exec -it validation-db psql -U postgres -d validation_db

# Check processed events
SELECT event_id, event_type, aggregate_id, processed_at 
FROM processed_events 
ORDER BY processed_at DESC 
LIMIT 5;
```

**Expected Result**:
```
event_id                             | event_type        | aggregate_id                         | processed_at
-------------------------------------|-------------------|--------------------------------------|-------------------------
<event-id-uuid>                      | DocumentUploaded  | <document-id-uuid>                   | 2026-02-14 10:30:00+00
```

### 5. Test Idempotency (Duplicate Message)

**Simulate duplicate delivery**:
```bash
# Republish the same message from RabbitMQ Management UI
# 1. Go to http://localhost:15672
# 2. Login: guest/guest
# 3. Queues -> document.uploaded.q -> "Get messages"
# 4. Copy the message
# 5. Exchanges -> doc.events -> "Publish message"
# 6. Paste the message and publish
```

**Expected Logs**:
```
INFO - Received message: eventId=<same-event-id>, ...
INFO - Event already processed (idempotent skip): eventId=<same-event-id>
```

**No duplicate processing!** ✅

### 6. Test Retry Logic

To test retry with exponential backoff, we need to trigger a technical failure. Unfortunately, our current simulation makes this tricky because we insert into `processed_events` before the failure.

**Better approach** (see "Production Improvements" below): Separate idempotency check from processing.

---

## Testing Retry and DLQ (Manual Simulation)

Since our current implementation has the idempotency issue, let's test retry/DLQ by temporarily modifying the code:

### Option 1: Comment Out Idempotency Insert

```java
// Temporarily comment out this line
// processedEventRepository.save(processedEvent);

// Now processing will retry without idempotency skip
validateDocument(documentId, documentName);
```

### Option 2: Use RabbitMQ Management UI

1. **Publish a message directly to the queue** (bypassing ingestion-service):

```bash
# Go to RabbitMQ Management UI: http://localhost:15672
# Exchanges -> doc.events -> Publish message

# Payload:
{
  "eventId": "00000000-0000-0000-0000-000000000001",
  "eventType": "DocumentUploaded",
  "aggregateId": "00000000-0000-0000-0000-000000000001",
  "timestamp": "2026-02-14T10:00:00Z",
  "documentName": "test-retry.pdf",
  "contentType": "application/pdf",
  "fileSize": 1024
}

# Properties:
# - message_id: 00000000-0000-0000-0000-000000000001
# - content_type: application/json
# - headers:
#   - eventType: DocumentUploaded
#   - aggregateId: 00000000-0000-0000-0000-000000000001

# Routing key: document.uploaded
```

**Note**: The UUID ends with '1' (odd), so it will trigger technical failure.

**Expected Logs**:
```
INFO  - Received message: eventId=00000000-...-0001, ...
ERROR - Simulated validation technical failure: documentId=00000000-...-0001
ERROR - Error in listener (will retry if attempts remain)
... (1 second delay)
INFO  - Received message: eventId=00000000-...-0001, ...
ERROR - Simulated validation technical failure: documentId=00000000-...-0001
... (2 second delay)
INFO  - Received message: eventId=00000000-...-0001, ...
ERROR - Simulated validation technical failure: documentId=00000000-...-0001
... (4 second delay)
INFO  - Received message: eventId=00000000-...-0001, ...
ERROR - Simulated validation technical failure: documentId=00000000-...-0001
... (8 second delay)
INFO  - Received message: eventId=00000000-...-0001, ...
ERROR - Simulated validation technical failure: documentId=00000000-...-0001
ERROR - Retries exhausted, sending to DLQ
```

### Check DLQ

```bash
# RabbitMQ Management UI: http://localhost:15672
# Queues -> document.uploaded.dlq
# Should have 1 message
```

---

## Production Improvements

### 1. Fix Idempotency + Retry Issue

**Problem**: Current implementation inserts into `processed_events` before processing, so retries are skipped.

**Solution**: Use a two-phase approach:

```java
@RabbitListener(queues = RabbitMQConfig.DOCUMENT_UPLOADED_QUEUE)
@Transactional
public void handleDocumentUploaded(Message message) {
    UUID eventId = extractEventId(message);
    
    // Phase 1: Idempotency check (read-only)
    if (processedEventRepository.existsById(eventId)) {
        log.info("Event already processed (idempotent skip): eventId={}", eventId);
        return;
    }
    
    // Phase 2: Process + mark as processed (transactional)
    try {
        // Business logic
        validateDocument(documentId, documentName);
        
        // Mark as processed AFTER successful processing
        processedEventRepository.save(ProcessedEvent.of(eventId, eventType, aggregateId));
        
    } catch (BusinessValidationException e) {
        // Business failures: Don't retry, emit DocumentRejected
        log.warn("Business validation failed: {}", e.getMessage());
        // TODO: Emit DocumentRejected event
        // Mark as processed (don't retry business failures)
        processedEventRepository.save(ProcessedEvent.of(eventId, eventType, aggregateId));
        
    } catch (Exception e) {
        // Technical failures: Retry (don't mark as processed)
        log.error("Technical error (will retry): {}", e.getMessage(), e);
        throw e;  // Trigger retry
    }
}
```

**Key Changes**:
- **Idempotency check first** (read-only, outside transaction)
- **Mark as processed AFTER success** (or after business failure)
- **Don't mark as processed on technical failure** (allow retry)
- **Distinguish business vs. technical failures**

### 2. Add Metrics

```java
@Component
public class ConsumerMetrics {
    private final MeterRegistry meterRegistry;
    
    public void recordMessageProcessed(String eventType, boolean success) {
        meterRegistry.counter("consumer.messages.processed", 
            "eventType", eventType,
            "success", String.valueOf(success)
        ).increment();
    }
    
    public void recordIdempotentSkip(String eventType) {
        meterRegistry.counter("consumer.messages.idempotent_skip",
            "eventType", eventType
        ).increment();
    }
    
    public void recordRetry(String eventType, int attempt) {
        meterRegistry.counter("consumer.messages.retry",
            "eventType", eventType,
            "attempt", String.valueOf(attempt)
        ).increment();
    }
}
```

### 3. Add Health Indicator

```java
@Component
public class ConsumerHealthIndicator implements HealthIndicator {
    private final ProcessedEventRepository processedEventRepository;
    
    @Override
    public Health health() {
        long recentCount = processedEventRepository.countProcessedBetween(
            Instant.now().minus(1, ChronoUnit.HOURS),
            Instant.now()
        );
        
        return Health.up()
            .withDetail("processedLastHour", recentCount)
            .build();
    }
}
```

### 4. Add Distributed Tracing

```java
@RabbitListener(queues = RabbitMQConfig.DOCUMENT_UPLOADED_QUEUE)
@Transactional
public void handleDocumentUploaded(Message message) {
    // Extract trace context from headers
    String traceId = (String) message.getMessageProperties().getHeader("X-Trace-Id");
    String spanId = (String) message.getMessageProperties().getHeader("X-Span-Id");
    
    // Set MDC for logging
    MDC.put("traceId", traceId);
    MDC.put("spanId", spanId);
    
    try {
        // Process message
        ...
    } finally {
        MDC.clear();
    }
}
```

### 5. Archival Strategy

```java
@Component
public class ProcessedEventArchivalJob {
    private final ProcessedEventRepository processedEventRepository;
    
    @Scheduled(cron = "0 0 2 * * *")  // Daily at 2 AM
    @Transactional
    public void archiveOldEvents() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        long deleted = processedEventRepository.deleteByProcessedAtBefore(cutoff);
        log.info("Archived {} old processed events (older than 30 days)", deleted);
    }
}
```

---

## Best Practices Summary

### ✅ DO

1. **Always check idempotency** before processing
2. **Use AUTO acknowledgment** with `@Transactional` for simplicity
3. **Log comprehensively** (received, processed, skipped, failed)
4. **Use exponential backoff** for retries
5. **Route to DLQ** after retries exhausted
6. **Distinguish business vs. technical failures**
7. **Add metrics and monitoring**
8. **Archive old processed_events** to prevent table bloat
9. **Use messageId for eventId** (RabbitMQ provides this)
10. **Test idempotency** by replaying messages

### ❌ DON'T

1. **Don't retry business validation failures** (emit rejection event instead)
2. **Don't requeue to main queue** after retries (use DLQ)
3. **Don't ignore duplicate messages** (always check idempotency)
4. **Don't process without eventId** (reject messages without messageId)
5. **Don't use fixed delay retry** (use exponential backoff)
6. **Don't let processed_events grow unbounded** (archive old records)
7. **Don't swallow exceptions** (log and rethrow for retry)
8. **Don't mix business and technical errors** (handle separately)

---

## Next Steps

1. **Emit Events**: Implement DocumentValidated/DocumentRejected event publishing
2. **Add Transactional Outbox**: Use outbox pattern for event publishing (like ingestion-service)
3. **Implement Enrichment Consumer**: Apply same patterns in enrichment-service
4. **Add Metrics**: Integrate Micrometer for observability
5. **Add Distributed Tracing**: Implement correlation IDs across services
6. **Load Testing**: Test with high message volume
7. **Chaos Engineering**: Test failure scenarios (DB down, RabbitMQ down, etc.)

---

## References

- **Idempotent Consumer Pattern**: https://microservices.io/patterns/communication-style/idempotent-consumer.html
- **Spring AMQP Retry**: https://docs.spring.io/spring-amqp/reference/amqp/resilience-recovering-from-errors-and-broker-failures.html
- **Dead Letter Exchanges**: https://www.rabbitmq.com/dlx.html
- **Exponential Backoff**: https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
