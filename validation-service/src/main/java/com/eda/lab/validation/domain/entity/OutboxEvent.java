package com.eda.lab.validation.domain.entity;

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
 * Outbox Event entity for Transactional Outbox pattern.
 * 
 * This entity stores events that need to be published to RabbitMQ.
 * Events are created in the same transaction as business logic (validation),
 * ensuring atomicity and reliability.
 * 
 * Flow:
 * 1. Consumer validates document
 * 2. Save to processed_events (idempotency)
 * 3. Save to outbox_events (event to publish) - SAME TRANSACTION
 * 4. Background OutboxPublisher reads PENDING events
 * 5. Publishes to RabbitMQ
 * 6. Marks as SENT
 * 
 * This guarantees at-least-once delivery even if RabbitMQ is down.
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
     * Must match the eventId in the payload_json.
     */
    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    /**
     * Event type discriminator (e.g., "DocumentValidated", "DocumentRejected").
     * Used for routing and monitoring.
     */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /**
     * Type of aggregate (e.g., "Document").
     */
    @Column(name = "aggregate_type", nullable = false, length = 100)
    @Builder.Default
    private String aggregateType = "Document";

    /**
     * ID of the document this event relates to.
     */
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    /**
     * Full event payload as JSON.
     * Contains all data needed by consumers.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private String payloadJson;

    /**
     * Publishing status.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OutboxStatus status = OutboxStatus.PENDING;

    /**
     * When this event was created.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * When this event was successfully published.
     */
    @Column(name = "sent_at")
    private Instant sentAt;

    /**
     * Number of publish attempts.
     */
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    /**
     * Last error message if publish failed.
     */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /**
     * When to retry publishing (for failed events).
     */
    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
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
        SENT,     // Successfully published to RabbitMQ
        FAILED    // Max retries exceeded or permanent failure
    }
}
