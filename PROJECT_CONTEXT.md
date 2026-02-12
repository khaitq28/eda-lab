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

### 7.1 Transactional Outbox

- Used by services that publish events
- Business data and Outbox event are stored in the same transaction
- A background publisher process reads pending Outbox records and publishes them to RabbitMQ
- After successful publish, the Outbox record is marked as SENT

### 7.2 Idempotent Consumer

- Each consumer maintains an "inbox" or "processed_events" table
- Before processing a message, the consumer checks if the eventId was already processed
- If yes, the message is ignored safely

### 7.3 Retry and Dead Letter Queue (DLQ)

- Message consumption must be configured with retry and backoff
- If a message keeps failing after N retries, it is sent to a Dead Letter Queue
- DLQ can be inspected to analyze poison messages

---

## 8. Testing Strategy

### 8.1 Local Development

- Docker Compose is used to run:
  - RabbitMQ
  - PostgreSQL for each service
  - All
