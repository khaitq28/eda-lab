package com.eda.lab.common.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.UUID;

/**
 * Auto-closeable context for populating MDC from RabbitMQ messages.
 * Use with try-with-resources to ensure MDC is cleared after processing.
 * 
 * Example:
 * <pre>
 * try (MessageMdcContext mdc = new MessageMdcContext(message, objectMapper)) {
 *     log.info("Processing event"); // Will include MDC fields
 * }
 * </pre>
 */
public class MessageMdcContext implements AutoCloseable {
    
    /**
     * Create MDC context from RabbitMQ message.
     * Extracts correlation ID, event ID, document ID, routing key, and event type.
     * 
     * @param message RabbitMQ message
     * @param objectMapper Jackson ObjectMapper for parsing JSON payload
     */
    public MessageMdcContext(Message message, ObjectMapper objectMapper) {
        MessageProperties props = message.getMessageProperties();
        
        // Correlation ID (from header or property)
        String correlationId = props.getCorrelationId();
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = (String) props.getHeader("correlationId");
        }
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MdcKeys.CORRELATION_ID, correlationId);
        
        // Event ID (from messageId)
        String eventId = props.getMessageId();
        if (eventId != null) {
            MDC.put(MdcKeys.EVENT_ID, eventId);
        }
        
        // Document ID (from header or payload)
        Object aggregateIdHeader = props.getHeader("aggregateId");
        if (aggregateIdHeader != null) {
            MDC.put(MdcKeys.DOCUMENT_ID, aggregateIdHeader.toString());
        } else {
            // Try to extract from payload
            try {
                String messageBody = new String(message.getBody());
                JsonNode eventJson = objectMapper.readTree(messageBody);
                if (eventJson.has("documentId")) {
                    MDC.put(MdcKeys.DOCUMENT_ID, eventJson.get("documentId").asText());
                } else if (eventJson.has("aggregateId")) {
                    MDC.put(MdcKeys.DOCUMENT_ID, eventJson.get("aggregateId").asText());
                }
            } catch (Exception e) {
                // Ignore - not critical
            }
        }
        
        // Routing Key
        String routingKey = props.getReceivedRoutingKey();
        if (routingKey != null) {
            MDC.put(MdcKeys.ROUTING_KEY, routingKey);
        }
        
        // Event Type
        Object eventType = props.getHeader("eventType");
        if (eventType != null) {
            MDC.put(MdcKeys.EVENT_TYPE, eventType.toString());
        }
    }
    
    @Override
    public void close() {
        MDC.clear();
    }
}
