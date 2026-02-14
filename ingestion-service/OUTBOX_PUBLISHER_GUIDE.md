# Outbox Publisher Implementation Guide

## Overview

This document explains the **Outbox Publisher** implementation in the Ingestion Service, completing the Transactional Outbox pattern.

The Transactional Outbox pattern consists of two parts:
1. **Write Phase** (already implemented in `DocumentService`): Write business data + outbox event in the same transaction
2. **Publish Phase** (this implementation): Poll outbox events and publish to RabbitMQ

---

## Architecture

```
┌─────────────────┐
│ POST /documents │
└────────┬────────┘
         │
         ▼
┌─────────────────────────────────────┐
│  DocumentService (@Transactional)   │
│  1. Save Document                   │
│  2. Create OutboxEvent (PENDING)    │
│     Both in SAME DB transaction     │
└────────┬────────────────────────────┘
         │
         ▼
    ┌────────┐
    │ DB     │
    │ ├─ documents       │
    │ └─ outbox_events   │
    └────────┬───────────┘
         │
         ▼
┌─────────────────────────────────────┐
│ OutboxPublisher (@Scheduled)        │
│ - Polls every 2 seconds             │
│ - Fetches 50 PENDING events         │
│ - Publishes to RabbitMQ             │
│ - Marks as SENT or retries          │
└────────┬────────────────────────────┘
         │
         ▼
    ┌────────┐
    │ RabbitMQ│
    │ Exchange: doc.events            │
    │ Routing: document.uploaded      │
    │ Queue: document.uploaded.q      │
    └─────────────────────────────────┘
```

---

## Components

### 1. RabbitMQ Configuration (`RabbitMQConfig.java`)

**Purpose**: Declaratively creates RabbitMQ topology (exchanges, queues, bindings).

**Key Features**:
- **Auto-declaration**: Spring AMQP creates resources on startup (like Flyway for DB!)
- **Topic Exchange**: `doc.events` - allows flexible routing with wildcards
- **Queue**: `document.uploaded.q` - stores events for consumers
- **Dead Letter Queue (DLQ)**: `document.uploaded.dlq` - stores permanently failed messages
- **Publisher Confirms**: Ensures messages are persisted before acknowledging
- **Queue Limits**: Max 10,000 messages, 7-day TTL to prevent unbounded growth

**Topology Created**:
```
doc.events (topic exchange)
    │
    ├─ routing key: document.uploaded
    │   └─> document.uploaded.q
    │
    └─ dead letter exchange: doc.events.dlx
        └─> document.uploaded.dlq
```

**No Manual Setup Required**: RabbitMQ topology is created automatically on service startup!

---

### 2. Outbox Properties (`OutboxProperties.java`)

**Purpose**: Externalized configuration for the Outbox Publisher.

**Configurable Parameters**:
- `polling-interval`: How often to poll (default: 2 seconds)
- `batch-size`: Events per batch (default: 50)
- `max-retries`: Max attempts before FAILED (default: 10)
- `initial-retry-delay`: First retry delay (default: 10 seconds)
- `max-retry-delay`: Max retry delay cap (default: 1 hour)
- `enabled`: Toggle publisher on/off (default: true)

**Configuration in `application.yml`**:
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

### 3. Outbox Publisher (`OutboxPublisher.java`)

**Purpose**: Background job that polls and publishes outbox events to RabbitMQ.

**Key Implementation Details**:

#### Scheduling
- **Fixed Delay**: Waits for previous execution to complete before starting next
- **Prevents Overlaps**: No concurrent executions
- **Configurable**: Interval set via `application.yml`

#### Batch Processing
```java
@Scheduled(fixedDelayString = "#{@outboxProperties.pollingInterval.toMillis()}")
public void publishPendingEvents() {
    // Fetch batch of PENDING events (max 50)
    List<OutboxEvent> pendingEvents = outboxEventRepository
        .findPendingEvents(PageRequest.of(0, batchSize));
    
    // Process each event
    for (OutboxEvent event : pendingEvents) {
        publishEvent(event);
    }
}
```

#### Message Publishing
Sets important RabbitMQ message properties:
- **messageId**: `eventId` (UUID) for consumer deduplication
- **contentType**: `application/json`
- **headers**: `eventType`, `aggregateType`, `aggregateId` for routing/filtering
- **timestamp**: When published

#### Retry Logic (Exponential Backoff)
```
Retry 1:  10s delay
Retry 2:  20s delay
Retry 3:  40s delay
Retry 4:  80s delay (1m 20s)
Retry 5:  160s delay (2m 40s)
Retry 6:  320s delay (5m 20s)
Retry 7:  640s delay (10m 40s)
Retry 8:  1280s delay (21m 20s)
Retry 9:  2560s delay (42m 40s)
Retry 10: 3600s delay (1h - capped at max-retry-delay)
```

After 10 retries, event is marked as `FAILED` and won't be retried automatically.

#### Transactional Safety
- Each event update is `@Transactional`
- Status changes are atomic (PENDING → SENT or FAILED)
- No lost updates

---

## Concurrency Safety

### Current Implementation: Single Instance
The current implementation assumes **one instance** of `ingestion-service`.

### Multi-Instance Deployment
For horizontal scaling (multiple service instances), you need **distributed locking** to prevent duplicate processing.

**Option 1: Database-Level Locking (Recommended)**
Use `SELECT FOR UPDATE SKIP LOCKED`:

```java
@Query(value = """
    SELECT * FROM outbox_events 
    WHERE status = 'PENDING' 
    OR (status = 'PENDING' AND next_retry_at <= NOW())
    ORDER BY created_at ASC 
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
List<OutboxEvent> findPendingEventsWithLock(int limit);
```

**How it works**:
- Each instance locks different rows
- `SKIP LOCKED` skips rows locked by other instances
- No blocking, no duplicate processing
- **Requires PostgreSQL 9.5+** (or equivalent in other DBs)

**Option 2: Distributed Locks**
- Redis: `SET key NX EX ttl`
- Database advisory locks: `pg_try_advisory_lock()`
- Hazelcast, Redisson, etc.

**Option 3: Leader Election**
- Kubernetes leader election
- Only the leader instance publishes
- Automatic failover

**Option 4: Partitioning**
- Partition events by `aggregate_id % instance_count`
- Each instance processes its partition
- Requires consistent hashing

---

## Observability

### Logging
The implementation includes comprehensive logging:

```java
// Batch summary
log.info("Found {} pending outbox events to publish", pendingEvents.size());
log.info("Outbox publish batch completed. Success: {}, Failures: {}", successCount, failureCount);

// Per-event tracking
log.info("Successfully published event: eventId={}, aggregateId={}", eventId, aggregateId);
log.error("Failed to publish event: eventId={}, aggregateId={}, attempt={}", eventId, aggregateId, retryCount);
log.warn("Will retry event: eventId={}, aggregateId={}, attempt={}, nextRetry={}", eventId, aggregateId, retryCount, nextRetry);
```

### Log Levels
- `TRACE`: No pending events (normal operation)
- `DEBUG`: Event details before publishing
- `INFO`: Batch summaries, successful publishes
- `WARN`: Retries scheduled
- `ERROR`: Publish failures, max retries exceeded

### Future Improvements
1. **Metrics** (Micrometer):
   - `outbox.published.count` - Total published
   - `outbox.failed.count` - Total failures
   - `outbox.retry.count` - Total retries
   - `outbox.backlog.gauge` - Pending events count
   - `outbox.publish.duration` - Publish latency

2. **Health Indicator**:
   - Status: `UP` if backlog < threshold
   - Status: `DOWN` if backlog > threshold
   - Expose backlog size, oldest pending event age

3. **Distributed Tracing**:
   - Add correlation IDs
   - Trace event from creation → publish → consumer
   - Integrate with Zipkin, Jaeger, or OpenTelemetry

4. **Alerting**:
   - Alert if backlog grows beyond threshold
   - Alert if FAILED events exceed threshold
   - Alert if oldest pending event exceeds SLA

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

### 2. Upload a Document

```bash
curl -X POST http://localhost:8081/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{
    "name": "test-document.pdf",
    "contentType": "application/pdf",
    "fileSize": 1024000,
    "metadata": {
      "description": "Test document for outbox publisher",
      "category": "testing"
    },
    "uploadedBy": "test-user"
  }'
```

**Expected Response**:
```json
{
  "id": "123e4567-e89b-12d3-a456-426614174000",
  "name": "test-document.pdf",
  "contentType": "application/pdf",
  "fileSize": 1024000,
  "status": "UPLOADED",
  "metadata": {
    "description": "Test document for outbox publisher",
    "category": "testing"
  },
  "createdAt": "2026-02-13T10:30:00Z",
  "updatedAt": "2026-02-13T10:30:00Z",
  "createdBy": "test-user",
  "updatedBy": "test-user"
}
```

### 3. Check Database (Outbox Events)

```bash
# Connect to Postgres
docker exec -it ingestion-db psql -U postgres -d ingestion_db

# Query outbox events
SELECT id, event_id, event_type, aggregate_id, status, retry_count, created_at, sent_at 
FROM outbox_events 
ORDER BY created_at DESC 
LIMIT 5;
```

**Expected Result** (initially):
```
status = 'PENDING'
retry_count = 0
sent_at = NULL
```

**After ~2 seconds** (when OutboxPublisher runs):
```
status = 'SENT'
retry_count = 0
sent_at = '2026-02-13 10:30:02'
```

### 4. Check RabbitMQ

Open RabbitMQ Management UI:
```
http://localhost:15672
Username: guest
Password: guest
```

**Verify**:
1. **Exchanges**: `doc.events` exists
2. **Queues**: `document.uploaded.q` exists with 1 message
3. **Message**: Click queue → "Get messages" → See payload

**Expected Message**:
- **Properties**:
  - `message_id`: `<event_id UUID>`
  - `content_type`: `application/json`
  - `headers`: `eventType`, `aggregateType`, `aggregateId`
- **Payload** (JSON):
  ```json
  {
    "eventId": "...",
    "eventType": "DocumentUploaded",
    "aggregateId": "...",
    "timestamp": "...",
    "documentName": "test-document.pdf",
    "contentType": "application/pdf",
    "fileSize": 1024000
  }
  ```

### 5. Check Service Logs

```bash
# View ingestion-service logs
docker logs ingestion-service -f

# Look for:
# - "Document uploaded successfully. ID: ..."
# - "Found 1 pending outbox events to publish"
# - "Successfully published event: eventId=..., aggregateId=..."
# - "Outbox publish batch completed. Success: 1, Failures: 0"
```

---

## Testing Retry Logic

### Simulate Failure (Stop RabbitMQ)

```bash
# Stop RabbitMQ
docker compose -f docker-compose.infra.yml stop rabbitmq

# Upload a document
curl -X POST http://localhost:8081/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{
    "name": "test-retry.pdf",
    "contentType": "application/pdf",
    "fileSize": 512000,
    "uploadedBy": "test-user"
  }'

# Check logs - should see errors:
# "Failed to publish event: eventId=..., aggregateId=..., attempt=1"
# "Will retry event: eventId=..., aggregateId=..., attempt=1, nextRetry=..."

# Check database - retry_count increments, next_retry_at set
SELECT event_id, status, retry_count, next_retry_at, last_error 
FROM outbox_events 
WHERE status = 'PENDING';
```

### Restart RabbitMQ

```bash
# Restart RabbitMQ
docker compose -f docker-compose.infra.yml start rabbitmq

# Wait for next_retry_at to pass
# OutboxPublisher will retry and succeed

# Check logs:
# "Successfully published event: eventId=..., aggregateId=..."

# Check database:
# status = 'SENT', sent_at populated
```

---

## Production Checklist

### Before Deploying to Production

- [ ] **Multi-Instance Safety**: Implement `FOR UPDATE SKIP LOCKED` or distributed locking
- [ ] **Monitoring**: Add Micrometer metrics
- [ ] **Alerting**: Set up alerts for backlog growth, failures
- [ ] **Health Checks**: Implement custom health indicator based on backlog
- [ ] **Distributed Tracing**: Add correlation IDs, integrate with APM
- [ ] **Rate Limiting**: Prevent overwhelming RabbitMQ with too many messages
- [ ] **Circuit Breaker**: Stop publishing if RabbitMQ is consistently failing
- [ ] **Dead Letter Queue Monitoring**: Process or alert on DLQ messages
- [ ] **Configuration Tuning**: Adjust polling interval, batch size based on load
- [ ] **Database Indexes**: Verify indexes on `status`, `created_at`, `next_retry_at`
- [ ] **Archival Strategy**: Archive or delete old SENT events (prevent table bloat)
- [ ] **Idempotent Consumers**: Ensure consumers handle duplicate messages
- [ ] **Message Ordering**: Document ordering guarantees (or lack thereof)
- [ ] **Schema Evolution**: Plan for event schema changes (versioning strategy)

---

## Troubleshooting

### Events Stuck in PENDING

**Symptoms**: Events remain PENDING, never become SENT.

**Possible Causes**:
1. **RabbitMQ Down**: Check `docker ps | grep rabbitmq`
2. **Network Issues**: Check connectivity from service to RabbitMQ
3. **Scheduler Disabled**: Check `outbox.publisher.enabled=true` in config
4. **@EnableScheduling Missing**: Verify annotation in main application class
5. **Transaction Issues**: Check database logs for errors

**Debug**:
```bash
# Check OutboxPublisher logs
docker logs ingestion-service | grep OutboxPublisher

# Check RabbitMQ connectivity
docker exec -it ingestion-service curl http://rabbitmq:15672/api/health/checks/alarms

# Check outbox configuration
docker exec -it ingestion-service env | grep OUTBOX
```

### High Retry Count

**Symptoms**: Events have `retry_count > 5` but still PENDING.

**Possible Causes**:
1. **Transient RabbitMQ failures**
2. **Network instability**
3. **RabbitMQ resource limits** (memory, disk)

**Actions**:
1. Check RabbitMQ resource usage
2. Increase polling interval to reduce load
3. Check network latency/stability
4. Review RabbitMQ logs for errors

### Events Marked as FAILED

**Symptoms**: Events reach max retries and become FAILED.

**Actions**:
1. **Investigate root cause**: Check `last_error` column
2. **Manual republish**: Update status to PENDING, reset retry_count
   ```sql
   UPDATE outbox_events 
   SET status = 'PENDING', retry_count = 0, last_error = NULL, next_retry_at = NULL 
   WHERE id = '<failed-event-id>';
   ```
3. **Long-term fix**: Address root cause (RabbitMQ config, network, etc.)

### Duplicate Messages in RabbitMQ

**Symptoms**: Same `eventId` appears multiple times in queue.

**Possible Causes**:
1. **Multi-instance without locking**: Multiple instances publishing same event
2. **Transaction rollback**: Event marked SENT, then transaction rolled back

**Fix**:
- Implement `FOR UPDATE SKIP LOCKED` for multi-instance safety
- Ensure consumers are idempotent (use `messageId` for deduplication)

---

## Best Practices Summary

1. **At-Least-Once Delivery**: Design consumers to be idempotent
2. **Exponential Backoff**: Prevents overwhelming failing systems
3. **Dead Letter Queues**: Capture permanently failed messages for investigation
4. **Monitoring**: Track backlog, failures, and latency
5. **Multi-Instance Safety**: Use database-level locking for horizontal scaling
6. **Configuration Externalization**: Tune without code changes
7. **Comprehensive Logging**: Essential for debugging distributed systems
8. **Publisher Confirms**: Ensures RabbitMQ persisted the message
9. **Transactional Updates**: Event status changes are atomic
10. **Archival Strategy**: Prevent database table bloat

---

## Next Steps

1. **Implement Consumers**: Create listeners in validation-service, enrichment-service
2. **Add Metrics**: Integrate Micrometer for observability
3. **Multi-Instance Support**: Add `FOR UPDATE SKIP LOCKED` for production
4. **Schema Versioning**: Plan for event schema evolution
5. **Integration Tests**: Add tests with Testcontainers for RabbitMQ + Postgres

---

## References

- **Transactional Outbox Pattern**: https://microservices.io/patterns/data/transactional-outbox.html
- **Spring AMQP Documentation**: https://docs.spring.io/spring-amqp/reference/
- **PostgreSQL SKIP LOCKED**: https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE
- **Exponential Backoff**: https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
