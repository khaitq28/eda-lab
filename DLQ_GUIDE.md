# Dead Letter Queue (DLQ) in This Project — A–Z Guide

This document explains how **DLQ** (Dead Letter Queue) is used in this EDA project: the **two different “DLQ” mechanisms**, where messages go when things fail, and how to process them manually.

---

## Table of Contents

1. [Two Different “DLQ” Mechanisms](#1-two-different-dlq-mechanisms)
2. [Summary Table](#2-summary-table)
3. [Consumer-Side DLQ (RabbitMQ)](#3-consumer-side-dlq-rabbitmq)
4. [Outbox-Side “DLQ” (Database FAILED)](#4-outbox-side-dlq-database-failed)
5. [DocumentValidatedConsumer: What Happens When processMessage Fails](#5-documentvalidatedconsumer-what-happens-when-processmessage-fails)
6. [Where Exactly Is the Message Sent to the RabbitMQ DLQ?](#6-where-exactly-is-the-message-sent-to-the-rabbitmq-dlq)
7. [How to Process DLQ Messages Manually](#7-how-to-process-dlq-messages-manually)
8. [How to Process Outbox FAILED Events Manually](#8-how-to-process-outbox-failed-events-manually)
9. [All DLQ / FAILED Cases in One Place](#9-all-dlq--failed-cases-in-one-place)
10. [Monitoring and Alerts](#10-monitoring-and-alerts)

---

## 1. Two Different “DLQ” Mechanisms

In this project there are **two** failure paths that are both referred to as “DLQ-like” behavior:

| Mechanism | Where it lives | When it triggers | What “DLQ” means here |
|-----------|----------------|------------------|------------------------|
| **Consumer-side DLQ** | **RabbitMQ** (queues like `document.validated.dlq`) | Consumer fails to process a message (e.g. `DocumentValidatedConsumer.processMessage` throws) and **max retries** are exhausted | Message is **rejected without requeue** → RabbitMQ routes it to the **Dead Letter Exchange (DLX)** → message lands in a **RabbitMQ DLQ**. You process these **in RabbitMQ** (inspect, republish, etc.). |
| **Outbox-side “DLQ”** | **Database** (`outbox_events.status = 'FAILED'`) | OutboxPublisher fails to **publish** an event to RabbitMQ and **max retries** are exhausted | Event is **saved in the same table** with `status = FAILED`. **No** message is sent to any RabbitMQ queue. You process these **in the DB** (query FAILED, fix cause, optionally reset to PENDING). |

So:

- **Consumer KO** (e.g. enrichment fails) → after retries → message **is** sent to a **RabbitMQ DLQ**.
- **Outbox publish KO** (e.g. RabbitMQ down) → after retries → event **is not** sent to any queue; it is only stored as **FAILED** in the DB.

---

## 2. Summary Table

| Scenario | Failure point | After max retries | Where to look | How to process manually |
|----------|----------------|-------------------|---------------|--------------------------|
| Validation consumer fails (e.g. processMessage throws) | validation-service | Message → **RabbitMQ** `document.uploaded.dlq` | RabbitMQ Management UI / AMQP | Republish from DLQ to main queue or fix and replay (see [§7](#7-how-to-process-dlq-messages-manually)) |
| Enrichment consumer fails (processMessage throws) | enrichment-service | Message → **RabbitMQ** `document.validated.dlq` | RabbitMQ Management UI / AMQP | Same as above |
| Audit consumer fails | audit-service | Message → **RabbitMQ** `document.audit.dlq` | RabbitMQ Management UI / AMQP | Same as above |
| Notification consumer fails | notification-service | Message → **RabbitMQ** `document.notification.dlq` | RabbitMQ Management UI / AMQP | Same as above |
| Outbox publisher fails to publish (any service) | e.g. enrichment OutboxPublisher | **No** RabbitMQ DLQ; row in DB with `status = FAILED` | DB: `outbox_events` where `status = 'FAILED'` | Fix cause, optionally set `status = 'PENDING'` to retry (see [§8](#8-how-to-process-outbox-failed-events-manually)) |

---

## 3. Consumer-Side DLQ (RabbitMQ)

### 3.1 Topology

- **Main queue** (e.g. `document.validated.q`) is declared with:
  - `x-dead-letter-exchange` = DLX (e.g. `doc.dlx`)
  - `x-dead-letter-routing-key` = routing key to the DLQ (e.g. `document.validated.dlq`)
- **Dead Letter Exchange (DLX)** = e.g. `doc.dlx` (Direct, durable).
- **Dead Letter Queue (DLQ)** = e.g. `document.validated.dlq` (durable), bound to DLX with that routing key.

When a message is **rejected (NACK) with requeue = false** from the main queue, **RabbitMQ** automatically republishes it to the DLX; the DLX routes it into the corresponding DLQ. Our application code does **not** publish to the DLQ; it only triggers the reject.

### 3.2 Per-Service Queues and DLQs

| Service | Main queue | DLX | DLQ | Config / consumer |
|---------|------------|-----|-----|--------------------|
| validation-service | `document.uploaded.q` | `doc.dlx` | `document.uploaded.dlq` | `RabbitMQConfig` + `DocumentUploadedConsumer` |
| enrichment-service | `document.validated.q` | `doc.dlx` | `document.validated.dlq` | `RabbitMQConfig` + `DocumentValidatedConsumer` |
| audit-service | `document.audit.q` | `doc.dlx` | `document.audit.dlq` | `RabbitMQConfig` + `DocumentEventConsumer` |
| notification-service | `document.notification.q` | `doc.dlx` | `document.notification.dlq` | `RabbitMQConfig` + `DocumentEventConsumer` |

Note: ingestion-service declares `doc.events.dlx` and `document.uploaded.dlq` for its topology; validation-service uses `doc.dlx` for the same logical flow. For consumer-side DLQ behavior, the important part is that the **main queue** (e.g. `document.uploaded.q`) has a dead-letter exchange so that rejected messages end up in a DLQ.

### 3.3 Retry Then DLQ

- **Retry interceptor** (e.g. in `RabbitMQConfig`): `maxAttempts = 5`, exponential backoff (e.g. 1s, 2s, 4s, 8s, 10s).
- **Recoverer**: `RejectAndDontRequeueRecoverer`.
- Flow:
  1. Listener (e.g. `DocumentValidatedConsumer.handleDocumentValidated`) runs.
  2. If `processMessage` throws, the retry interceptor catches and retries up to 5 times with backoff.
  3. After the last failed attempt, the **Recoverer** runs: it **rejects the message with requeue = false**.
  4. RabbitMQ then **moves the message** to the DLX → DLQ (see [§6](#6-where-exactly-is-the-message-sent-to-the-rabbitmq-dlq)).

---

## 4. Outbox-Side “DLQ” (Database FAILED)

- **OutboxPublisher** (e.g. in enrichment-service) reads `outbox_events` with `status = 'PENDING'` (and due for retry).
- On **publish failure** it retries with exponential backoff; on each failure it updates `retry_count`, `last_error`, `next_retry_at` and saves (status remains PENDING).
- When **retry_count >= max retries** (e.g. 10), it calls `markAsPermanentlyFailed(...)` and **saves** the row with **`status = 'FAILED'`**. It does **not** send anything to RabbitMQ; the “DLQ” here is simply **the row in the DB with status FAILED**.
- The publisher’s query only selects `status = 'PENDING'`, so FAILED rows are never picked up again automatically. Manual intervention: query FAILED rows, fix root cause, optionally set `status = 'PENDING'` to retry (see [§8](#8-how-to-process-outbox-failed-events-manually)).

---

## 5. DocumentValidatedConsumer: What Happens When processMessage Fails

**Normal event from previous service (e.g. DocumentValidated):**

1. Message is consumed from `document.validated.q`.
2. `handleDocumentValidated` → `processMessage(message)`.
3. If **processMessage completes without throwing**: message is **ACKed**; no DLQ.

If **processMessage throws** (e.g. JSON parse error, DB down, or any `RuntimeException`):

1. The method throws; the **retry interceptor** catches.
2. Message is **not** ACKed; Spring AMQP / RabbitMQ will **retry** according to the interceptor (e.g. 5 attempts with backoff).
3. After the last attempt, **RejectAndDontRequeueRecoverer** runs: it **rejects the message with requeue = false**.
4. Because `document.validated.q` has `x-dead-letter-exchange` and `x-dead-letter-routing-key` set, **RabbitMQ** republishes the message to the DLX → the message **lands in `document.validated.dlq`**.

So: **consumer process KO → retries → then message is sent to the RabbitMQ DLQ by RabbitMQ**, not by our code. Our code only triggers this by throwing and then by the recoverer rejecting without requeue.

---

## 6. Where Exactly Is the Message Sent to the RabbitMQ DLQ?

- **Not** in application code: we do **not** call `rabbitTemplate.send(...)` to the DLQ.
- The flow is:
  1. Listener throws → retry interceptor retries → after last failure, **RejectAndDontRequeueRecoverer** runs.
  2. Recoverer calls **channel.basicReject(deliveryTag, requeue = false)** (conceptually).
  3. When a message is rejected with **requeue = false** from a queue that has **x-dead-letter-exchange** set, **RabbitMQ** automatically:
     - Publishes the message to the **Dead Letter Exchange** with the **x-dead-letter-routing-key** (and adds headers like `x-death`).
     - The DLX routes to the bound queue → **message appears in the DLQ** (e.g. `document.validated.dlq`).

So the “sending” to the DLQ is done **by the broker** when we reject without requeue; the application only triggers that by failing after all retries and using `RejectAndDontRequeueRecoverer`.

---

## 7. How to Process DLQ Messages Manually

RabbitMQ DLQ messages are processed **outside** the main application consumers (no `@RabbitListener` on DLQs in this project). Options:

### 7.1 Inspect in RabbitMQ Management UI

1. Open `http://localhost:15672` (default guest/guest).
2. Go to **Queues**.
3. Open the DLQ (e.g. `document.validated.dlq`).
4. Use **Get messages** to inspect payload and headers (e.g. `x-death`, messageId, correlationId).

### 7.2 Republish to Main Queue (Replay)

- **Option A – Management UI**: Some setups allow “Move message” or “Publish to another queue”. If not available, use Option B or C.
- **Option B – Script/tool**: Consume one (or more) messages from the DLQ, then publish the same body and important headers (e.g. `messageId`, `correlationId`) to the **main exchange** with the **original routing key** (e.g. `document.validated`), so the normal consumer can process it again. Then ACK (or delete) from the DLQ.
- **Option C – Admin/republish service**: Implement a small admin endpoint or script that:
  1. Reads from the DLQ (e.g. with `RabbitTemplate.receive` or a one-off listener).
  2. Publishes to `doc.events` with the correct routing key.
  3. Acknowledges the message from the DLQ so it is removed.

Before republishing: fix the underlying cause (e.g. DB up, valid payload) so the consumer does not fail again and send the message back to the DLQ.

### 7.3 Discard or Archive

- To **discard**: Purge the DLQ or consume and ACK without republishing.
- To **archive**: Consume, store payload (e.g. to S3, DB, or file), then ACK from the DLQ.

---

## 8. How to Process Outbox FAILED Events Manually

These are rows in `outbox_events` with `status = 'FAILED'` (no RabbitMQ DLQ).

1. **Find failed events** (per service DB):
   ```sql
   SELECT id, event_id, event_type, aggregate_id, retry_count, last_error, created_at
   FROM outbox_events
   WHERE status = 'FAILED'
   ORDER BY created_at DESC;
   ```
2. **Analyse** `last_error` and fix root cause (e.g. broker connectivity, network, serialization).
3. **Retry** (optional): set status back to PENDING and clear retry metadata so the publisher picks it up again:
   ```sql
   UPDATE outbox_events
   SET status = 'PENDING', retry_count = 0, last_error = NULL, next_retry_at = NULL
   WHERE id = '<failed-event-id>';
   ```
4. **Leave as FAILED** and alert: use for monitoring and manual follow-up (see [§10](#10-monitoring-and-alerts)).

---

## 9. All DLQ / FAILED Cases in One Place

| Case | Component | Condition | Result | Where it lives |
|------|-----------|------------|--------|----------------|
| Consumer fails (validation) | DocumentUploadedConsumer | processMessage throws; 5 retries exhausted | Message → RabbitMQ DLQ | `document.uploaded.dlq` |
| Consumer fails (enrichment) | DocumentValidatedConsumer | processMessage throws; 5 retries exhausted | Message → RabbitMQ DLQ | `document.validated.dlq` |
| Consumer fails (audit) | DocumentEventConsumer (audit) | processMessage throws; retries exhausted | Message → RabbitMQ DLQ | `document.audit.dlq` |
| Consumer fails (notification) | DocumentEventConsumer (notification) | processMessage throws; retries exhausted | Message → RabbitMQ DLQ | `document.notification.dlq` |
| Outbox publish fails (any service) | OutboxPublisher | Publish to RabbitMQ fails; max retries (e.g. 10) exceeded | Row saved with `status = FAILED` | DB: `outbox_events` |

---

## 10. Monitoring and Alerts

- **RabbitMQ DLQs**: Monitor queue depth of each `*.dlq`. Alert when depth > 0 or above a threshold; use Management API or Prometheus exporters.
- **Outbox FAILED**: Monitor `SELECT COUNT(*) FROM outbox_events WHERE status = 'FAILED'` per service (e.g. via metrics or scheduled checks). Alert when count > 0 or above a threshold.
- **Logs**: Search for “Retries exhausted”, “Marking as FAILED”, “OUTBOX_PUBLISH_FAILED” and “permanently failed” to correlate with DLQ or outbox FAILED state.

---

## Quick Reference

- **Consumer KO** → retries → **RabbitMQ DLQ** (message is moved by the broker after reject without requeue). Process via Management UI or republish/archive.
- **Outbox publish KO** → retries → **DB row with status FAILED** (no RabbitMQ DLQ). Process via SQL (inspect, fix, optionally set PENDING to retry).
- **DocumentValidatedConsumer.processMessage** throws → same as “Consumer KO” for `document.validated.q` → after 5 attempts message goes to **document.validated.dlq**.
