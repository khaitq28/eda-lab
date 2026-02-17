# Ingestion Service - Implementation Notes

## ✅ What's Implemented

### 1. **Transactional Outbox Pattern** (Core EDA Pattern)

The service implements the Transactional Outbox pattern to guarantee reliable event publishing:

```java
@Transactional
public DocumentResponse uploadDocument(UploadDocumentRequest request) {
    // 1. Save business data
    Document document = documentRepository.save(document);
    
    // 2. Save event in SAME transaction
    OutboxEvent outboxEvent = createOutboxEvent(event, document.getId());
    outboxEventRepository.save(outboxEvent);
    
    // Both commit together - atomicity guaranteed!
    return mapToResponse(document);
}
```

**Why this matters:**
- ✅ **Atomicity**: Either both document AND event are saved, or neither
- ✅ **Reliability**: No lost events (event is in database)
- ✅ **Consistency**: Database is the source of truth
- ✅ **No dual-write problem**: Avoids the classic distributed systems issue

---

### 2. **Database Schema**

#### Documents Table
```sql
CREATE TABLE documents (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'UPLOADED',
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);
```

#### Outbox Events Table
```sql
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,          -- For consumer idempotency
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    payload JSONB NOT NULL,                 -- Full event as JSON
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE,
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    next_retry_at TIMESTAMP WITH TIME ZONE
);
```

**Key Design Decisions:**
- `event_id` is UNIQUE for consumer idempotency
- `payload` is JSONB for flexibility (can evolve event schema)
- `status` tracks outbox processing (PENDING → SENT/FAILED)
- `retry_count` and `next_retry_at` for retry logic
- Indexes optimized for outbox publisher queries

---

### 3. **API Endpoints**

#### POST /api/documents
Upload a new document.

**Request:**
```json
{
  "name": "invoice-2024.pdf",
  "contentType": "application/pdf",
  "fileSize": 1048576,
  "metadata": {
    "department": "finance",
    "tags": ["invoice", "2024"]
  },
  "uploadedBy": "user@example.com"
}
```

**Response (201 Created):**
```json
{
  "success": true,
  "message": "Document uploaded successfully",
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "invoice-2024.pdf",
    "contentType": "application/pdf",
    "fileSize": 1048576,
    "status": "UPLOADED",
    "metadata": {
      "department": "finance",
      "tags": ["invoice", "2024"]
    },
    "createdAt": "2024-02-13T10:30:00Z",
    "updatedAt": "2024-02-13T10:30:00Z",
    "createdBy": "user@example.com"
  },
  "timestamp": "2024-02-13T10:30:00Z"
}
```

#### GET /api/documents/{id}
Retrieve document by ID.

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "invoice-2024.pdf",
    ...
  },
  "timestamp": "2024-02-13T10:30:00Z"
}
```

**Error Response (404 Not Found):**
```json
{
  "error": "Not Found",
  "message": "Document not found with ID: 550e8400-e29b-41d4-a716-446655440000",
  "status": 404,
  "path": "/api/documents/550e8400-e29b-41d4-a716-446655440000",
  "timestamp": "2024-02-13T10:30:00Z"
}
```

---

### 4. **Validation**

Bean Validation (Jakarta Validation) on request DTOs:

```java
@NotBlank(message = "Document name is required")
@Size(max = 255, message = "Document name must not exceed 255 characters")
private String name;

@NotNull(message = "File size is required")
@Positive(message = "File size must be positive")
private Long fileSize;
```

**Validation Error Response (400 Bad Request):**
```json
{
  "error": "Validation Failed",
  "message": "Invalid request parameters",
  "status": 400,
  "path": "/api/documents",
  "timestamp": "2024-02-13T10:30:00Z",
  "fieldErrors": [
    {
      "field": "name",
      "message": "Document name is required",
      "rejectedValue": null
    },
    {
      "field": "fileSize",
      "message": "File size must be positive",
      "rejectedValue": -100
    }
  ]
}
```

---

### 5. **Project Structure**

```
ingestion-service/
├── src/main/java/com/eda/lab/ingestion/
│   ├── IngestionServiceApplication.java
│   ├── api/
│   │   ├── controller/
│   │   │   └── DocumentController.java          # REST endpoints
│   │   ├── dto/
│   │   │   ├── UploadDocumentRequest.java       # Request DTO
│   │   │   └── DocumentResponse.java            # Response DTO
│   │   └── exception/
│   │       └── GlobalExceptionHandler.java      # Error handling
│   ├── domain/
│   │   ├── entity/
│   │   │   ├── Document.java                    # JPA entity
│   │   │   └── OutboxEvent.java                 # Outbox entity
│   │   ├── repository/
│   │   │   ├── DocumentRepository.java          # Spring Data JPA
│   │   │   └── OutboxEventRepository.java
│   │   └── service/
│   │       └── DocumentService.java             # Business logic
│   └── config/
│       └── JacksonConfig.java                   # JSON configuration
└── src/main/resources/
    ├── application.yml
    └── db/migration/
        ├── V1__init_schema.sql                  # Outbox table (from skeleton)
        ├── V2__create_documents_table.sql       # Documents table
        └── V3__create_outbox_events_table.sql   # Enhanced outbox
```

---

## 🎯 EDA Best Practices Applied

### 1. **Transactional Outbox**
✅ Events stored in same transaction as business data  
✅ No dual-write problem  
✅ Database as source of truth

### 2. **Event Immutability**
✅ Events use Java Records (immutable)  
✅ Event payload stored as JSON (can't be modified after creation)

### 3. **Idempotency Support**
✅ `event_id` is unique (consumers can detect duplicates)  
✅ Stored in outbox for at-least-once delivery

### 4. **Retry Logic Ready**
✅ `retry_count` and `next_retry_at` fields  
✅ `last_error` for debugging  
✅ Repository methods for finding retry-able events

### 5. **Observability**
✅ Structured logging with SLF4J  
✅ Status tracking (PENDING/SENT/FAILED)  
✅ Timestamps for monitoring

---

## 🚫 What's NOT Implemented Yet

1. **Outbox Publisher** (Next step)
   - Background job to publish PENDING events to RabbitMQ
   - Will be implemented in next iteration

2. **RabbitMQ Integration**
   - Event publishing to message broker
   - Queue/exchange configuration

3. **Metrics & Monitoring**
   - Prometheus metrics
   - Health indicators for outbox

4. **Archival/Cleanup**
   - Old SENT events cleanup
   - Retention policies

---

## 🧪 How to Test

### 1. Start Infrastructure
```bash
docker compose -f docker-compose.infra.yml up -d
```

### 2. Run Service
```bash
cd ingestion-service
../mvnw spring-boot:run
```

### 3. Upload Document
```bash
curl -X POST http://localhost:8081/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{
    "name": "test-document.pdf",
    "contentType": "application/pdf",
    "fileSize": 1024,
    "uploadedBy": "test-user"
  }'
```

### 4. Verify in Database
```sql
-- Check document
SELECT * FROM documents;

-- Check outbox event (should be PENDING)
SELECT * FROM outbox_events;
```

### 5. Get Document
```bash
curl http://localhost:8081/api/v1/documents/{id}
```

---

## 📚 Key Learning Points

### Transactional Outbox Pattern
**Problem it solves:**
```
Without Outbox:
1. Save document ✅
2. Publish to RabbitMQ ❌ (network fails)
Result: Document saved but no event → inconsistency!

With Outbox:
1. Save document ✅
2. Save outbox event ✅ (same transaction)
3. Background job publishes event later
Result: Guaranteed consistency!
```

### Why JSONB for Payload?
- ✅ Flexible event schema evolution
- ✅ Can query event contents if needed
- ✅ No need to deserialize for storage
- ✅ PostgreSQL has excellent JSONB support

### Why Separate Repositories?
- ✅ Single Responsibility Principle
- ✅ Clear separation of concerns
- ✅ Easier to test
- ✅ Follows Spring Data JPA conventions

---

## 🎓 Next Steps

1. Implement Outbox Publisher (background job)
2. Add RabbitMQ configuration
3. Publish events to RabbitMQ
4. Add metrics and monitoring
5. Implement retry logic with exponential backoff

This implementation provides a solid foundation for reliable event-driven architecture! 🚀
