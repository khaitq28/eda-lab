package com.eda.lab.validation.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * ProcessedEvent entity for idempotent message consumption.
 * 
 * This entity tracks which events have been successfully processed to prevent
 * duplicate processing in at-least-once delivery scenarios.
 * 
 * Key Design:
 * - event_id as PRIMARY KEY ensures uniqueness
 * - Minimal schema (only what's needed for idempotency)
 * - Optional fields (event_type, aggregate_id) for debugging/monitoring
 * 
 * Usage Pattern:
 * 1. Consumer receives message with eventId
 * 2. Check if eventId exists in processed_events
 * 3. If exists => Skip processing (idempotent)
 * 4. If not exists => Insert eventId, then process
 * 
 * Best Practices:
 * - Insert BEFORE processing (within same transaction)
 * - Use @Transactional to ensure atomicity
 * - Consider archival strategy for old records
 */
@Entity
@Table(name = "processed_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEvent {

    /**
     * Event ID from message properties (messageId).
     * This is the PRIMARY KEY to ensure no duplicates.
     */
    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    /**
     * When this event was first successfully processed.
     * Used for monitoring and archival.
     */
    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /**
     * Optional: Event type for debugging/monitoring.
     * Example: "DocumentUploaded", "DocumentValidated"
     */
    @Column(name = "event_type", length = 100)
    private String eventType;

    /**
     * Optional: Aggregate ID for correlation.
     * Example: documentId
     */
    @Column(name = "aggregate_id")
    private UUID aggregateId;

    /**
     * Set processedAt to current time before persisting.
     */
    @PrePersist
    protected void onCreate() {
        if (processedAt == null) {
            processedAt = Instant.now();
        }
    }

    /**
     * Factory method to create a ProcessedEvent with minimal data.
     * Use this for simple idempotency checks.
     */
    public static ProcessedEvent of(UUID eventId) {
        return ProcessedEvent.builder()
                .eventId(eventId)
                .build();
    }

    /**
     * Factory method to create a ProcessedEvent with full metadata.
     * Use this for better observability and debugging.
     */
    public static ProcessedEvent of(UUID eventId, String eventType, UUID aggregateId) {
        return ProcessedEvent.builder()
                .eventId(eventId)
                .eventType(eventType)
                .aggregateId(aggregateId)
                .build();
    }
}
