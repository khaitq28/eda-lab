package com.eda.lab.enrichment.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity for tracking processed events (Idempotent Consumer pattern).
 * 
 * Purpose:
 * - Prevent duplicate processing of the same event
 * - Handle at-least-once delivery from RabbitMQ
 * 
 * Usage:
 * 1. Before processing a message, check if event_id exists
 * 2. If exists → skip processing (idempotent)
 * 3. If not exists → process and insert event_id
 * 
 * The event_id comes from the RabbitMQ message's messageId property.
 */
@Entity
@Table(name = "processed_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "aggregate_id")
    private UUID aggregateId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public ProcessedEvent(UUID eventId, String eventType, UUID aggregateId) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.processedAt = Instant.now();
    }
}
