package com.eda.lab.common.observability;

/**
 * Constants for MDC (Mapped Diagnostic Context) keys.
 * Used for structured logging across all services.
 */
public final class MdcKeys {
    public static final String CORRELATION_ID = "correlationId";
    public static final String EVENT_ID = "eventId";
    public static final String DOCUMENT_ID = "documentId";
    public static final String ROUTING_KEY = "routingKey";
    public static final String EVENT_TYPE = "eventType";
    
    private MdcKeys() {
        throw new UnsupportedOperationException("Utility class");
    }
}
