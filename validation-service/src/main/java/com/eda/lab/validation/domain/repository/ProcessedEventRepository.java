package com.eda.lab.validation.domain.repository;

import com.eda.lab.validation.domain.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Repository for ProcessedEvent - used for idempotent message consumption.
 * 
 * Key Methods:
 * - existsById(eventId): Check if event was already processed
 * - save(processedEvent): Mark event as processed
 * 
 * Usage Pattern in Consumer:
 * <pre>
 * {@code
 * @Transactional
 * public void processEvent(UUID eventId, String eventType, UUID aggregateId) {
 *     // 1. Check idempotency
 *     if (processedEventRepository.existsById(eventId)) {
 *         log.info("Event already processed (idempotent skip): eventId={}", eventId);
 *         return;
 *     }
 *     
 *     // 2. Mark as processed FIRST (within transaction)
 *     processedEventRepository.save(ProcessedEvent.of(eventId, eventType, aggregateId));
 *     
 *     // 3. Process the event
 *     // ... business logic ...
 * }
 * }
 * </pre>
 * 
 * Why insert BEFORE processing?
 * - If processing fails, transaction rolls back (including the insert)
 * - If processing succeeds, both insert and business logic commit together
 * - This ensures atomicity: either both succeed or both fail
 */
@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /**
     * Check if an event has already been processed.
     * This is the primary idempotency check.
     * 
     * @param eventId The event ID to check
     * @return true if event was already processed, false otherwise
     */
    boolean existsById(UUID eventId);

    /**
     * Count how many events were processed in a time range.
     * Useful for monitoring and metrics.
     * 
     * @param start Start time (inclusive)
     * @param end End time (inclusive)
     * @return Number of events processed in the time range
     */
    @Query("SELECT COUNT(e) FROM ProcessedEvent e WHERE e.processedAt BETWEEN :start AND :end")
    long countProcessedBetween(Instant start, Instant end);

    /**
     * Count events by type (for monitoring).
     * 
     * @param eventType The event type to count
     * @return Number of events of this type
     */
    long countByEventType(String eventType);

    /**
     * Delete old processed events (for archival/cleanup).
     * Use this in a scheduled job to prevent table bloat.
     * 
     * Example usage:
     * <pre>
     * {@code
     * @Scheduled(cron = "0 0 2 * * *") // Daily at 2 AM
     * public void archiveOldEvents() {
     *     Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
     *     long deleted = processedEventRepository.deleteByProcessedAtBefore(cutoff);
     *     log.info("Archived {} old processed events", deleted);
     * }
     * }
     * </pre>
     * 
     * @param cutoff Delete events processed before this time
     * @return Number of deleted records
     */
    long deleteByProcessedAtBefore(Instant cutoff);
}
