package com.eda.lab.validation.domain.repository;

import com.eda.lab.validation.domain.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for OutboxEvent - implements Transactional Outbox pattern.
 * 
 * Key Methods:
 * - findPendingEvents(): Get events ready to publish
 * - save(): Store new events (in same transaction as business logic)
 * 
 * Usage in OutboxPublisher:
 * <pre>
 * {@code
 * @Scheduled(fixedDelay = 2000)
 * public void publishPendingEvents() {
 *     List<OutboxEvent> pending = outboxEventRepository.findPendingEvents(PageRequest.of(0, 50));
 *     
 *     for (OutboxEvent event : pending) {
 *         try {
 *             rabbitTemplate.send(...);
 *             event.markAsSent();
 *             outboxEventRepository.save(event);
 *         } catch (Exception e) {
 *             // Handle retry
 *         }
 *     }
 * }
 * }
 * </pre>
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Find pending events ready to publish with row-level locking.
     * Uses FOR UPDATE SKIP LOCKED for multi-instance safety.
     * 
     * How it works:
     * - Each instance locks different rows (SELECT FOR UPDATE)
     * - If row is locked by another instance, SKIP it (SKIP LOCKED)
     * - Prevents duplicate publishing in scaled deployments
     * 
     * Example with 3 instances:
     * - Instance 1 locks rows 1-50
     * - Instance 2 tries same rows → SKIPPED (busy)
     * - Instance 2 locks rows 51-100 instead
     * - Instance 3 locks rows 101-150
     * - Result: Each instance processes different events ✅
     * 
     * @param now Current timestamp (to check if retry time has arrived)
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


    /**
     * Count events by status (for monitoring).
     * 
     * @param status The status to count
     * @return Number of events with this status
     */
    long countByStatus(OutboxEvent.OutboxStatus status);

    /**
     * Count events by type (for monitoring).
     * 
     * @param eventType The event type
     * @return Number of events of this type
     */
    long countByEventType(String eventType);

    /**
     * Find events for a specific aggregate (for debugging).
     * 
     * @param aggregateId The document ID
     * @return List of events for this document
     */
    List<OutboxEvent> findByAggregateIdOrderByCreatedAtDesc(UUID aggregateId);

    /**
     * Delete old sent events (for archival/cleanup).
     * Use in scheduled job to prevent table bloat.
     * 
     * @param cutoff Delete events sent before this time
     * @return Number of deleted records
     */
    long deleteByStatusAndSentAtBefore(OutboxEvent.OutboxStatus status, Instant cutoff);
}
