# Observability Implementation Complete

## Overview

This document describes the complete observability implementation across all services in the EDA Lab project, including JSON logging, MDC (Mapped Diagnostic Context), and correlation ID propagation for end-to-end traceability.

## What Was Implemented

### 1. JSON Logging to Console

All services now emit structured JSON logs to STDOUT using `logstash-logback-encoder`:

**Dependency added** (`pom.xml` for all services):
```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

**Logback configuration** (`src/main/resources/logback-spring.xml` for each service):
- One JSON object per line
- Standard fields: `timestamp`, `level`, `logger`, `message`, `stackTrace`
- MDC fields: `correlationId`, `eventId`, `documentId`, `routingKey`, `eventType`
- Service name in every log

### 2. Correlation ID Propagation

#### **ingestion-service** (Entry Point)
- **REST API** (`POST /documents`):
  - Accepts optional header `X-Correlation-Id`
  - Generates UUID if missing
  - Sets `correlationId` in MDC for all request logs
  - Persists `correlationId` in `documents` table
  - Includes `correlationId` in `DocumentUploadedEvent` payload
  
- **Outbox Publisher**:
  - Extracts `correlationId` from event payload
  - Sets `correlationId` in RabbitMQ message headers and properties

#### **validation-service** (Consumer)
- **Consumer** (`DocumentUploadedConsumer`):
  - Extracts `correlationId` from incoming message headers
  - Sets MDC context automatically using `MessageMdcContext.of()` (try-with-resources)
  - Extracts `correlationId` from payload and includes it in outbound events
  - Creates `DocumentValidatedEvent` or `DocumentRejectedEvent` with `correlationId`
  
- **Outbox Publisher**:
  - Extracts `correlationId` from event payload
  - Sets `correlationId` in RabbitMQ message headers

#### **enrichment-service** (Consumer)
- **Consumer** (`DocumentValidatedConsumer`):
  - Uses `MessageMdcContext.of()` to set MDC from message headers
  - Extracts `correlationId` from payload and includes it in outbound `DocumentEnrichedEvent`
  
- **Outbox Publisher**:
  - Extracts `correlationId` from event payload
  - Sets `correlationId` in RabbitMQ message headers

#### **audit-service** (Consumer)
- **Consumer** (`DocumentEventConsumer`):
  - Uses `MessageMdcContext.of()` to set MDC from message headers
  - Extracts `correlationId` from payload and stores it in `audit_log` table

#### **notification-service** (Consumer)
- **Consumer** (`DocumentEventConsumer`):
  - Uses `MessageMdcContext.of()` to set MDC from message headers
  - Extracts `correlationId` from payload and stores it in `notification_history` table

### 3. MDC Management Utilities

#### **MdcKeys** (`common/src/main/java/com/eda/lab/common/observability/MdcKeys.java`)
Centralized constants for MDC keys:
- `CORRELATION_ID`
- `EVENT_ID`
- `DOCUMENT_ID`
- `ROUTING_KEY`
- `EVENT_TYPE`

#### **MessageMdcContext** (`common/src/main/java/com/eda/lab/common/observability/MessageMdcContext.java`)
AutoCloseable utility for managing MDC in RabbitMQ consumers:
```java
try (var mdc = MessageMdcContext.of(message.getMessageProperties())) {
    // All logs in this block include correlationId, eventId, documentId, etc.
    log.info("EVENT_RECEIVED");
}
// MDC automatically cleared here
```

#### **RequestMdcInterceptor** (`ingestion-service/.../config/RequestMdcInterceptor.java`)
Spring MVC interceptor for REST requests:
- Extracts or generates `correlationId` from `X-Correlation-Id` header
- Sets `correlationId` in MDC for entire request
- Clears MDC after request completion

### 4. Logging Conventions

All services follow consistent logging conventions:

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

## Database Schema Changes

### **ingestion-service**
```sql
ALTER TABLE documents ADD COLUMN correlation_id VARCHAR(255);
CREATE INDEX idx_documents_correlation_id ON documents(correlation_id);
```

### **audit-service**
```sql
ALTER TABLE audit_log ADD COLUMN correlation_id VARCHAR(255);
CREATE INDEX idx_audit_log_correlation_id ON audit_log(correlation_id);
```

## How to Test

### 1. Rebuild and Restart Services

```bash
# From project root
docker compose down
docker compose up --build
```

### 2. Run End-to-End Test

```bash
chmod +x test-observability.sh
./test-observability.sh
```

**Expected output:**
- Correlation ID generated or accepted from header
- Document ID returned from ingestion-service
- JSON logs found in all services (ingestion, validation, enrichment, audit, notification)
- Document ID appears in logs across services

### 3. Manual Tracing

```bash
# Get correlation ID from test
CORRELATION_ID="your-correlation-id-here"

# Trace across all services
docker logs eda-ingestion-service 2>&1 | grep "$CORRELATION_ID"
docker logs eda-validation-service 2>&1 | grep "$CORRELATION_ID"
docker logs eda-enrichment-service 2>&1 | grep "$CORRELATION_ID"
docker logs eda-audit-service 2>&1 | grep "$CORRELATION_ID"
docker logs eda-notification-service 2>&1 | grep "$CORRELATION_ID"
```

### 4. Sample JSON Log Output

```json
{
  "@timestamp": "2026-02-17T23:13:49.663+0000",
  "@version": "1",
  "message": "EVENT_RECEIVED",
  "logger_name": "com.eda.lab.validation.messaging.consumer.DocumentUploadedConsumer",
  "thread_name": "org.springframework.amqp.rabbit.RabbitListenerEndpointContainer#0-1",
  "level": "INFO",
  "level_value": 20000,
  "eventId": "c6f9878b-625e-4807-9d81-eb1260eef908",
  "correlationId": "test-correlation-1771370027",
  "documentId": "d745f931-c6e5-4c35-8ece-8cc9e4a1844f",
  "eventType": "DocumentUploaded",
  "routingKey": "document.uploaded",
  "service": "validation-service"
}
```

## Benefits

### 1. **End-to-End Traceability**
- Track a single request/document through all services using `correlationId`
- No need for manual grep across plain text logs

### 2. **Structured Querying**
- Filter logs by `service`, `correlationId`, `documentId`, `eventType`, etc.
- Ready for Kibana/ELK, Datadog, Splunk, or any log aggregation tool

### 3. **Production-Ready**
- JSON logs are industry standard for microservices
- MDC ensures context is automatically included in all logs
- No need to manually pass context to every log statement

### 4. **Debugging**
- Quickly find all events related to a specific document
- Identify where a flow fails (which service, which event)
- Understand retry/DLQ behavior with structured context

## Architecture Patterns Used

1. **Correlation ID Pattern**: Unique ID propagated through entire event flow
2. **MDC (Mapped Diagnostic Context)**: Thread-local storage for log context
3. **Structured Logging**: JSON format for machine-readable logs
4. **AutoCloseable MDC Management**: Ensures MDC is always cleaned up
5. **Interceptor for REST Requests**: Centralized correlation ID management for HTTP
6. **Message Headers for Event Correlation**: Propagate correlation ID via RabbitMQ headers

## Next Steps (Optional)

1. **Deploy ELK Stack** (Elasticsearch, Logstash, Kibana):
   - Add to `docker-compose.yml`
   - Configure Logstash to consume Docker logs
   - Create Kibana dashboards for tracing

2. **Add Distributed Tracing**:
   - Spring Cloud Sleuth / Micrometer Tracing
   - Zipkin or Jaeger for trace visualization

3. **Metrics and Monitoring**:
   - Spring Boot Actuator metrics
   - Prometheus + Grafana
   - Custom metrics for event processing rates, DLQ counts, etc.

## Files Modified

### Common Module
- `common/pom.xml` (added logstash-logback-encoder)
- `common/src/main/java/com/eda/lab/common/observability/MdcKeys.java` (new)
- `common/src/main/java/com/eda/lab/common/observability/MessageMdcContext.java` (new)

### Ingestion Service
- `ingestion-service/pom.xml` (added logstash-logback-encoder)
- `ingestion-service/src/main/resources/logback-spring.xml` (new)
- `ingestion-service/src/main/resources/db/migration/V4__add_correlation_id_to_documents.sql` (new)
- `ingestion-service/src/main/java/com/eda/lab/ingestion/domain/entity/Document.java` (added correlationId field)
- `ingestion-service/src/main/java/com/eda/lab/ingestion/api/dto/DocumentResponse.java` (added correlationId field)
- `ingestion-service/src/main/java/com/eda/lab/ingestion/domain/service/DocumentService.java` (correlation ID handling)
- `ingestion-service/src/main/java/com/eda/lab/ingestion/outbox/OutboxPublisher.java` (correlation ID propagation)
- `ingestion-service/src/main/java/com/eda/lab/ingestion/config/RequestMdcInterceptor.java` (new)
- `ingestion-service/src/main/java/com/eda/lab/ingestion/config/WebMvcConfig.java` (new)

### Validation Service
- `validation-service/pom.xml` (added logstash-logback-encoder)
- `validation-service/src/main/resources/logback-spring.xml` (new)
- `validation-service/src/main/java/com/eda/lab/validation/messaging/consumer/DocumentUploadedConsumer.java` (MDC + correlation ID)
- `validation-service/src/main/java/com/eda/lab/validation/outbox/OutboxPublisher.java` (correlation ID propagation)

### Enrichment Service
- `enrichment-service/pom.xml` (added logstash-logback-encoder)
- `enrichment-service/src/main/resources/logback-spring.xml` (new)
- `enrichment-service/src/main/java/com/eda/lab/enrichment/messaging/consumer/DocumentValidatedConsumer.java` (MDC + correlation ID)
- `enrichment-service/src/main/java/com/eda/lab/enrichment/outbox/OutboxPublisher.java` (correlation ID propagation)

### Audit Service
- `audit-service/pom.xml` (added logstash-logback-encoder)
- `audit-service/src/main/resources/logback-spring.xml` (new)
- `audit-service/src/main/resources/db/migration/V2__add_correlation_id_to_audit_log.sql` (new)
- `audit-service/src/main/java/com/eda/lab/audit/domain/entity/AuditLog.java` (added correlationId field)
- `audit-service/src/main/java/com/eda/lab/audit/messaging/consumer/DocumentEventConsumer.java` (MDC + correlation ID)

### Notification Service
- `notification-service/pom.xml` (added logstash-logback-encoder)
- `notification-service/src/main/resources/logback-spring.xml` (new)
- `notification-service/src/main/resources/db/migration/V2__add_correlation_id_to_notification_history.sql` (new)
- `notification-service/src/main/java/com/eda/lab/notification/domain/entity/NotificationHistory.java` (added correlationId field)
- `notification-service/src/main/java/com/eda/lab/notification/messaging/consumer/DocumentEventConsumer.java` (MDC + correlation ID)

### Test Scripts
- `test-observability.sh` (E2E observability test)
- `rebuild-observability.sh` (rebuild and test script)

## Summary

✅ **JSON logging implemented** across all 5 services  
✅ **Correlation ID propagation** from ingestion → validation → enrichment → audit/notification  
✅ **MDC management** for automatic context inclusion in logs  
✅ **Database persistence** of correlation IDs for traceability  
✅ **Test scripts** for verification  
✅ **Production-ready** observability implementation

The system is now fully observable and traceable across all microservices!
