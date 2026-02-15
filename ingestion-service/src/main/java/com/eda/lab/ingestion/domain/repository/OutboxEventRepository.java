package com.eda.lab.ingestion.domain.repository;

import com.eda.lab.ingestion.domain.entity.OutboxEvent;
import com.eda.lab.ingestion.domain.entity.OutboxEvent.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
     * Find pending events ready to publish with row-level locking.
     * Uses FOR UPDATE SKIP LOCKED for multi-instance safety.
     * 
     * How it works:
     * - Each instance locks different rows
     * - If row is locked by another instance, SKIP it
     * - Prevents duplicate publishing in scaled deployments
     * 
     * @param now Current timestamp
     * @param limit Batch size
     * @return List of pending events (locked by this instance)
     */
    @Query(value = """
        SELECT * FROM outbox_events 
        WHERE status = 'PENDING' 
        AND (next_retry_at IS NULL OR next_retry_at <= :now)
        ORDER BY created_at ASC 
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEvent> findPendingEvents(@Param("now") Instant now, @Param("limit") int limit);

    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(UUID aggregateId);

    List<OutboxEvent> findByStatus(OutboxStatus status);

    long countByStatus(OutboxStatus status);

    @Query("""
        SELECT e FROM OutboxEvent e 
        WHERE e.status = 'SENT' 
        AND e.sentAt < :before
        """)
    List<OutboxEvent> findOldSentEvents(Instant before);
}
