# Multi-Instance Safety Implementation

## Overview

This document explains how the EDA project is now **safe for horizontal scaling** (multiple instances per service).

---

## Problem Statement

### Consumer Side (✅ Already Safe)

**Question:** Can 3 validation-service instances consume the same event?

**Answer:** ✅ **NO** - RabbitMQ handles this automatically.

```
RabbitMQ Queue: document.uploaded.q
    ├─ Instance 1 → Message A
    ├─ Instance 2 → Message B
    └─ Instance 3 → Message C
```

- RabbitMQ distributes messages round-robin
- Each message delivered to **ONE consumer only**
- Prefetch count controls messages per instance

**No changes needed for consumers!** ✅

---

### Outbox Publisher Side (❌ Was NOT Safe, ✅ Now Fixed)

**Question:** Can 3 validation-service instances poll the same outbox table?

**Problem Before:**

```
❌ Without locking:
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

**Solution: SELECT FOR UPDATE SKIP LOCKED** ✅

```
✅ With row-level locking:
┌──────────────────────────────────────────┐
│ outbox_events table                      │
│ 1-50:PENDING  51-100:PENDING  101-150:PENDING │
└──────────────────────────────────────────┘
            ↓         ↓         ↓
    Instance 1   Instance 2   Instance 3
    locks 1-50   locks 51-100 locks 101-150
            ↓         ↓         ↓
    Publishes    Publishes    Publishes
            ↓         ↓         ↓
    Each message published ONCE! ✅
```

---

## Implementation

### Repository Layer

**Before (JPQL):**

```java
@Query("""
    SELECT e FROM OutboxEvent e 
    WHERE e.status = 'PENDING' 
    ORDER BY e.createdAt ASC
    """)
List<OutboxEvent> findPendingEvents(Instant now, Pageable pageable);
```

**After (Native SQL with Row Locking):**

```java
@Query(value = """
    SELECT * FROM outbox_events 
    WHERE status = 'PENDING' 
    AND (next_retry_at IS NULL OR next_retry_at <= :now)
    ORDER BY created_at ASC 
    LIMIT :limit
    FOR UPDATE SKIP LOCKED  -- ✅ Multi-instance safety!
    """, nativeQuery = true)
List<OutboxEvent> findPendingEvents(@Param("now") Instant now, @Param("limit") int limit);
```

### Changes Applied

#### ingestion-service

- ✅ `OutboxEventRepository.java` - Added `FOR UPDATE SKIP LOCKED`
- ✅ `OutboxPublisher.java` - Updated method call to use `int limit` instead of `Pageable`
- ✅ Removed unused imports (`PageRequest`, `Pageable`)

#### validation-service

- ✅ `OutboxEventRepository.java` - Added `FOR UPDATE SKIP LOCKED`
- ✅ `OutboxPublisher.java` - Updated method call to use `int limit` instead of `Pageable`
- ✅ Removed unused imports (`PageRequest`, `Pageable`)

---

## How FOR UPDATE SKIP LOCKED Works

### Step-by-Step

1. **Instance 1 starts transaction:**
   ```sql
   SELECT * FROM outbox_events 
   WHERE status = 'PENDING' 
   LIMIT 50 
   FOR UPDATE SKIP LOCKED;
   ```
   - Locks rows 1-50
   - Other transactions CANNOT read these rows

2. **Instance 2 starts transaction (same query):**
   - Tries to lock rows 1-50 → **LOCKED by Instance 1**
   - **SKIP LOCKED** → Skip these rows
   - Locks rows 51-100 instead

3. **Instance 3 starts transaction (same query):**
   - Rows 1-50 locked by Instance 1 → SKIP
   - Rows 51-100 locked by Instance 2 → SKIP
   - Locks rows 101-150

4. **Each instance publishes different events:**
   - Instance 1 publishes events 1-50
   - Instance 2 publishes events 51-100
   - Instance 3 publishes events 101-150
   - **No duplicates!** ✅

### Key Features

- **`FOR UPDATE`**: Locks rows for update (prevents other transactions from reading)
- **`SKIP LOCKED`**: Skip rows that are already locked (instead of waiting)
- **Result**: Each instance processes different events

---

## Database Requirements

### PostgreSQL Version

- **Required:** PostgreSQL 9.5+ (for `SKIP LOCKED` support)
- **Used in project:** PostgreSQL 16 (via Docker) ✅

### Other Databases

| Database | Support | Syntax |
|----------|---------|--------|
| PostgreSQL 9.5+ | ✅ Yes | `FOR UPDATE SKIP LOCKED` |
| MySQL 8.0+ | ✅ Yes | `FOR UPDATE SKIP LOCKED` |
| Oracle 11g+ | ✅ Yes | `FOR UPDATE SKIP LOCKED` |
| SQL Server | ❌ No native support | Use `READPAST` hint (different semantics) |
| MariaDB 10.6+ | ✅ Yes | `FOR UPDATE SKIP LOCKED` |

---

## Testing Multi-Instance Deployment

### Local Testing with Docker Compose

#### 1. Scale ingestion-service to 3 instances

```bash
docker compose up --scale ingestion-service=3 -d
```

#### 2. Verify 3 instances running

```bash
docker compose ps | grep ingestion
# Should show:
# ingestion-service-1
# ingestion-service-2
# ingestion-service-3
```

#### 3. Upload multiple documents

```bash
for i in {1..100}; do
  curl -X POST http://localhost:8081/api/v1/documents \
    -H "Content-Type: application/json" \
    -d '{
      "name": "test-doc-'$i'.pdf",
      "contentType": "application/pdf",
      "fileSize": 1024,
      "uploadedBy": "test@example.com"
    }'
  echo "Uploaded document $i"
done
```

#### 4. Check logs for each instance

```bash
# Instance 1
docker logs ingestion-service-1 | grep "Publishing event"

# Instance 2
docker logs ingestion-service-2 | grep "Publishing event"

# Instance 3
docker logs ingestion-service-3 | grep "Publishing event"
```

**Expected Result:**
- Each instance publishes different events
- No duplicate `eventId` across instances
- Total published = 100 (no duplicates)

#### 5. Verify in RabbitMQ

Open RabbitMQ Management UI: http://localhost:15672

- Check `document.uploaded.q` message count
- Should match number of uploaded documents
- No duplicate messages

#### 6. Verify in database

```bash
docker exec -it ingestion-db psql -U postgres -d ingestion_db -c \
  "SELECT status, COUNT(*) FROM outbox_events GROUP BY status;"
```

**Expected Result:**
```
 status  | count 
---------+-------
 SENT    |   100
 PENDING |     0
(2 rows)
```

---

### Load Testing

#### Script: test-multi-instance.sh

```bash
#!/bin/bash

echo "============================================"
echo "Multi-Instance Load Test"
echo "============================================"

# Scale services
echo "Scaling ingestion-service to 3 instances..."
docker compose up --scale ingestion-service=3 -d

sleep 5

echo "Scaling validation-service to 3 instances..."
docker compose up --scale validation-service=3 -d

sleep 5

# Verify instances
echo ""
echo "Running instances:"
docker compose ps | grep -E "(ingestion|validation)"

# Upload 100 documents
echo ""
echo "Uploading 100 documents..."
for i in {1..100}; do
  RESPONSE=$(curl -s -X POST http://localhost:8081/api/v1/documents \
    -H "Content-Type: application/json" \
    -d '{
      "name": "load-test-'$i'.pdf",
      "contentType": "application/pdf",
      "fileSize": 1024,
      "uploadedBy": "load-test@example.com"
    }')
  
  if [ $((i % 10)) -eq 0 ]; then
    echo "  Uploaded $i documents..."
  fi
done

echo ""
echo "Waiting for processing (30 seconds)..."
sleep 30

# Check results
echo ""
echo "============================================"
echo "Results"
echo "============================================"

echo ""
echo "Ingestion Service Outbox Status:"
docker exec -it ingestion-db psql -U postgres -d ingestion_db -c \
  "SELECT status, COUNT(*) FROM outbox_events GROUP BY status;"

echo ""
echo "Validation Service Processed Events:"
docker exec -it validation-db psql -U postgres -d validation_db -c \
  "SELECT COUNT(DISTINCT event_id) as processed_count FROM processed_events;"

echo ""
echo "Validation Service Outbox Status:"
docker exec -it validation-db psql -U postgres -d validation_db -c \
  "SELECT event_type, status, COUNT(*) FROM outbox_events GROUP BY event_type, status;"

echo ""
echo "RabbitMQ Queue Status:"
echo "  Open: http://localhost:15672"
echo "  Check 'document.uploaded.q' and 'document.validated.q'"

echo ""
echo "============================================"
echo "Test Complete!"
echo "============================================"
```

#### Expected Results

1. **No Duplicate Publishing:**
   - Each `eventId` published exactly once
   - No duplicate messages in RabbitMQ

2. **Load Distribution:**
   - Each instance processes roughly 1/3 of events
   - Check logs to verify distribution

3. **Idempotency Still Works:**
   - If somehow duplicates occur, consumers handle it
   - Check `processed_events` table for uniqueness

---

## Monitoring and Observability

### Key Metrics to Track

1. **Outbox Events by Status:**
   ```sql
   SELECT status, COUNT(*) 
   FROM outbox_events 
   GROUP BY status;
   ```

2. **Events Published per Instance:**
   - Check logs: `grep "Successfully published event" | wc -l`
   - Should be roughly equal across instances

3. **Lock Contention:**
   - PostgreSQL query: `pg_stat_activity`
   - Look for waiting transactions
   - High contention → increase batch size or polling interval

4. **Processing Latency:**
   ```sql
   SELECT 
     AVG(EXTRACT(EPOCH FROM (sent_at - created_at))) as avg_latency_seconds
   FROM outbox_events 
   WHERE status = 'SENT';
   ```

### Logging Examples

```
[ingestion-service-1] Publishing event abc123 (batch 1/50)
[ingestion-service-2] Publishing event def456 (batch 1/50)
[ingestion-service-3] Publishing event ghi789 (batch 1/50)
```

**Good sign:** Different `eventId` across instances ✅

---

## Troubleshooting

### Issue: All instances publishing same events

**Symptom:** Logs show duplicate `eventId` across instances

**Cause:** `FOR UPDATE SKIP LOCKED` not working

**Solution:**
1. Check PostgreSQL version: `SELECT version();`
   - Must be 9.5+
2. Verify native query syntax in repository
3. Check transaction isolation level

### Issue: Only one instance publishing

**Symptom:** Only instance 1 shows "Publishing event" logs

**Cause:** Not enough pending events for distribution

**Solution:**
1. Upload more documents (> batch_size * num_instances)
2. Increase `outbox.publisher.polling-interval` to slow down one instance
3. Check if other instances are running: `docker compose ps`

### Issue: High database CPU usage

**Symptom:** PostgreSQL CPU at 100%

**Cause:** Too many instances, too frequent polling

**Solution:**
1. Increase `polling-interval` (from 2s to 5s)
2. Reduce number of instances
3. Increase `batch-size` to process more per poll
4. Add database connection pooling

---

## Performance Tuning

### Configuration Options

**application.yml:**

```yaml
outbox:
  publisher:
    enabled: true
    polling-interval: 2000  # Increase for less DB load
    batch-size: 50          # Increase for higher throughput
    max-retries: 10
```

### Recommendations

| Scenario | polling-interval | batch-size | instances |
|----------|------------------|------------|-----------|
| Low volume (< 100 msg/min) | 5000ms | 20 | 1-2 |
| Medium volume (100-1000 msg/min) | 2000ms | 50 | 2-3 |
| High volume (> 1000 msg/min) | 1000ms | 100 | 3-5 |

### Database Indexes

Already implemented in Flyway migrations:

```sql
-- Critical for FOR UPDATE SKIP LOCKED performance
CREATE INDEX idx_outbox_pending_next_retry 
ON outbox_events(status, next_retry_at) 
WHERE status = 'PENDING';
```

---

## Production Checklist

- [✅] `FOR UPDATE SKIP LOCKED` implemented in both services
- [✅] PostgreSQL 9.5+ or compatible database
- [✅] Database indexes on `status` and `next_retry_at`
- [✅] Consumer idempotency (handled by `processed_events`)
- [✅] Exponential backoff for retries
- [✅] Dead Letter Queue for failed messages
- [ ] Metrics and monitoring (Prometheus, Grafana)
- [ ] Distributed tracing (OpenTelemetry, Zipkin)
- [ ] Health checks for Kubernetes
- [ ] Connection pooling (HikariCP is default in Spring Boot)
- [ ] Circuit breaker for RabbitMQ failures

---

## Summary

### What Changed

1. **Repository queries now use native SQL with `FOR UPDATE SKIP LOCKED`**
2. **Method signature changed from `Pageable` to `int limit`**
3. **Removed unused imports (`PageRequest`, `Pageable`)**
4. **Updated comments to reflect multi-instance safety**

### Benefits

- ✅ Safe for horizontal scaling (multiple instances)
- ✅ No duplicate event publishing
- ✅ Automatic load distribution
- ✅ No external dependencies (no Redis, no leader election)
- ✅ Simple and reliable

### Requirements

- PostgreSQL 9.5+ (or compatible database)
- Proper transaction isolation (default is fine)

---

**The system is now production-ready for multi-instance deployment!** 🚀
