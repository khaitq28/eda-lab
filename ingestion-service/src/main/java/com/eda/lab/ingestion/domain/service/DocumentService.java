package com.eda.lab.ingestion.domain.service;

import com.eda.lab.common.event.DocumentUploadedEvent;
import com.eda.lab.common.event.EventTypes;
import com.eda.lab.common.observability.MdcKeys;
import com.eda.lab.ingestion.api.dto.DocumentResponse;
import com.eda.lab.ingestion.api.dto.UploadDocumentRequest;
import com.eda.lab.ingestion.domain.entity.Document;
import com.eda.lab.ingestion.domain.entity.OutboxEvent;
import com.eda.lab.ingestion.domain.repository.DocumentRepository;
import com.eda.lab.ingestion.domain.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Service layer for document operations.
 * Implements the Transactional Outbox pattern.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Upload a document and create an outbox event in the same transaction.
     * 
     * This is the core of the Transactional Outbox pattern:
     * 1. Save business data (Document)
     * 2. Save event (OutboxEvent) in the SAME transaction
     * 3. If transaction commits, both are saved atomically
     * 4. A separate process will publish the event to RabbitMQ
     * 
     * Benefits:
     * - Atomicity: Either both succeed or both fail
     * - Reliability: No lost events (event is in DB)
     * - Consistency: Database is the source of truth
     * 
     * @param request Upload request
     * @return Created document response
     */
    @Transactional
    public DocumentResponse uploadDocument(UploadDocumentRequest request) {
        // Get correlation ID from MDC (set by RequestMdcInterceptor)
        String correlationId = MDC.get(MdcKeys.CORRELATION_ID);
        
        log.info("DOCUMENT_UPLOAD_STARTED");

        // 1. Create and save Document entity
        Document document = Document.builder()
                .name(request.getName())
                .contentType(request.getContentType())
                .fileSize(request.getFileSize())
                .status(Document.DocumentStatus.UPLOADED)
                .metadata(request.getMetadata())
                .correlationId(correlationId)
                .createdBy(request.getUploadedBy())
                .updatedBy(request.getUploadedBy())
                .build();

        document = documentRepository.save(document);
        
        // Add document ID to MDC for subsequent logs
        MDC.put(MdcKeys.DOCUMENT_ID, document.getId().toString());
        log.debug("DOCUMENT_SAVED");

        // 2. Create integration event
        DocumentUploadedEvent event = DocumentUploadedEvent.create(
                document.getId(),
                document.getName(),
                document.getContentType(),
                document.getFileSize()
        );

        // 3. Create and save OutboxEvent in the SAME transaction
        OutboxEvent outboxEvent = createOutboxEvent(event, document.getId(), correlationId);
        outboxEventRepository.save(outboxEvent);
        
        MDC.put(MdcKeys.EVENT_ID, event.eventId().toString());
        log.info("DOCUMENT_UPLOAD_COMPLETED");

        // 4. Return response
        return mapToResponse(document);
    }

    /**
     * Get document by ID.
     * 
     * @param id Document ID
     * @return Document response
     * @throws DocumentNotFoundException if document not found
     */
    @Transactional(readOnly = true)
    public DocumentResponse getDocument(UUID id) {
        log.debug("Fetching document with ID: {}", id);
        
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
        
        return mapToResponse(document);
    }

    /**
     * Create an OutboxEvent from an integration event.
     * Converts the event to JSON for storage, including correlation ID.
     */
    private OutboxEvent createOutboxEvent(DocumentUploadedEvent event, UUID aggregateId, String correlationId) {
        try {
            // Add correlation ID to event payload
            var eventWithCorrelation = new DocumentUploadedEventWithCorrelation(
                    event.eventId(),
                    event.eventType(),
                    event.aggregateId(),
                    event.timestamp(),
                    event.documentName(),
                    event.contentType(),
                    event.fileSize(),
                    correlationId
            );
            
            String payload = objectMapper.writeValueAsString(eventWithCorrelation);
            
            return OutboxEvent.builder()
                    .eventId(event.eventId())
                    .eventType(EventTypes.DOCUMENT_UPLOADED)
                    .aggregateType("Document")
                    .aggregateId(aggregateId)
                    .payload(payload)
                    .status(OutboxEvent.OutboxStatus.PENDING)
                    .build();
                    
        } catch (JsonProcessingException e) {
            log.error("EVENT_SERIALIZATION_FAILED", e);
            throw new RuntimeException("Failed to create outbox event", e);
        }
    }
    
    /**
     * Internal record to include correlation ID in event payload.
     */
    private record DocumentUploadedEventWithCorrelation(
            UUID eventId,
            String eventType,
            UUID aggregateId,
            Instant timestamp,
            String documentName,
            String contentType,
            Long fileSize,
            String correlationId
    ) {}

    /**
     * Map Document entity to response DTO.
     */
    private DocumentResponse mapToResponse(Document document) {
        return DocumentResponse.builder()
                .id(document.getId())
                .name(document.getName())
                .contentType(document.getContentType())
                .fileSize(document.getFileSize())
                .status(document.getStatus())
                .metadata(document.getMetadata())
                .correlationId(document.getCorrelationId())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .createdBy(document.getCreatedBy())
                .updatedBy(document.getUpdatedBy())
                .build();
    }

    /**
     * Exception thrown when document is not found.
     */
    public static class DocumentNotFoundException extends RuntimeException {
        public DocumentNotFoundException(UUID id) {
            super("Document not found with ID: " + id);
        }
    }
}
