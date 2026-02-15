package com.eda.lab.validation.outbox;

import com.eda.lab.validation.domain.entity.OutboxEvent;
import com.eda.lab.validation.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
 * - Publishes to doc.events exchange with appropriate routing keys
 * 
 * Routing Keys:
 * - DocumentValidated → "document.validated"
 * - DocumentRejected → "document.rejected"
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;

    // Configuration (can be externalized to application.yml)
    private static final int BATCH_SIZE = 50;
    private static final int MAX_RETRIES = 10;
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(10);
    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(1);
    private static final String EXCHANGE_NAME = "doc.events";

    /**
     * Scheduled polling for pending outbox events.
     * Runs every 2 seconds with fixed delay (waits for previous execution to complete).
     */
    @Scheduled(fixedDelay = 2000)
    public void publishPendingEvents() {
        try {
            // Fetch batch of pending events (only those ready to publish)
            Pageable pageable = PageRequest.of(0, BATCH_SIZE);
            Instant now = Instant.now();
            List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents(now, pageable);

            if (pendingEvents.isEmpty()) {
                log.trace("No pending outbox events to publish");
                return;
            }

            log.info("Found {} pending outbox events to publish", pendingEvents.size());

            // Process each event
            int successCount = 0;
            int failureCount = 0;

            for (OutboxEvent event : pendingEvents) {
                boolean success = publishEvent(event);
                if (success) {
                    successCount++;
                } else {
                    failureCount++;
                }
            }

            log.info("Outbox publish batch completed. Success: {}, Failures: {}", 
                    successCount, failureCount);

        } catch (Exception e) {
            log.error("Error during outbox publishing", e);
            // Don't rethrow - let the scheduler continue
        }
    }

    /**
     * Publish a single outbox event to RabbitMQ.
     * 
     * @param outboxEvent The event to publish
     * @return true if successful, false otherwise
     */
    @Transactional
    protected boolean publishEvent(OutboxEvent outboxEvent) {
        try {
            log.debug("Publishing outbox event: eventId={}, eventType={}, aggregateId={}", 
                    outboxEvent.getEventId(), 
                    outboxEvent.getEventType(),
                    outboxEvent.getAggregateId());

            // Build RabbitMQ message
            Message message = buildMessage(outboxEvent);

            // Determine routing key based on event type
            String routingKey = getRoutingKey(outboxEvent.getEventType());

            // Publish to exchange
            rabbitTemplate.send(EXCHANGE_NAME, routingKey, message);

            // Mark as sent
            markAsSent(outboxEvent);

            log.info("Successfully published event: eventId={}, eventType={}, aggregateId={}", 
                    outboxEvent.getEventId(),
                    outboxEvent.getEventType(),
                    outboxEvent.getAggregateId());

            return true;

        } catch (Exception e) {
            log.error("Failed to publish event: eventId={}, eventType={}, attempt={}", 
                    outboxEvent.getEventId(),
                    outboxEvent.getEventType(),
                    outboxEvent.getRetryCount() + 1, 
                    e);

            // Handle failure - retry or mark as failed
            handlePublishFailure(outboxEvent, e);

            return false;
        }
    }

    /**
     * Build RabbitMQ message from outbox event.
     */
    private Message buildMessage(OutboxEvent outboxEvent) {
        MessageProperties properties = new MessageProperties();
        
        // Message ID for deduplication/idempotency
        properties.setMessageId(outboxEvent.getEventId().toString());
        
        // Content type
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        
        // Headers for routing and filtering
        properties.setHeader("eventType", outboxEvent.getEventType());
        properties.setHeader("aggregateType", outboxEvent.getAggregateType());
        properties.setHeader("aggregateId", outboxEvent.getAggregateId().toString());
        properties.setHeader("publishedAt", Instant.now().toString());
        
        // Timestamp
        properties.setTimestamp(java.util.Date.from(Instant.now()));

        // Build message with payload
        return MessageBuilder
                .withBody(outboxEvent.getPayloadJson().getBytes())
                .andProperties(properties)
                .build();
    }

    /**
     * Get routing key based on event type.
     */
    private String getRoutingKey(String eventType) {
        return switch (eventType) {
            case "DocumentValidated" -> "document.validated";
            case "DocumentRejected" -> "document.rejected";
            default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
        };
    }

    /**
     * Mark outbox event as successfully sent.
     */
    private void markAsSent(OutboxEvent outboxEvent) {
        outboxEvent.markAsSent();
        outboxEventRepository.save(outboxEvent);
    }

    /**
     * Handle publish failure - retry or mark as failed.
     */
    private void handlePublishFailure(OutboxEvent outboxEvent, Exception error) {
        int newRetryCount = outboxEvent.getRetryCount() + 1;

        if (newRetryCount >= MAX_RETRIES) {
            // Max retries exceeded - mark as permanently failed
            log.error("Max retries exceeded for event: eventId={}, eventType={}. Marking as FAILED.", 
                    outboxEvent.getEventId(),
                    outboxEvent.getEventType());

            outboxEvent.setStatus(OutboxEvent.OutboxStatus.FAILED);
            outboxEvent.setRetryCount(newRetryCount);
            outboxEvent.setLastError(truncateError(error.getMessage()));
            outboxEvent.setNextRetryAt(null);
            
        } else {
            // Schedule retry with exponential backoff
            Instant nextRetry = calculateNextRetry(newRetryCount);
            
            log.warn("Will retry event: eventId={}, eventType={}, attempt={}, nextRetry={}", 
                    outboxEvent.getEventId(),
                    outboxEvent.getEventType(),
                    newRetryCount, 
                    nextRetry);

            outboxEvent.setRetryCount(newRetryCount);
            outboxEvent.setLastError(truncateError(error.getMessage()));
            outboxEvent.setNextRetryAt(nextRetry);
            // Status remains PENDING for retry
        }

        outboxEventRepository.save(outboxEvent);
    }

    /**
     * Calculate next retry time using exponential backoff.
     * Formula: initialDelay * (2 ^ retryCount) capped at maxDelay
     */
    private Instant calculateNextRetry(int retryCount) {
        long initialDelaySeconds = INITIAL_RETRY_DELAY.getSeconds();
        long maxDelaySeconds = MAX_RETRY_DELAY.getSeconds();
        
        // Exponential backoff: initialDelay * (2 ^ retryCount)
        long delaySeconds = initialDelaySeconds * (long) Math.pow(2, retryCount - 1);
        
        // Cap at max delay
        delaySeconds = Math.min(delaySeconds, maxDelaySeconds);
        
        return Instant.now().plus(Duration.ofSeconds(delaySeconds));
    }

    /**
     * Truncate error message to avoid storing huge stack traces.
     */
    private String truncateError(String error) {
        if (error == null) {
            return "Unknown error";
        }
        return error.length() > 500 ? error.substring(0, 500) + "..." : error;
    }
}
