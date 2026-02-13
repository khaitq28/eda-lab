package com.eda.lab.ingestion.domain.repository;

import com.eda.lab.ingestion.domain.entity.OutboxEvent;
import com.eda.lab.ingestion.domain.entity.OutboxEvent.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for OutboxEvent entity.
 * Used by the outbox publisher to fetch and update events.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Find pending events for publishing.
     * Ordered by creation time for FIFO processing.
     * 
     * This will be used by the outbox publisher background job.
     */
    @Query("""
        SELECT e FROM OutboxEvent e 
        WHERE e.status = 'PENDING' 
        ORDER BY e.createdAt ASC
        """)
    List<OutboxEvent> findPendingEvents();

    /**
     * Find failed events ready for retry.
     * Used for implementing retry with exponential backoff.
     */
    @Query("""
        SELECT e FROM OutboxEvent e 
        WHERE e.status = 'FAILED' 
        AND e.nextRetryAt IS NOT NULL 
        AND e.nextRetryAt <= :now
        ORDER BY e.nextRetryAt ASC
        """)
    List<OutboxEvent> findEventsReadyForRetry(Instant now);

    /**
     * Find events by aggregate ID.
     * Useful for debugging and event sourcing scenarios.
     */
    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(UUID aggregateId);

    /**
     * Find events by status.
     * Useful for monitoring.
     */
    List<OutboxEvent> findByStatus(OutboxStatus status);

    /**
     * Count events by status.
     * Useful for metrics dashboards.
     */
    long countByStatus(OutboxStatus status);

    /**
     * Find old sent events for archival/cleanup.
     * Sent events can be archived after a retention period.
     */
    @Query("""
        SELECT e FROM OutboxEvent e 
        WHERE e.status = 'SENT' 
        AND e.sentAt < :before
        """)
    List<OutboxEvent> findOldSentEvents(Instant before);
}
