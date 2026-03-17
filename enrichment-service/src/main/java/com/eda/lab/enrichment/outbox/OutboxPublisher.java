package com.eda.lab.enrichment.outbox;

import com.eda.lab.enrichment.domain.entity.OutboxEvent;
import com.eda.lab.enrichment.domain.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Outbox Publisher - Publishes pending outbox events to RabbitMQ.
 *
 * This component implements the "Publisher" part of the Transactional Outbox pattern:
 * 1. Polls for PENDING events from the database
 * 2. Publishes them to RabbitMQ
 * 3. Marks them as SENT on success
 * 4. Implements retry logic with exponential backoff on failure
 *
 * DLQ (Dead Letter Queue) pattern — outbox side:
 * - Here "DLQ" means: events that cannot be published after max retries are not sent to
 *   a RabbitMQ queue; they are persisted in the same outbox table with status FAILED.
 * - On publish failure: we retry with exponential backoff (see handlePublishFailure).
 * - After max retries: we save the outbox event to the DB with status FAILED
 *   (markAsPermanentlyFailed + save). The event stays in outbox_events, never goes to RabbitMQ.
 * - Then what?
 *   - The publisher stops retrying (findPendingEvents only selects status = 'PENDING').
 *   - FAILED rows are for manual intervention: operators query them (e.g. by status or last_error),
 *     fix root cause (e.g. broker/network), then either reset status to PENDING to retry, or
 *     archive/alert. Monitoring/alerting on count of FAILED events is recommended.
 *
 * Key Features:
 * - Fixed-delay scheduling (prevents overlapping executions)
 * - Batch processing for efficiency
 * - Exponential backoff for retries
 * - Publishes to doc.events exchange with routing key "document.enriched"
 *
 * Concurrency Safety:
 * - SAFE for multiple instances
 * - Uses SELECT FOR UPDATE SKIP LOCKED in repository query
 * - Each instance locks different rows → no duplicate publishing
 * - PostgreSQL 9.5+ required (SKIP LOCKED support)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    // Configuration (can be externalized to application.yml)
    private static final int BATCH_SIZE = 50;
    private static final int MAX_RETRIES = 10;
    private static final long INITIAL_RETRY_DELAY_SECONDS = 10;
    private static final String EXCHANGE = "doc.events";
    private static final String ROUTING_KEY_ENRICHED = "document.enriched";

    /**
     * Scheduled job to publish pending outbox events.
     * Runs every 2 seconds with fixed delay (waits for previous execution to complete).
     */
    @Scheduled(fixedDelay = 2000)
    public void publishPendingEvents() {
        try {
            // Fetch batch of pending events (with row-level locking for multi-instance safety)
            Instant now = Instant.now();
            List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents(now, BATCH_SIZE);

            if (pendingEvents.isEmpty()) {
                log.trace("No pending outbox events to publish");
                return;
            }

            log.info("Found {} pending outbox events to publish", pendingEvents.size());

            // Publish each event
            for (OutboxEvent event : pendingEvents) {
                publishEvent(event);
            }

        } catch (Exception e) {
            log.error("Error in outbox publisher: {}", e.getMessage(), e);
        }
    }

    /**
     * Publish a single outbox event to RabbitMQ.
     * 
     * @param event Outbox event to publish
     */
    @Transactional
    protected void publishEvent(OutboxEvent event) {
        try {
            log.debug("Publishing event: eventId={}, eventType={}, aggregateId={}, retryCount={}", 
                    event.getEventId(), event.getEventType(), event.getAggregateId(), event.getRetryCount());

            // Determine routing key based on event type
            String routingKey = getRoutingKey(event.getEventType());

            // Extract correlation ID from payload
            String correlationId = extractCorrelationId(event.getPayloadJson());
            
            // Build message with properties
            var builder = MessageBuilder.withBody(event.getPayloadJson().getBytes())
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setMessageId(event.getEventId().toString())
                    .setHeader("eventType", event.getEventType())
                    .setHeader("aggregateId", event.getAggregateId().toString())
                    .setHeader("aggregateType", event.getAggregateType())
                    .setTimestamp(java.util.Date.from(event.getCreatedAt()));
            
            // Add correlation ID if present
            if (correlationId != null) {
                builder.setHeader("correlationId", correlationId);
                builder.setCorrelationId(correlationId);
            }
            
            Message message = builder.build();

            // Publish to RabbitMQ
            rabbitTemplate.send(EXCHANGE, routingKey, message);

            // Mark as sent
            event.markAsSent();
            outboxEventRepository.save(event);

            log.info("OUTBOX_PUBLISH_SUCCESS: eventId={}, eventType={}", 
                    event.getEventId(), event.getEventType());

        } catch (Exception e) {
            handlePublishFailure(event, e);
        }
    }

    /**
     * Handle publish failure with retry logic (DLQ path: retry then FAILED in DB).
     * <ul>
     *   <li>Under max retries: increment retry count, set last_error and next_retry_at
     *       (exponential backoff), save; publisher will pick it up again when next_retry_at has passed.</li>
     *   <li>At or over max retries: mark as permanently failed (status = FAILED), save to DB.
     *       Publisher will not pick it up again (only PENDING rows are selected). Operators must
     *       query FAILED events, fix cause, and optionally set status back to PENDING to retry.</li>
     * </ul>
     *
     * @param event Event that failed to publish
     * @param error Exception that occurred
     */
    @Transactional
    protected void handlePublishFailure(OutboxEvent event, Exception error) {
        log.error("OUTBOX_PUBLISH_FAILED: eventId={}, eventType={}, retryCount={}, error={}",
                event.getEventId(), event.getEventType(), event.getRetryCount(), error.getMessage());

        if (event.getRetryCount() >= MAX_RETRIES) {
            // DLQ: Max retries exceeded — persist as FAILED in DB (outbox "dead letter"); no RabbitMQ DLQ.
            event.markAsPermanentlyFailed(
                    String.format("Max retries (%d) exceeded. Last error: %s", MAX_RETRIES, error.getMessage())
            );
            outboxEventRepository.save(event);

            log.error("Event permanently failed after {} retries: eventId={}, eventType={}. " +
                    "Event is now FAILED in DB; no automatic retry. Manual intervention or reset to PENDING to retry.",
                    MAX_RETRIES, event.getEventId(), event.getEventType());
        } else {
            // Schedule retry with exponential backoff; status stays PENDING, next run will re-select when next_retry_at <= now.
            event.markAsFailed(error.getMessage(), INITIAL_RETRY_DELAY_SECONDS);
            outboxEventRepository.save(event);

            Duration nextRetryIn = Duration.between(Instant.now(), event.getNextRetryAt());
            log.warn("Event will be retried in {} seconds: eventId={}, retryCount={}",
                    nextRetryIn.getSeconds(), event.getEventId(), event.getRetryCount());
        }
    }

    /**
     * Get routing key for event type.
     * 
     * @param eventType Event type
     * @return Routing key
     */
    private String getRoutingKey(String eventType) {
        return switch (eventType) {
            case "DocumentEnriched" -> ROUTING_KEY_ENRICHED;
            default -> {
                log.warn("Unknown event type: {}. Using default routing key.", eventType);
                yield "document.unknown";
            }
        };
    }
    
    /**
     * Extract correlation ID from event payload JSON.
     */
    private String extractCorrelationId(String payloadJson) {
        try {
            var jsonNode = objectMapper.readTree(payloadJson);
            if (jsonNode.has("correlationId")) {
                return jsonNode.get("correlationId").asText();
            }
        } catch (Exception e) {
            log.warn("Failed to extract correlationId from payload", e);
        }
        return null;
    }
}
