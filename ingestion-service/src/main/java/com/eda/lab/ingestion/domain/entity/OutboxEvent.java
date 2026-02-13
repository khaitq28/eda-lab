package com.eda.lab.ingestion.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox Event entity implementing the Transactional Outbox pattern.
 * 
 * Events are stored in the same transaction as business data (Document),
 * ensuring atomicity. A separate background process will read PENDING events
 * and publish them to RabbitMQ.
 * 
 * This guarantees at-least-once delivery:
 * - If DB commit fails, no event is stored (consistency)
 * - If DB commit succeeds, event will eventually be published (reliability)
 */
@Entity
@Table(name = "outbox_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Unique event identifier for consumer idempotency.
     * Consumers use this to detect and ignore duplicate events.
     */
    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    /**
     * Event type discriminator (e.g., "DocumentUploaded").
     * Used for routing and consumer filtering.
     */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /**
     * Type of aggregate this event relates to (e.g., "Document").
     */
    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    /**
     * ID of the aggregate (e.g., Document ID).
     * Used for event ordering and correlation.
     */
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    /**
     * Full event payload as JSON.
     * Contains all data needed by consumers.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    /**
     * Outbox processing status.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * When the event was successfully published.
     * Null if not yet published.
     */
    @Column(name = "sent_at")
    private Instant sentAt;

    /**
     * Number of publish attempts.
     * Used for monitoring and retry logic.
     */
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    /**
     * Last error message if publish failed.
     * Useful for debugging and monitoring.
     */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /**
     * When to retry publishing (for failed events).
     * Implements exponential backoff.
     */
    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (eventId == null) {
            eventId = UUID.randomUUID();
        }
    }

    /**
     * Mark event as successfully sent.
     */
    public void markAsSent() {
        this.status = OutboxStatus.SENT;
        this.sentAt = Instant.now();
    }

    /**
     * Mark event as failed and schedule retry.
     */
    public void markAsFailed(String error, Instant nextRetry) {
        this.status = OutboxStatus.FAILED;
        this.lastError = error;
        this.retryCount++;
        this.nextRetryAt = nextRetry;
    }

    /**
     * Outbox event status.
     */
    public enum OutboxStatus {
        PENDING,  // Not yet published
        SENT,     // Successfully published to message broker
        FAILED    // Max retries exceeded or permanent failure
    }
}
