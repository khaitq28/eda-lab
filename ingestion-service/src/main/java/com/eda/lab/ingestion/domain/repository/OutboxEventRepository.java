package com.eda.lab.ingestion.domain.repository;

import com.eda.lab.ingestion.domain.entity.OutboxEvent;
import com.eda.lab.ingestion.domain.entity.OutboxEvent.OutboxStatus;
import org.springframework.data.domain.Pageable;
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


    @Query("""
        SELECT e FROM OutboxEvent e 
        WHERE e.status = 'PENDING' 
        AND (e.nextRetryAt IS NULL OR e.nextRetryAt <= :now)
        ORDER BY e.createdAt ASC
        """)
    List<OutboxEvent> findPendingEvents(Instant now, Pageable pageable);

    @Query("""
        SELECT e FROM OutboxEvent e 
        WHERE e.status = 'FAILED' 
        AND e.nextRetryAt IS NOT NULL 
        AND e.nextRetryAt <= :now
        ORDER BY e.nextRetryAt ASC
        """)
    List<OutboxEvent> findEventsReadyForRetry(Instant now);

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
