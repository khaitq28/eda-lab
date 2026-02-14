package com.eda.lab.validation.messaging.consumer;

import com.eda.lab.common.event.DocumentUploadedEvent;
import com.eda.lab.validation.config.RabbitMQConfig;
import com.eda.lab.validation.domain.entity.ProcessedEvent;
import com.eda.lab.validation.domain.repository.ProcessedEventRepository;
import com.eda.lab.validation.messaging.exception.BusinessValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Consumer for DocumentUploaded events.
 * 
 * This component demonstrates EDA best practices:
 * 1. **Idempotency**: Tracks processed events to handle duplicate deliveries
 * 2. **Retry with Exponential Backoff**: Configured in RabbitMQConfig (5 attempts)
 * 3. **Dead Letter Queue**: Failed messages go to DLQ after retries exhausted
 * 4. **Transactional Processing**: Idempotency check + business logic in same transaction
 * 5. **Observability**: Comprehensive logging for monitoring and debugging
 * 
 * Message Flow:
 * 1. Receive message from "document.uploaded.q"
 * 2. Extract eventId from message properties (messageId)
 * 3. Check if already processed (idempotency)
 * 4. If new, mark as processed and validate document
 * 5. On technical failure, retry (up to 5 attempts)
 * 6. After retries exhausted, message goes to DLQ
 * 
 * Simulated Validation Logic:
 * - If documentId ends with even digit => VALIDATED
 * - If documentId ends with odd digit => Technical failure (triggers retry/DLQ)
 * 
 * Production Improvements:
 * - Emit DocumentValidated/DocumentRejected events (next step)
 * - Add metrics (processed count, validation success rate)
 * - Add distributed tracing (correlation IDs)
 * - Implement actual validation business logic
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentUploadedConsumer {

    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Consume DocumentUploaded events from RabbitMQ.
     * 
     * FIXED Implementation:
     * - Idempotency check FIRST (read-only, outside main transaction)
     * - Process business logic
     * - Mark as processed AFTER success (or after business validation failure)
     * - DON'T mark as processed on technical failure (allow retry)
     * 
     * Configuration:
     * - Queue: document.uploaded.q
     * - Retry: 5 attempts with exponential backoff (configured in RabbitMQConfig)
     * - DLQ: document.uploaded.dlq (after retries exhausted)
     * - Acknowledgment: AUTO (Spring handles based on method success/exception)
     * 
     * Error Handling:
     * - Business validation failure => Mark as processed, DON'T retry
     * - Technical failure => DON'T mark as processed, retry with backoff
     * - After 5 retries => Message goes to DLQ
     */
    @RabbitListener(queues = RabbitMQConfig.DOCUMENT_UPLOADED_QUEUE)
    @Transactional
    public void handleDocumentUploaded(Message message) {
        UUID eventId = null;
        UUID aggregateId = null;
        String eventType = null;
        
        try {
            // ============================================================
            // 1. Extract Event Metadata
            // ============================================================
            
            String messageIdStr = message.getMessageProperties().getMessageId();
            if (messageIdStr == null || messageIdStr.isBlank()) {
                log.error("Message received without messageId. Cannot ensure idempotency. Rejecting message.");
                throw new IllegalArgumentException("messageId is required for idempotency");
            }
            
            eventId = UUID.fromString(messageIdStr);
            eventType = message.getMessageProperties().getHeader("eventType");
            String aggregateIdStr = message.getMessageProperties().getHeader("aggregateId");
            aggregateId = aggregateIdStr != null ? UUID.fromString(aggregateIdStr) : null;
            
            log.info("Received message: eventId={}, eventType={}, aggregateId={}", 
                    eventId, eventType, aggregateId);

            // ============================================================
            // 2. Idempotency Check (Read-Only)
            // ============================================================
            
            if (processedEventRepository.existsById(eventId)) {
                log.info("Event already processed (idempotent skip): eventId={}, aggregateId={}", 
                        eventId, aggregateId);
                return;  // ACK and skip
            }

            // ============================================================
            // 3. Parse Event Payload
            // ============================================================
            
            String payloadJson = new String(message.getBody());
            DocumentUploadedEvent event = objectMapper.readValue(payloadJson, DocumentUploadedEvent.class);
            
            UUID documentId = event.aggregateId();
            String documentName = event.documentName();
            String contentType = event.contentType();
            
            log.info("Processing DocumentUploaded event: documentId={}, documentName={}, contentType={}", 
                    documentId, documentName, contentType);

            // ============================================================
            // 4. Business Logic: Validate Document
            // ============================================================
            
            validateDocument(documentId, documentName, contentType);

            // ============================================================
            // 5. Mark as Processed AFTER Successful Validation
            // ============================================================
            
            ProcessedEvent processedEvent = ProcessedEvent.of(eventId, eventType, aggregateId);
            processedEventRepository.save(processedEvent);
            
            log.info("Document VALIDATED and marked as processed: eventId={}, documentId={}", 
                    eventId, documentId);
            
            // TODO: Emit DocumentValidated event (next step)
            
            // Transaction commits here (both processed_event insert and validation success)

        } catch (BusinessValidationException e) {
            // ============================================================
            // Business Validation Failure - DON'T RETRY
            // ============================================================
            
            log.warn("Document REJECTED due to business validation: eventId={}, documentId={}, reason={}", 
                    eventId, aggregateId, e.getMessage());
            
            // Mark as processed (don't process this event again)
            ProcessedEvent processedEvent = ProcessedEvent.of(eventId, eventType, aggregateId);
            processedEventRepository.save(processedEvent);
            
            // TODO: Emit DocumentRejected event with reason (next step)
            
            // DON'T throw exception - we handled it (business logic completed)
            // Message will be ACKed
            
        } catch (IllegalArgumentException e) {
            // Invalid message format - don't retry
            log.error("Invalid message format: eventId={}, error={}", eventId, e.getMessage(), e);
            throw e;  // Will go to DLQ after retries
            
        } catch (Exception e) {
            // ============================================================
            // Technical Error - RETRY
            // ============================================================
            
            log.error("Technical error processing event: eventId={}, documentId={} (will retry if attempts remain)", 
                    eventId, aggregateId, e);
            
            // DON'T mark as processed - allow retry
            // Throw exception to trigger retry mechanism
            throw new RuntimeException("Failed to process DocumentUploaded event", e);
        }
    }

    /**
     * Validate document against business rules.
     * 
     * Business Rules:
     * 1. File name must be <= 30 characters
     * 2. Content type must be "application/pdf"
     * 
     * Error Handling:
     * - Business rule violation => Throws BusinessValidationException (DON'T RETRY)
     * - Technical failure => Throws RuntimeException (RETRY)
     * 
     * @param documentId The document ID
     * @param documentName The document name
     * @param contentType The content type
     * @throws BusinessValidationException if document violates business rules
     * @throws RuntimeException if technical error occurs (e.g., external service call fails)
     */
    private void validateDocument(UUID documentId, String documentName, String contentType) {
        log.debug("Validating document: documentId={}, documentName={}, contentType={}", 
                documentId, documentName, contentType);
        
        if (documentName == null || documentName.isBlank()) {
            throw new BusinessValidationException("Document name is required");
        }
        
        if (documentName.length() > 30) {
            throw new BusinessValidationException(
                "Document name too long: %d characters (max 30)", 
                documentName.length()
            );
        }
        if (contentType == null || !contentType.equalsIgnoreCase("application/pdf")) {
            throw new BusinessValidationException(
                "Invalid file format: %s (expected: application/pdf)", 
                contentType
            );
        }
        if (!documentName.toLowerCase().endsWith(".pdf")) {
            throw new BusinessValidationException(
                "File extension does not match content type: %s", 
                documentName
            );
        }
        
        log.info("Document VALIDATED: documentId={}, documentName={}", documentId, documentName);
    }
    
}
