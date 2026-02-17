package com.eda.lab.enrichment.domain.repository;

import com.eda.lab.enrichment.domain.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository for ProcessedEvent - implements Idempotent Consumer pattern.
 * 
 * Key Methods:
 * - existsById(eventId): Check if event was already processed
 * - save(processedEvent): Mark event as processed
 * 
 * Usage in Consumer:
 * <pre>
 * {@code
 * @RabbitListener(queues = "document.validated.q")
 * public void handleDocumentValidated(Message message) {
 *     UUID eventId = UUID.fromString(message.getMessageProperties().getMessageId());
 *     
 *     if (processedEventRepository.existsById(eventId)) {
 *         log.info("Event {} already processed, skipping", eventId);
 *         return; // Idempotent - ACK and skip
 *     }
 *     
 *     // Process the event...
 *     
 *     processedEventRepository.save(new ProcessedEvent(eventId, "DocumentValidated", documentId));
 * }
 * }
 * </pre>
 */
@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
    // existsById(UUID) is inherited from JpaRepository
    // save(ProcessedEvent) is inherited from JpaRepository
}
