package com.eda.lab.enrichment.messaging.consumer;

import com.eda.lab.common.event.DocumentEnrichedEvent;
import com.eda.lab.common.event.DocumentValidatedEvent;
import com.eda.lab.common.observability.MessageMdcContext;
import com.eda.lab.enrichment.config.RabbitMQConfig;
import com.eda.lab.enrichment.domain.entity.OutboxEvent;
import com.eda.lab.enrichment.domain.entity.ProcessedEvent;
import com.eda.lab.enrichment.domain.repository.OutboxEventRepository;
import com.eda.lab.enrichment.domain.repository.ProcessedEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumer for DocumentValidated events.
 * 
 * Responsibilities:
 * 1. Consume DocumentValidated events from document.validated.q
 * 2. Check idempotency (skip if already processed)
 * 3. Perform enrichment (simulated: classification, metadata extraction)
 * 4. Create DocumentEnriched event in outbox (same transaction)
 * 5. Handle technical failures with retry/DLQ
 * 
 * Patterns Implemented:
 * - Idempotent Consumer (processed_events table)
 * - Transactional Outbox (outbox_events table)
 * - Retry with exponential backoff (5 attempts: 1s, 2s, 4s, 8s, 10s)
 * - Dead Letter Queue (after max retries)
 * 
 * Message Flow:
 * 1. Receive message from document.validated.q
 * 2. Extract eventId from messageId (required)
 * 3. Check if already processed (idempotency)
 * 4. Parse message body to DocumentValidatedEvent
 * 5. Perform enrichment
 * 6. Save ProcessedEvent + OutboxEvent in same transaction
 * 7. ACK message (success) or throw exception (retry/DLQ)
 * 
 * Error Handling:
 * - Technical failures (JSON parse, DB down) → throw exception → retry → DLQ
 * - Business failures (none expected in enrichment) → log and ACK
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentValidatedConsumer {

    private final ProcessedEventRepository processedEventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Handle DocumentValidated events.
     * 
     * @param message RabbitMQ message with DocumentValidated event
     */
    @RabbitListener(queues = RabbitMQConfig.MAIN_QUEUE)
    @Transactional
    public void handleDocumentValidated(Message message) {
        try (MessageMdcContext mdcContext = new MessageMdcContext(message, objectMapper)) {
            processMessage(message);
        }
    }
    
    private void processMessage(Message message) {
        try {
            // Extract eventId from message properties (required for idempotency)
            String messageId = message.getMessageProperties().getMessageId();
            if (messageId == null || messageId.isBlank()) {
                log.error("EVENT_INVALID: Message received without messageId");
                throw new IllegalArgumentException("messageId is required for idempotency");
            }

            UUID eventId = UUID.fromString(messageId);
            log.info("EVENT_RECEIVED");

            // Idempotency check: Skip if already processed
            if (processedEventRepository.existsById(eventId)) {
                log.info("EVENT_SKIPPED_IDEMPOTENT");
                return; // ACK and skip
            }

            // Parse message body
            String messageBody = new String(message.getBody());
            DocumentValidatedEvent event = objectMapper.readValue(messageBody, DocumentValidatedEvent.class);
            UUID documentId = event.aggregateId();

            log.debug("EVENT_PROCESSING");

            // Perform enrichment (simulated)
            enrichDocument(documentId);

            // Save processed event (idempotency)
            ProcessedEvent processedEvent = new ProcessedEvent(
                    eventId,
                    "DocumentValidated",
                    documentId
            );
            processedEventRepository.save(processedEvent);

            // Extract correlation ID from incoming message
            String correlationId = extractCorrelationId(messageBody);
            
            // Create DocumentEnriched event in outbox (same transaction)
            DocumentEnrichedEvent enrichedEvent = new DocumentEnrichedEvent(
                    UUID.randomUUID(),
                    documentId,
                    Instant.now(),
                    "CLASSIFICATION_AND_METADATA", // Enrichment type
                    Instant.now()
            );

            OutboxEvent outboxEvent = createOutboxEvent(enrichedEvent, correlationId);
            outboxEventRepository.save(outboxEvent);

            log.info("EVENT_PROCESSED: outboxEventId={}", outboxEvent.getEventId());

        } catch (IllegalArgumentException e) {
            log.error("EVENT_PARSE_FAILED: error={}", e.getMessage(), e);
            throw new RuntimeException("Invalid message format", e); // Trigger retry/DLQ
            
        } catch (Exception e) {
            log.error("TECHNICAL_FAILURE: (will retry if attempts remain)", e);
            throw new RuntimeException("Failed to process event", e); // Trigger retry/DLQ
        }
    }
    
    /**
     * Extract correlation ID from event payload.
     */
    private String extractCorrelationId(String payloadJson) {
        try {
            var jsonNode = objectMapper.readTree(payloadJson);
            if (jsonNode.has("correlationId")) {
                return jsonNode.get("correlationId").asText();
            }
        } catch (Exception e) {
            log.debug("Could not extract correlationId from payload");
        }
        return null;
    }

    /**
     * Simulate document enrichment.
     * 
     * In a real system, this would:
     * - Classify document type (invoice, contract, report, etc.)
     * - Extract metadata (author, date, keywords, etc.)
     * - Perform OCR if needed
     * - Extract entities (names, addresses, amounts, etc.)
     * - Calculate confidence scores
     * 
     * For this learning project, we just simulate success.
     * 
     * @param documentId Document to enrich
     */
    private void enrichDocument(UUID documentId) {
        log.debug("Enriching document: documentId={}", documentId);
        // Simulated enrichment processing
        // In real system: call ML models, external APIs, etc.
        log.debug("Document enriched successfully: documentId={}", documentId);
    }

    /**
     * Create an outbox event for DocumentEnriched with correlation ID.
     * 
     * @param event DocumentEnriched event
     * @param correlationId Correlation ID to propagate
     * @return OutboxEvent ready to be saved
     */
    private OutboxEvent createOutboxEvent(DocumentEnrichedEvent event, String correlationId) {
        try {
            // Create enriched payload with correlation ID
            var eventWithCorrelation = new DocumentEnrichedEventWithCorrelation(
                    event.eventId(),
                    event.aggregateId(),
                    event.timestamp(),
                    event.enrichmentType(),
                    event.enrichedAt(),
                    correlationId
            );
            
            String payloadJson = objectMapper.writeValueAsString(eventWithCorrelation);

            OutboxEvent outboxEvent = new OutboxEvent();
            outboxEvent.setId(UUID.randomUUID());
            outboxEvent.setEventId(event.eventId());
            outboxEvent.setEventType(event.eventType());
            outboxEvent.setAggregateType("Document");
            outboxEvent.setAggregateId(event.aggregateId());
            outboxEvent.setPayloadJson(payloadJson);
            outboxEvent.setStatus(OutboxEvent.OutboxStatus.PENDING);
            outboxEvent.setCreatedAt(Instant.now());
            outboxEvent.setRetryCount(0);

            return outboxEvent;
            
        } catch (Exception e) {
            log.error("EVENT_SERIALIZATION_FAILED", e);
            throw new RuntimeException("Failed to create outbox event", e);
        }
    }
    
    /**
     * Internal record to include correlation ID in event payload.
     */
    private record DocumentEnrichedEventWithCorrelation(
            UUID eventId,
            UUID aggregateId,
            Instant timestamp,
            String enrichmentType,
            Instant enrichedAt,
            String correlationId
    ) {}
}
