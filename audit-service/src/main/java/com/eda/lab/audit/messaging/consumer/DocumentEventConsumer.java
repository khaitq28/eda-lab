package com.eda.lab.audit.messaging.consumer;

import com.eda.lab.audit.config.RabbitMQConfig;
import com.eda.lab.audit.domain.entity.AuditLog;
import com.eda.lab.audit.domain.repository.AuditLogRepository;
import com.eda.lab.common.observability.MessageMdcContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Consumer for ALL document events - stores immutable audit log.
 * 
 * Responsibilities:
 * 1. Consume ALL document events from document.audit.q
 * 2. Extract event metadata (eventId, eventType, aggregateId, routing key)
 * 3. Store in audit_log table (idempotent - duplicate event_id is ignored)
 * 4. Handle technical failures with retry/DLQ
 * 
 * Events Consumed:
 * - DocumentUploaded (routing key: document.uploaded)
 * - DocumentValidated (routing key: document.validated)
 * - DocumentRejected (routing key: document.rejected)
 * - DocumentEnriched (routing key: document.enriched)
 * 
 * Patterns Implemented:
 * - Idempotent Consumer (UNIQUE constraint on event_id)
 * - Retry with exponential backoff (5 attempts: 1s, 2s, 4s, 8s, 10s)
 * - Dead Letter Queue (after max retries)
 * - Immutable Audit Log (no updates, only inserts)
 * 
 * Message Flow:
 * 1. Receive message from document.audit.q
 * 2. Extract eventId from messageId (required)
 * 3. Extract routing key from message properties
 * 4. Parse message body to get eventType and aggregateId
 * 5. Insert into audit_log (if duplicate, ignore gracefully)
 * 6. ACK message (success) or throw exception (retry/DLQ)
 * 
 * Error Handling:
 * - Duplicate event_id → log "idempotent skip" and ACK (no retry)
 * - Technical failures (JSON parse, DB down) → throw exception → retry → DLQ
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentEventConsumer {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * Handle ALL document events.
     * 
     * @param message RabbitMQ message with document event
     */
    @RabbitListener(queues = RabbitMQConfig.AUDIT_QUEUE)
    @Transactional
    public void handleDocumentEvent(Message message) {
        try (MessageMdcContext mdcContext = new MessageMdcContext(message, objectMapper)) {
            processMessage(message);
        }
    }
    
    private void processMessage(Message message) {
        try {
            // Extract message properties
            MessageProperties props = message.getMessageProperties();
            String routingKey = props.getReceivedRoutingKey();
            
            // Extract eventId from messageId (required for idempotency)
            String messageId = props.getMessageId();
            if (messageId == null || messageId.isBlank()) {
                log.error("EVENT_INVALID: Message received without messageId");
                throw new IllegalArgumentException("messageId is required for audit logging");
            }

            UUID eventId = UUID.fromString(messageId);
            log.info("EVENT_RECEIVED");

            // Check if already audited (idempotency)
            if (auditLogRepository.existsByEventId(eventId)) {
                log.info("EVENT_SKIPPED_IDEMPOTENT");
                return; // ACK and skip
            }

            // Parse message body to extract eventType and aggregateId
            String messageBody = new String(message.getBody());
            JsonNode eventJson = objectMapper.readTree(messageBody);
            
            String eventType = extractEventType(eventJson, props);
            UUID aggregateId = extractAggregateId(eventJson);
            String aggregateType = extractAggregateType(eventJson, "Document");
            String correlationId = props.getCorrelationId();

            log.debug("EVENT_PROCESSING");

            // Create and save audit log
            AuditLog auditLog = new AuditLog(
                    eventId,
                    eventType,
                    aggregateId,
                    aggregateType,
                    routingKey,
                    messageBody,
                    messageId,
                    correlationId
            );

            try {
                auditLogRepository.save(auditLog);
                log.info("EVENT_PROCESSED");
                
            } catch (DataIntegrityViolationException e) {
                // Duplicate event_id (race condition) - treat as idempotent
                if (e.getMessage() != null && e.getMessage().contains("unique_event_id")) {
                    log.info("EVENT_SKIPPED_IDEMPOTENT");
                    return; // ACK and skip
                }
                throw e; // Other DB constraint violation - retry
            }

        } catch (IllegalArgumentException e) {
            log.error("EVENT_PARSE_FAILED: error={}", e.getMessage(), e);
            throw new RuntimeException("Invalid message format", e); // Trigger retry/DLQ
            
        } catch (Exception e) {
            log.error("TECHNICAL_FAILURE: (will retry if attempts remain)", e);
            throw new RuntimeException("Failed to audit event", e); // Trigger retry/DLQ
        }
    }

    /**
     * Extract eventType from JSON or message headers.
     * 
     * @param eventJson Parsed event JSON
     * @param props Message properties
     * @return Event type
     */
    private String extractEventType(JsonNode eventJson, MessageProperties props) {
        // Try to get from header first
        Object eventTypeHeader = props.getHeader("eventType");
        if (eventTypeHeader != null) {
            return eventTypeHeader.toString();
        }
        
        // Try to get from JSON payload
        if (eventJson.has("eventType")) {
            return eventJson.get("eventType").asText();
        }
        
        // Fallback: derive from routing key (document.uploaded → DocumentUploaded)
        String routingKey = props.getReceivedRoutingKey();
        return deriveEventTypeFromRoutingKey(routingKey);
    }

    /**
     * Extract aggregateId from JSON or message headers.
     * 
     * @param eventJson Parsed event JSON
     * @return Aggregate ID
     */
    private UUID extractAggregateId(JsonNode eventJson) {
        // Try common field names
        if (eventJson.has("documentId")) {
            return UUID.fromString(eventJson.get("documentId").asText());
        }
        if (eventJson.has("aggregateId")) {
            return UUID.fromString(eventJson.get("aggregateId").asText());
        }
        
        throw new IllegalArgumentException("Could not extract aggregateId from event JSON");
    }

    /**
     * Extract aggregateType from JSON or use default.
     * 
     * @param eventJson Parsed event JSON
     * @param defaultValue Default value if not found
     * @return Aggregate type
     */
    private String extractAggregateType(JsonNode eventJson, String defaultValue) {
        if (eventJson.has("aggregateType")) {
            return eventJson.get("aggregateType").asText();
        }
        return defaultValue;
    }

    /**
     * Derive event type from routing key.
     * Example: "document.uploaded" → "DocumentUploaded"
     * 
     * @param routingKey RabbitMQ routing key
     * @return Event type
     */
    private String deriveEventTypeFromRoutingKey(String routingKey) {
        if (routingKey == null || routingKey.isBlank()) {
            return "Unknown";
        }
        
        // Split by dot and capitalize each part
        String[] parts = routingKey.split("\\.");
        StringBuilder eventType = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                eventType.append(Character.toUpperCase(part.charAt(0)))
                        .append(part.substring(1));
            }
        }
        return eventType.toString();
    }
}
