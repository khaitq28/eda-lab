package com.eda.lab.enrichment.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity for Transactional Outbox pattern.
 * 
 * Purpose:
 * - Ensure reliable event publishing (at-least-once delivery)
 * - Store events in same transaction as business data
 * - Background publisher reads and publishes to RabbitMQ
 * 
 * Lifecycle:
 * 1. PENDING: Created in same transaction as business logic
 * 2. SENT: Successfully published to RabbitMQ
 * 3. FAILED: Max retries exceeded (requires manual intervention)
 * 
 * Retry Strategy:
 * - Exponential backoff: 10s, 20s, 40s, 80s, 160s, ...
 * - Max retries: 10 (configurable)
 * - After max retries: status = FAILED
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "payload_json", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OutboxStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    public enum OutboxStatus {
        PENDING,  // Not yet published
        SENT,     // Successfully published
        FAILED    // Max retries exceeded
    }

    /**
     * Mark event as successfully sent.
     */
    public void markAsSent() {
        this.status = OutboxStatus.SENT;
        this.sentAt = Instant.now();
        this.lastError = null;
        this.nextRetryAt = null;
    }

    /**
     * Mark event as failed and schedule retry with exponential backoff.
     * 
     * @param error Error message
     * @param baseDelaySeconds Base delay for exponential backoff (e.g., 10 seconds)
     */
    public void markAsFailed(String error, long baseDelaySeconds) {
        this.retryCount++;
        this.lastError = error;
        
        // Exponential backoff: baseDelay * 2^retryCount
        // Example with baseDelay=10s: 10s, 20s, 40s, 80s, 160s, 320s, ...
        long delaySeconds = baseDelaySeconds * (long) Math.pow(2, retryCount - 1);
        long maxDelaySeconds = 3600; // Cap at 1 hour
        delaySeconds = Math.min(delaySeconds, maxDelaySeconds);
        
        this.nextRetryAt = Instant.now().plusSeconds(delaySeconds);
    }

    /**
     * Mark event as permanently failed (max retries exceeded).
     * 
     * @param error Final error message
     */
    public void markAsPermanentlyFailed(String error) {
        this.status = OutboxStatus.FAILED;
        this.lastError = error;
        this.nextRetryAt = null;
    }
}
