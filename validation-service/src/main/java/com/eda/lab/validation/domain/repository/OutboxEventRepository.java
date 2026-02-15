package com.eda.lab.validation.domain.repository;

import com.eda.lab.validation.domain.entity.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
     * Find pending events ready to publish.
     * Orders by creation time for FIFO processing.
     * 
     * @param pageable Pagination (typically PageRequest.of(0, batchSize))
     * @return List of pending events
     */
    @Query("""
        SELECT e FROM OutboxEvent e 
        WHERE e.status = 'PENDING' 
        ORDER BY e.createdAt ASC
        """)
    List<OutboxEvent> findPendingEvents(Pageable pageable);

    /**
     * Find failed events ready for retry.
     * Only returns events where next_retry_at has passed.
     * 
     * @param now Current timestamp
     * @param pageable Pagination
     * @return List of events ready for retry
     */
    @Query("""
        SELECT e FROM OutboxEvent e 
        WHERE e.status = 'FAILED' 
        AND e.nextRetryAt IS NOT NULL 
        AND e.nextRetryAt <= :now
        ORDER BY e.nextRetryAt ASC
        """)
    List<OutboxEvent> findEventsReadyForRetry(Instant now, Pageable pageable);

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
