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
 * Key Features:
 * - Fixed-delay scheduling (prevents overlapping executions)
 * - Batch processing for efficiency
 * - Exponential backoff for retries
 * - Publishes to doc.events exchange with routing key "document.enriched"
 * 
 * Concurrency Safety:
 * - ✅ SAFE for multiple instances
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
     * Handle publish failure with retry logic.
     * 
     * @param event Event that failed to publish
     * @param error Exception that occurred
     */
    @Transactional
    protected void handlePublishFailure(OutboxEvent event, Exception error) {
        log.error("OUTBOX_PUBLISH_FAILED: eventId={}, eventType={}, retryCount={}, error={}", 
                event.getEventId(), event.getEventType(), event.getRetryCount(), error.getMessage());

        if (event.getRetryCount() >= MAX_RETRIES) {
            // Max retries exceeded - mark as permanently failed
            event.markAsPermanentlyFailed(
                    String.format("Max retries (%d) exceeded. Last error: %s", MAX_RETRIES, error.getMessage())
            );
            outboxEventRepository.save(event);

            log.error("Event permanently failed after {} retries: eventId={}, eventType={}", 
                    MAX_RETRIES, event.getEventId(), event.getEventType());
        } else {
            // Schedule retry with exponential backoff
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
