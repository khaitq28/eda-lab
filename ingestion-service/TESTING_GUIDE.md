# Validation Service - Testing Guide

## 📋 Test Scripts Overview

Three test scripts are provided to test different validation scenarios:

1. **`test-valid-document.sh`** - Tests valid PDF documents (✅ VALIDATED)
2. **`test-invalid-document.sh`** - Tests invalid document formats (❌ REJECTED)
3. **`run-all-tests.sh`** - Runs all tests in sequence

---

## 🚀 Quick Start

### Prerequisites

Make sure services are running:

```bash
# Start infrastructure
docker compose -f docker-compose.infra.yml up -d

# Start services
docker compose up --build
```

Verify services are healthy:
```bash
curl http://localhost:8081/actuator/health  # ingestion-service
curl http://localhost:8082/actuator/health  # validation-service
```

---

## 🧪 Test 1: Valid PDF Document

Tests the **happy path** - document should be VALIDATED.

### Run:
```bash
cd validation-service
./test-valid-document.sh
```

### What it does:
- Generates random document name (e.g., `doc-1234567.pdf`) < 30 chars
- Generates random file size (1KB - 1MB)
- Content type: `application/pdf`
- Uploads to ingestion-service
- Waits 3 seconds for validation-service to process

### Expected Result:
✅ **Document VALIDATED**

**Logs:**
```
Document VALIDATED: documentId=<uuid>
```

**Database:**
```sql
SELECT * FROM processed_events WHERE aggregate_id = '<uuid>';
-- Should return 1 row
```

**RabbitMQ:**
- `document.uploaded.q`: Empty (message consumed)
- `document.uploaded.dlq`: Empty (no failures)

---

## 🧪 Test 2: Invalid Document Format

Tests **business validation failure** - document should be REJECTED.

### Run:
```bash
cd validation-service
./test-invalid-document.sh
```

### What it does:
- Generates random document name (< 30 chars)
- Randomly selects invalid format:
  - `application/msword` (.docx)
  - `application/vnd.ms-excel` (.xlsx)
  - `text/plain` (.txt)
  - `image/jpeg` (.jpg)
  - `application/zip` (.zip)
- Uploads to ingestion-service
- Waits 3 seconds for validation-service to process

### Expected Result:
❌ **Document REJECTED** (business validation failure)

**Logs:**
```
Document REJECTED due to business validation: reason=Invalid file format: application/msword (expected: application/pdf)
```

**Important:**
- ✅ Message is ACKed (removed from queue)
- ✅ Event is marked as processed (no retry)
- ✅ **NOT sent to DLQ** (business failure, not technical)

**Database:**
```sql
SELECT * FROM processed_events WHERE aggregate_id = '<uuid>';
-- Should return 1 row (marked as processed)
```

**RabbitMQ:**
- `document.uploaded.q`: Empty (message consumed)
- `document.uploaded.dlq`: Empty (business failure, not retried)

---

## 🧪 Test 3: Run All Tests

Runs all test scenarios in sequence.

### Run:
```bash
cd validation-service
./run-all-tests.sh
```

### What it does:
1. Checks if services are running
2. Runs Test 1: Valid PDF
3. Runs Test 2: Invalid format
4. Runs Test 3: Name too long (edge case)
5. Displays summary

---

## 📊 Test Scenarios Summary

| Test | Document Name | Format | Expected Result | Retry? | DLQ? |
|------|--------------|--------|-----------------|--------|------|
| Valid PDF | Random (< 30) | `application/pdf` | ✅ VALIDATED | No | No |
| Invalid Format | Random (< 30) | Non-PDF | ❌ REJECTED | No | No |
| Name Too Long | > 30 chars | `application/pdf` | ❌ REJECTED | No | No |

---

## 🔍 Verification Steps

### 1. Check Validation Service Logs

```bash
# Follow logs in real-time
docker logs validation-service -f

# Search for specific document
docker logs validation-service --tail 50 | grep '<document-id>'
```

**Look for:**
- `"Document VALIDATED: documentId=..."`
- `"Document REJECTED due to business validation: reason=..."`

---

### 2. Check Database (processed_events)

```bash
# Connect to validation database
docker exec -it validation-db psql -U postgres -d validation_db

# Query processed events
SELECT event_id, event_type, aggregate_id, processed_at 
FROM processed_events 
ORDER BY processed_at DESC 
LIMIT 10;

# Query specific document
SELECT * FROM processed_events WHERE aggregate_id = '<document-id>';
```

**Expected:**
- Each processed event should have 1 row (regardless of validated or rejected)
- `processed_at` timestamp should match processing time

---

### 3. Check RabbitMQ

Open RabbitMQ Management UI:
```
http://localhost:15672
Username: guest
Password: guest
```

**Check Queues:**
1. **`document.uploaded.q`** - Main queue
   - Should be empty (messages consumed)
   
2. **`document.uploaded.dlq`** - Dead Letter Queue
   - Should be empty for business failures
   - Would contain messages only after technical failures + 5 retries

**Check Exchanges:**
- **`doc.events`** - Topic exchange (should exist)
- **`doc.dlx`** - Dead Letter Exchange (should exist)

---

## 🎯 Understanding Test Results

### ✅ Valid Document (Expected: VALIDATED)

**Flow:**
```
1. Upload to ingestion-service → Save to DB + Outbox
2. Outbox publisher → Publish to RabbitMQ
3. Validation-service consumer → Receive message
4. Check idempotency → Not processed yet
5. Validate → Name ≤ 30, format = PDF ✅
6. Mark as processed → Save to processed_events
7. Return normally → ACK message
8. Log: "Document VALIDATED"
```

**Database:**
- `ingestion_db.documents`: 1 row
- `ingestion_db.outbox_events`: 1 row (status = SENT)
- `validation_db.processed_events`: 1 row

---

### ❌ Invalid Document (Expected: REJECTED)

**Flow:**
```
1. Upload to ingestion-service → Save to DB + Outbox
2. Outbox publisher → Publish to RabbitMQ
3. Validation-service consumer → Receive message
4. Check idempotency → Not processed yet
5. Validate → Format ≠ PDF ❌
6. Throw BusinessValidationException
7. Catch business exception → Mark as processed
8. Return normally (no throw) → ACK message
9. Log: "Document REJECTED: Invalid file format"
```

**Key Differences:**
- ❌ **No retry** (business failure)
- ✅ **Mark as processed** (don't process again)
- ✅ **ACK message** (remove from queue)
- ❌ **Not in DLQ** (business logic completed)

**Database:**
- `ingestion_db.documents`: 1 row
- `ingestion_db.outbox_events`: 1 row (status = SENT)
- `validation_db.processed_events`: 1 row (marked as processed despite rejection)

---

## 🔧 Troubleshooting

### Issue: "Connection refused" error

**Problem:** Services not running.

**Solution:**
```bash
docker compose ps  # Check service status
docker compose up  # Start services
```

---

### Issue: Message not processed

**Problem:** Validation service not consuming messages.

**Solution:**
```bash
# Check validation service logs
docker logs validation-service --tail 50

# Check RabbitMQ connection
docker logs validation-service | grep -i rabbitmq

# Restart validation service
docker compose restart validation-service
```

---

### Issue: Message in DLQ

**Problem:** Technical failure occurred (not expected for these tests).

**Solution:**
```bash
# Check what went wrong
docker logs validation-service --tail 100 | grep -i error

# Inspect DLQ message in RabbitMQ UI
# http://localhost:15672 → Queues → document.uploaded.dlq → Get messages
```

---

## 📈 Advanced Testing

### Test Idempotency (Duplicate Message)

```bash
# 1. Upload a document
./test-valid-document.sh

# 2. Note the document ID from output
# 3. Manually republish the same message in RabbitMQ UI
# 4. Check logs - should see "Event already processed (idempotent skip)"
```

### Test Retry Logic (Requires Code Modification)

To test retry mechanism, temporarily add a technical failure:

```java
// In DocumentUploadedConsumer.validateDocument()
throw new RuntimeException("Simulated DB failure");  // Add this line
```

Then:
```bash
./test-valid-document.sh

# Watch logs - should see 5 retry attempts with delays
docker logs validation-service -f
```

---

## 🎓 Learning Points

1. **Business vs. Technical Failures**
   - Business failures (invalid format) → No retry
   - Technical failures (DB down) → Retry 5 times → DLQ

2. **Idempotency**
   - Each event processed exactly once
   - Duplicate messages skipped

3. **Message Lifecycle**
   - Ingestion → Outbox → RabbitMQ → Validation
   - ACK = delete from queue
   - NACK = retry or DLQ

4. **Observability**
   - Logs show processing flow
   - Database shows processed events
   - RabbitMQ shows queue status

---

## 📚 Next Steps

After running these tests, you're ready for:
1. Emit `DocumentValidated` and `DocumentRejected` events (next iteration)
2. Implement enrichment-service consumer
3. Add metrics and monitoring
4. Load testing with high volume

---

## ✨ Summary

These test scripts demonstrate:
- ✅ Valid document validation (happy path)
- ❌ Business validation failures (reject, don't retry)
- 🔄 Idempotency (duplicate handling)
- 📊 Observability (logs, DB, RabbitMQ)

All tests are **automated, repeatable, and demonstrate EDA best practices**! 🚀
