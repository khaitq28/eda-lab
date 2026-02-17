package com.eda.lab.audit.domain.repository;

import com.eda.lab.audit.domain.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for AuditLog - audit trail of all events.
 * 
 * Key Methods:
 * - findByAggregateId(): Get all events for a document
 * - findByEventId(): Get specific event by eventId
 * - existsByEventId(): Check if event already audited (idempotency)
 * 
 * Usage in REST API:
 * - GET /audit?documentId={aggregateId}
 * - GET /audit/events/{eventId}
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /**
     * Find all audit logs for a specific document, ordered by time (chronological).
     * 
     * @param aggregateId Document ID
     * @return List of audit logs for this document (oldest first)
     */
    List<AuditLog> findByAggregateIdOrderByReceivedAtAsc(UUID aggregateId);

    /**
     * Find all audit logs for a specific document, ordered by time (reverse chronological).
     * 
     * @param aggregateId Document ID
     * @return List of audit logs for this document (newest first)
     */
    List<AuditLog> findByAggregateIdOrderByReceivedAtDesc(UUID aggregateId);

    /**
     * Find audit log by event ID.
     * 
     * @param eventId Event ID
     * @return Optional audit log
     */
    Optional<AuditLog> findByEventId(UUID eventId);

    /**
     * Check if event already audited (for idempotency).
     * 
     * @param eventId Event ID
     * @return true if event already exists
     */
    boolean existsByEventId(UUID eventId);

    /**
     * Find audit logs by event type.
     * 
     * @param eventType Event type (DocumentUploaded, DocumentValidated, etc.)
     * @return List of audit logs of this type
     */
    List<AuditLog> findByEventTypeOrderByReceivedAtDesc(String eventType);

    /**
     * Find audit logs by routing key.
     * 
     * @param routingKey RabbitMQ routing key
     * @return List of audit logs with this routing key
     */
    List<AuditLog> findByRoutingKeyOrderByReceivedAtDesc(String routingKey);

    /**
     * Find recent audit logs.
     * 
     * @param after Only events after this timestamp
     * @return List of recent audit logs
     */
    List<AuditLog> findByReceivedAtAfterOrderByReceivedAtDesc(Instant after);

    /**
     * Count events by type (for monitoring).
     * 
     * @param eventType Event type
     * @return Count of events
     */
    long countByEventType(String eventType);

    /**
     * Count events for a document (for monitoring).
     * 
     * @param aggregateId Document ID
     * @return Count of events for this document
     */
    long countByAggregateId(UUID aggregateId);

    /**
     * Get timeline of events for a document.
     * Returns event types in chronological order.
     * 
     * @param aggregateId Document ID
     * @return List of event types in order
     */
    @Query("SELECT a.eventType FROM AuditLog a WHERE a.aggregateId = :aggregateId ORDER BY a.receivedAt ASC")
    List<String> findEventTimelineByAggregateId(UUID aggregateId);
}
