package com.eda.lab.ingestion.outbox;

import com.eda.lab.ingestion.config.OutboxProperties;
import com.eda.lab.ingestion.config.RabbitMQConfig;
import com.eda.lab.ingestion.domain.entity.OutboxEvent;
import com.eda.lab.ingestion.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * Key Design Decisions:
 * - Fixed-delay scheduling (not fixed-rate) to prevent overlapping executions
 * - Batch processing for efficiency (configurable batch size)
 * - Exponential backoff for retries
 * - Dead letter queue for permanently failed events
 * 
 * Concurrency Safety (Current Implementation):
 * - This implementation assumes a SINGLE instance of ingestion-service
 * - For multiple instances, you would need:
 *   1. SELECT FOR UPDATE SKIP LOCKED in the repository query
 *   2. Or distributed locking (Redis, DB advisory locks)
 *   3. Or leader election (Kubernetes leader election, Hazelcast)
 * 
 * Production Improvements:
 * - Add metrics (published count, failed count, retry count)
 * - Add distributed tracing (correlation IDs)
 * - Add circuit breaker for RabbitMQ failures
 * - Add health indicator based on outbox backlog
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
    prefix = "outbox.publisher",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final OutboxProperties properties;

    /**
     * Scheduled polling for pending outbox events.
     * 
     * Uses fixedDelayString to:
     * - Wait for previous execution to complete before starting next
     * - Configurable via application.yml
     * - Prevents overlapping executions
     * 
     * The #{@outboxProperties.pollingInterval.toMillis()} reads from configuration.
     */
    @Scheduled(fixedDelayString = "#{@outboxProperties.pollingInterval.toMillis()}")
    public void publishPendingEvents() {
        if (!properties.isEnabled()) {
            return;
        }

        try {
            // Fetch batch of pending events
            Pageable pageable = PageRequest.of(0, properties.getBatchSize());
            List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents(pageable);

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
            log.debug("Publishing outbox event: eventId={}, aggregateId={}, retryCount={}", 
                    outboxEvent.getEventId(), 
                    outboxEvent.getAggregateId(), 
                    outboxEvent.getRetryCount());

            // Build RabbitMQ message
            Message message = buildMessage(outboxEvent);

            // Publish to exchange with routing key
            rabbitTemplate.send(
                    RabbitMQConfig.EXCHANGE_NAME,
                    RabbitMQConfig.DOCUMENT_UPLOADED_ROUTING_KEY,
                    message
            );

            // Mark as sent
            markAsSent(outboxEvent);

            log.info("Successfully published event: eventId={}, aggregateId={}", 
                    outboxEvent.getEventId(), 
                    outboxEvent.getAggregateId());

            return true;

        } catch (Exception e) {
            log.error("Failed to publish event: eventId={}, aggregateId={}, attempt={}", 
                    outboxEvent.getEventId(), 
                    outboxEvent.getAggregateId(), 
                    outboxEvent.getRetryCount() + 1, 
                    e);

            // Handle failure - retry or mark as failed
            handlePublishFailure(outboxEvent, e);

            return false;
        }
    }

    /**
     * Build RabbitMQ message from outbox event.
     * Sets important message properties for consumer processing.
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
                .withBody(outboxEvent.getPayload().getBytes())
                .andProperties(properties)
                .build();
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
     * Implements exponential backoff for retry scheduling.
     */
    private void handlePublishFailure(OutboxEvent outboxEvent, Exception error) {
        int newRetryCount = outboxEvent.getRetryCount() + 1;

        if (newRetryCount >= properties.getMaxRetries()) {
            // Max retries exceeded - mark as permanently failed
            log.error("Max retries exceeded for event: eventId={}, aggregateId={}. Marking as FAILED.", 
                    outboxEvent.getEventId(), 
                    outboxEvent.getAggregateId());

            outboxEvent.setStatus(OutboxEvent.OutboxStatus.FAILED);
            outboxEvent.setRetryCount(newRetryCount);
            outboxEvent.setLastError(truncateError(error.getMessage()));
            outboxEvent.setNextRetryAt(null);
            
        } else {
            // Schedule retry with exponential backoff
            Instant nextRetry = calculateNextRetry(newRetryCount);
            
            log.warn("Will retry event: eventId={}, aggregateId={}, attempt={}, nextRetry={}", 
                    outboxEvent.getEventId(), 
                    outboxEvent.getAggregateId(), 
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
     * 
     * Example with initialDelay=10s:
     * - Retry 1: 10s
     * - Retry 2: 20s
     * - Retry 3: 40s
     * - Retry 4: 80s (1m 20s)
     * - Retry 5: 160s (2m 40s)
     * - ...
     * - Capped at maxDelay (default 1 hour)
     */
    private Instant calculateNextRetry(int retryCount) {
        long initialDelaySeconds = properties.getInitialRetryDelay().getSeconds();
        long maxDelaySeconds = properties.getMaxRetryDelay().getSeconds();
        
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

    /**
     * TODO: For multi-instance deployment, add this query to OutboxEventRepository:
     * 
     * @Query(value = """
     *     SELECT * FROM outbox_events 
     *     WHERE status = 'PENDING' 
     *     OR (status = 'PENDING' AND next_retry_at <= NOW())
     *     ORDER BY created_at ASC 
     *     LIMIT :limit
     *     FOR UPDATE SKIP LOCKED
     *     """, nativeQuery = true)
     * List<OutboxEvent> findPendingEventsWithLock(int limit);
     * 
     * The FOR UPDATE SKIP LOCKED ensures:
     * - Each instance locks different events
     * - No duplicate processing
     * - No blocking between instances
     * 
     * Alternative approaches:
     * 1. Distributed lock (Redis SETNX, DB advisory locks)
     * 2. Partition by aggregate ID (each instance handles certain partitions)
     * 3. Leader election (only leader publishes)
     */
}
