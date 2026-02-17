package com.eda.lab.notification.messaging.consumer;

import com.eda.lab.common.observability.MessageMdcContext;
import com.eda.lab.notification.config.RabbitMQConfig;
import com.eda.lab.notification.domain.entity.NotificationHistory;
import com.eda.lab.notification.domain.repository.NotificationHistoryRepository;
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
 * Consumer for document events - sends notifications to users.
 * 
 * Responsibilities:
 * 1. Consume DocumentValidated, DocumentRejected, and DocumentEnriched events
 * 2. Send appropriate notifications (simulated via logging)
 * 3. Store notification history (idempotent)
 * 4. Handle technical failures with retry/DLQ
 * 
 * Events Consumed:
 * - DocumentValidated (routing key: document.validated) → "Your document was validated ✅"
 * - DocumentRejected (routing key: document.rejected) → "Your document was rejected ❌"
 * - DocumentEnriched (routing key: document.enriched) → "Your document processing is complete! 🎉"
 * 
 * Patterns Implemented:
 * - Idempotent Consumer (UNIQUE constraint on event_id)
 * - Retry with exponential backoff (5 attempts)
 * - Dead Letter Queue (after max retries)
 * - Fan-out pattern (multiple services consume same event type)
 * 
 * Simple Version:
 * - Notifications are simulated via logging (no actual email sending)
 * - Focus on RabbitMQ topology and EDA patterns
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentEventConsumer {

    private final NotificationHistoryRepository notificationHistoryRepository;
    private final ObjectMapper objectMapper;

    /**
     * Handle ALL notification events.
     * 
     * @param message RabbitMQ message
     */
    @RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE)
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
                throw new IllegalArgumentException("messageId is required for notification");
            }

            UUID eventId = UUID.fromString(messageId);
            log.info("EVENT_RECEIVED");

            // Check if notification already sent (idempotency)
            if (notificationHistoryRepository.existsByEventId(eventId)) {
                log.info("EVENT_SKIPPED_IDEMPOTENT");
                return; // ACK and skip
            }

            // Parse message body
            String messageBody = new String(message.getBody());
            JsonNode eventJson = objectMapper.readTree(messageBody);
            
            String eventType = extractEventType(eventJson, props);
            UUID documentId = extractDocumentId(eventJson);

            log.debug("EVENT_PROCESSING");

            // Send notification based on event type
            sendNotification(eventId, eventType, documentId, routingKey, eventJson);

        } catch (IllegalArgumentException e) {
            // Missing messageId or invalid format → technical failure
            log.error("Invalid message format: {}", e.getMessage(), e);
            throw new RuntimeException("Invalid message format", e); // Trigger retry/DLQ
            
        } catch (Exception e) {
            // Any other exception → technical failure
            log.error("Failed to send notification: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to send notification", e); // Trigger retry/DLQ
        }
    }

    /**
     * Send notification based on event type.
     * 
     * @param eventId Event ID
     * @param eventType Event type
     * @param documentId Document ID
     * @param routingKey Routing key
     * @param eventJson Event JSON
     */
    private void sendNotification(UUID eventId, String eventType, UUID documentId, 
                                   String routingKey, JsonNode eventJson) {
        try {
            String recipient = extractRecipient(eventJson);
            String subject;
            String message;

            // Determine notification content based on event type
            switch (eventType) {
                case "DocumentValidated" -> {
                    subject = "Document Validated Successfully ✅";
                    message = String.format(
                            "Good news! Your document (ID: %s) has been validated successfully and is now being processed.",
                            documentId
                    );
                    log.info("📧 [NOTIFICATION] Sending validation success notification to {}", recipient);
                }
                case "DocumentRejected" -> {
                    String reason = extractRejectionReason(eventJson);
                    subject = "Document Rejected ❌";
                    message = String.format(
                            "Unfortunately, your document (ID: %s) was rejected. Reason: %s. " +
                            "Please correct the issues and submit again.",
                            documentId, reason
                    );
                    log.warn("📧 [NOTIFICATION] Sending rejection notification to {}", recipient);
                }
                case "DocumentEnriched" -> {
                    subject = "Document Processing Complete! 🎉";
                    message = String.format(
                            "Great news! Your document (ID: %s) has been fully processed and enriched. " +
                            "It is now ready for use.",
                            documentId
                    );
                    log.info("📧 [NOTIFICATION] Sending completion notification to {}", recipient);
                }
                default -> {
                    log.warn("Unknown event type for notification: {}", eventType);
                    return; // Skip unknown event types
                }
            }

            // Simulate sending notification (in production: call email service, SMS API, etc.)
            log.info("📬 [SIMULATED EMAIL]");
            log.info("  To: {}", recipient);
            log.info("  Subject: {}", subject);
            log.info("  Message: {}", message);
            log.info("  Event Type: {}", eventType);
            log.info("  Document ID: {}", documentId);

            // Store notification history
            NotificationHistory history = new NotificationHistory(
                    eventId,
                    eventType,
                    documentId,
                    routingKey,
                    "EMAIL", // Notification type
                    recipient,
                    subject,
                    message
            );

            try {
                notificationHistoryRepository.save(history);
                log.info("EVENT_PROCESSED: notificationType=EMAIL");
                
            } catch (DataIntegrityViolationException e) {
                // Duplicate event_id (race condition) - treat as idempotent
                if (e.getMessage() != null && e.getMessage().contains("event_id")) {
                    log.info("EVENT_SKIPPED_IDEMPOTENT");
                    return; // ACK and skip
                }
                throw e; // Other DB constraint violation - retry
            }

        } catch (IllegalArgumentException e) {
            log.error("EVENT_PARSE_FAILED: error={}", e.getMessage(), e);
            throw new RuntimeException("Invalid message format", e);
            
        } catch (Exception e) {
            log.error("TECHNICAL_FAILURE: (will retry if attempts remain)", e);
            throw new RuntimeException("Failed to send notification", e);
        }
    }

    /**
     * Extract event type from JSON or headers.
     */
    private String extractEventType(JsonNode eventJson, MessageProperties props) {
        Object eventTypeHeader = props.getHeader("eventType");
        if (eventTypeHeader != null) {
            return eventTypeHeader.toString();
        }
        
        if (eventJson.has("eventType")) {
            return eventJson.get("eventType").asText();
        }
        
        throw new IllegalArgumentException("Could not extract eventType from message");
    }

    /**
     * Extract document ID from JSON.
     */
    private UUID extractDocumentId(JsonNode eventJson) {
        if (eventJson.has("documentId")) {
            return UUID.fromString(eventJson.get("documentId").asText());
        }
        if (eventJson.has("aggregateId")) {
            return UUID.fromString(eventJson.get("aggregateId").asText());
        }
        throw new IllegalArgumentException("Could not extract documentId from event JSON");
    }

    /**
     * Extract recipient (user email) from JSON.
     * In production, this would come from user profile lookup.
     */
    private String extractRecipient(JsonNode eventJson) {
        // Try to get from uploadedBy field
        if (eventJson.has("uploadedBy")) {
            return eventJson.get("uploadedBy").asText();
        }
        
        // Fallback to default (in production, lookup user by documentId)
        return "user@example.com";
    }

    /**
     * Extract rejection reason from DocumentRejected event.
     */
    private String extractRejectionReason(JsonNode eventJson) {
        if (eventJson.has("reason")) {
            return eventJson.get("reason").asText();
        }
        return "Document did not meet validation requirements";
    }
}
