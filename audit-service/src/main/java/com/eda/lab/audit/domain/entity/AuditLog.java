package com.eda.lab.audit.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit log of all document events.
 * 
 * Purpose:
 * - Store complete history of all document-related events
 * - Enable debugging and troubleshooting
 * - Provide observability into event flows
 * - Support compliance and auditing requirements
 * 
 * Idempotency:
 * - Uses UNIQUE constraint on event_id
 * - Duplicate events are safely ignored (idempotent)
 * 
 * Usage:
 * - audit-service consumes ALL document events from RabbitMQ
 * - Each event is stored exactly once in this table
 * - Query by documentId to see full event timeline
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "routing_key", nullable = false)
    private String routingKey;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "message_id")
    private String messageId;

    @Column(name = "correlation_id")
    private String correlationId;

    /**
     * Constructor for creating audit log from event.
     */
    public AuditLog(UUID eventId, String eventType, UUID aggregateId, String aggregateType,
                    String routingKey, String payloadJson, String messageId, String correlationId) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.routingKey = routingKey;
        this.payloadJson = payloadJson;
        this.receivedAt = Instant.now();
        this.messageId = messageId;
        this.correlationId = correlationId;
    }
}
