# Transactional Outbox Implementation - Validation Service

## ✅ Implementation Complete!

The validation-service now implements the **Transactional Outbox pattern** for reliable event publishing, as mandated by PROJECT_CONTEXT.md.

---

## 📊 What Was Implemented

### 1. Database Schema (`V3__create_outbox_events_table.sql`)

**Table: `outbox_events`**
```sql
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    event_id UUID UNIQUE NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    payload_json JSONB NOT NULL,
    status VARCHAR(20) NOT NULL,  -- PENDING, SENT, FAILED
    created_at TIMESTAMP NOT NULL,
    sent_at TIMESTAMP,
    retry_count INTEGER DEFAULT 0,
    last_error TEXT,
    next_retry_at TIMESTAMP
);
```

**Indexes:**
- `idx_outbox_status_created` - For finding pending events
- `idx_outbox_retry` - For retry logic
- `idx_outbox_event_id` - For deduplication
- `idx_outbox_aggregate_id` - For correlation
- `idx_outbox_event_type` - For monitoring

---

### 2. Domain Layer

**Entity: `OutboxEvent.java`**
- JPA entity mapping to `outbox_events` table
- Status enum: PENDING, SENT, FAILED
- Methods: `markAsSent()`, `markAsFailed()`

**Repository: `OutboxEventRepository.java`**
- `findPendingEvents(Pageable)` - Get events to publish
- `findEventsReadyForRetry(Instant, Pageable)` - Retry logic
- Monitoring queries: `countByStatus()`, `countByEventType()`

---

### 3. Modified Consumer (`DocumentUploadedConsumer.java`)

**Now Does TWO Things in SAME Transaction:**

#### Success Path (Document Validated):
```java
@Transactional
public void handleDocumentUploaded(Message message) {
    // 1. Validate document
    validateDocument(...);
    
    // 2. Mark as processed (idempotency)
    processedEventRepository.save(processedEvent);
    
    // 3. Create DocumentValidated event
    DocumentValidatedEvent event = DocumentValidatedEvent.create(...);
    
    // 4. Save to outbox (will be published later)
    OutboxEvent outboxEvent = createOutboxEvent(event, documentId);
    outboxEventRepository.save(outboxEvent);
    
    // Transaction commits: processed_events + outbox_events BOTH saved!
}
```

#### Failure Path (Document Rejected):
```java
catch (BusinessValidationException e) {
    // 1. Mark as processed
    processedEventRepository.save(processedEvent);
    
    // 2. Create DocumentRejected event
    DocumentRejectedEvent event = DocumentRejectedEvent.create(...);
    
    // 3. Save to outbox
    OutboxEvent outboxEvent = createOutboxEvent(event, documentId);
    outboxEventRepository.save(outboxEvent);
    
    // Transaction commits: processed_events + outbox_events BOTH saved!
}
```

---

### 4. Outbox Publisher (`OutboxPublisher.java`)

**Background Job:**
- Runs every 2 seconds (`@Scheduled(fixedDelay = 2000)`)
- Fetches 50 pending events per batch
- Publishes to RabbitMQ exchange `doc.events`
- Routing keys:
  - `document.validated` → DocumentValidated events
  - `document.rejected` → DocumentRejected events

**Retry Logic:**
- Exponential backoff: 10s, 20s, 40s, 80s, 160s, ...
- Max 10 retries
- After 10 retries → Mark as FAILED

**Message Properties:**
- `messageId`: eventId (for consumer idempotency)
- `contentType`: application/json
- `headers`: eventType, aggregateType, aggregateId, publishedAt

---

### 5. Configuration

**`ValidationServiceApplication.java`:**
```java
@SpringBootApplication
@EnableScheduling  // ← Enables OutboxPublisher
public class ValidationServiceApplication { ... }
```

**`application.yml`:**
```yaml
outbox:
  publisher:
    enabled: true
    polling-interval: 2s
    batch-size: 50
    max-retries: 10
    initial-retry-delay: 10s
    max-retry-delay: 1h
```

---

## 🔄 Complete Flow

### Scenario 1: Valid Document

```
1. Ingestion-service publishes DocumentUploaded to RabbitMQ
        ↓
2. Validation-service consumer receives message
        ↓
3. Check idempotency (processed_events)
        ↓
4. Validate document → Success ✅
        ↓
5. @Transactional method:
   - Save to processed_events (idempotency)
   - Create DocumentValidatedEvent
   - Save to outbox_events (status=PENDING)
   - Commit transaction (BOTH saved atomically)
        ↓
6. ACK message to RabbitMQ
        ↓
7. OutboxPublisher (background, every 2s):
   - Finds PENDING event in outbox_events
   - Publishes to RabbitMQ (doc.events, routing: document.validated)
   - Marks as SENT
        ↓
8. Enrichment-service and Audit-service receive DocumentValidated
```

---

### Scenario 2: Invalid Document (Business Failure)

```
1. Ingestion-service publishes DocumentUploaded to RabbitMQ
        ↓
2. Validation-service consumer receives message
        ↓
3. Check idempotency (processed_events)
        ↓
4. Validate document → Fail ❌ (BusinessValidationException)
        ↓
5. @Transactional method (catch block):
   - Save to processed_events (idempotency)
   - Create DocumentRejectedEvent
   - Save to outbox_events (status=PENDING)
   - Commit transaction (BOTH saved atomically)
        ↓
6. ACK message to RabbitMQ (no retry)
        ↓
7. OutboxPublisher (background, every 2s):
   - Finds PENDING event in outbox_events
   - Publishes to RabbitMQ (doc.events, routing: document.rejected)
   - Marks as SENT
        ↓
8. Audit-service receives DocumentRejected
```

---

### Scenario 3: RabbitMQ Down (Reliability Test)

```
1. Document validated successfully
        ↓
2. @Transactional:
   - processed_events: saved ✅
   - outbox_events: saved (status=PENDING) ✅
        ↓
3. OutboxPublisher tries to publish
        ↓
4. RabbitMQ is DOWN ❌
        ↓
5. Publish fails → Increment retry_count
        ↓
6. Wait 10 seconds (exponential backoff)
        ↓
7. Retry → Still down ❌
        ↓
8. Wait 20 seconds
        ↓
9. Retry → Still down ❌
        ↓
... (continues retrying)
        ↓
10. RabbitMQ comes back up ✅
        ↓
11. Next retry succeeds → Mark as SENT ✅
        ↓
12. Event delivered! No data loss! 🎉
```

---

## 🎯 Key Benefits

### ✅ Atomicity
```java
@Transactional
public void handleDocumentUploaded(...) {
    processedEventRepository.save(...);  // Write 1
    outboxEventRepository.save(...);     // Write 2
    // Both commit together or both rollback together
}
```

**Guarantee:** You can NEVER have a situation where:
- Document is marked as processed but event is not stored
- Event is stored but document is not marked as processed

---

### ✅ Reliability (At-Least-Once Delivery)

**Even if RabbitMQ is down:**
- Events are safely stored in database
- OutboxPublisher retries automatically
- Events eventually published when RabbitMQ recovers
- **No data loss!**

---

### ✅ Decoupling

**Consumer logic is clean:**
```java
// Just save to outbox - don't worry about RabbitMQ!
outboxEventRepository.save(outboxEvent);
```

**Publishing is separate:**
- Background job handles publishing
- Retry logic is centralized
- Consumer doesn't need to know about RabbitMQ failures

---

## 📊 Database State Examples

### After Successful Validation

**`processed_events` table:**
```
event_id                              | event_type        | aggregate_id
--------------------------------------|-------------------|------------------
abc-123-...                           | DocumentUploaded  | doc-456-...
```

**`outbox_events` table:**
```
event_id    | event_type         | aggregate_id | status  | sent_at
------------|--------------------|--------------|---------|---------
xyz-789-... | DocumentValidated  | doc-456-...  | SENT    | 2026-...
```

---

### After Business Validation Failure

**`processed_events` table:**
```
event_id                              | event_type        | aggregate_id
--------------------------------------|-------------------|------------------
abc-123-...                           | DocumentUploaded  | doc-456-...
```

**`outbox_events` table:**
```
event_id    | event_type         | aggregate_id | status  | sent_at
------------|--------------------|--------------|---------|---------
xyz-789-... | DocumentRejected   | doc-456-...  | SENT    | 2026-...
```

---

## 🧪 Testing

### Test Valid Document

```bash
curl -X POST http://localhost:8081/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{
    "name": "test.pdf",
    "contentType": "application/pdf",
    "fileSize": 1024,
    "uploadedBy": "test"
  }'
```

**Expected:**
1. Validation-service logs: "Document VALIDATED"
2. Outbox event created (status=PENDING)
3. Within 2 seconds: OutboxPublisher publishes event
4. Outbox event marked as SENT

**Verify:**
```sql
-- Check outbox
SELECT event_id, event_type, status, sent_at 
FROM outbox_events 
WHERE event_type = 'DocumentValidated' 
ORDER BY created_at DESC 
LIMIT 1;

-- Should show: status='SENT', sent_at populated
```

---

### Test Invalid Document

```bash
curl -X POST http://localhost:8081/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{
    "name": "test.docx",
    "contentType": "application/msword",
    "fileSize": 1024,
    "uploadedBy": "test"
  }'
```

**Expected:**
1. Validation-service logs: "Document REJECTED"
2. Outbox event created (status=PENDING)
3. Within 2 seconds: OutboxPublisher publishes event
4. Outbox event marked as SENT

**Verify:**
```sql
SELECT event_id, event_type, status, sent_at 
FROM outbox_events 
WHERE event_type = 'DocumentRejected' 
ORDER BY created_at DESC 
LIMIT 1;
```

---

### Test RabbitMQ Failure (Reliability)

```bash
# 1. Stop RabbitMQ
docker compose -f docker-compose.infra.yml stop rabbitmq

# 2. Upload a document
curl -X POST http://localhost:8081/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{"name":"test.pdf","contentType":"application/pdf","fileSize":1024,"uploadedBy":"test"}'

# 3. Check outbox - should be PENDING
docker exec -it validation-db psql -U postgres -d validation_db \
  -c "SELECT event_id, status, retry_count, next_retry_at FROM outbox_events ORDER BY created_at DESC LIMIT 1;"

# 4. Watch logs - should see retry attempts
docker logs validation-service -f

# 5. Restart RabbitMQ
docker compose -f docker-compose.infra.yml start rabbitmq

# 6. Wait for next retry - should succeed and mark as SENT
```

---

## 📈 Monitoring Queries

### Check Outbox Backlog

```sql
-- Count by status
SELECT status, COUNT(*) 
FROM outbox_events 
GROUP BY status;

-- Expected:
-- PENDING: 0-10 (normal)
-- SENT: many
-- FAILED: 0 (ideally)
```

### Find Failed Events

```sql
SELECT event_id, event_type, retry_count, last_error, created_at 
FROM outbox_events 
WHERE status = 'FAILED' 
ORDER BY created_at DESC;
```

### Check Publishing Rate

```sql
SELECT 
    DATE_TRUNC('hour', sent_at) as hour,
    event_type,
    COUNT(*) as published_count
FROM outbox_events 
WHERE status = 'SENT' 
AND sent_at > NOW() - INTERVAL '24 hours'
GROUP BY hour, event_type
ORDER BY hour DESC;
```

---

## 🎯 Summary

### ✅ What We Achieved

1. **Atomicity**: processed_events + outbox_events in same transaction
2. **Reliability**: Events stored in DB, published even if RabbitMQ is down
3. **Idempotency**: Both consumer (processed_events) and producer (outbox) sides
4. **Retry Logic**: Exponential backoff, max 10 retries
5. **Observability**: Comprehensive logging, monitoring queries
6. **Clean Architecture**: Consumer logic decoupled from publishing

### 🎓 EDA Best Practices Applied

- ✅ Transactional Outbox pattern (PROJECT_CONTEXT.md line 41)
- ✅ At-least-once delivery guarantee
- ✅ Idempotent consumer (processed_events)
- ✅ Idempotent producer (outbox with eventId)
- ✅ Retry with exponential backoff
- ✅ No direct RabbitMQ calls in business logic
- ✅ Events are immutable (Java Records)
- ✅ Database is source of truth

---

## 🚀 Next Steps

1. ✅ Transactional Outbox implemented
2. ⏭️ **Next**: Implement enrichment-service consumer (consumes DocumentValidated)
3. ⏭️ **Next**: Implement audit-service consumer (consumes all events)
4. ⏭️ **Future**: Add metrics (Micrometer)
5. ⏭️ **Future**: Add distributed tracing

---

**The validation-service is now production-ready with reliable event publishing!** 🎉
