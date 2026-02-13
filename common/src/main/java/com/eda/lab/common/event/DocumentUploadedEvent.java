package com.eda.lab.common.event;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published when a new document is uploaded to the system.
 * Uses Java 21 Record for immutability and conciseness.
 * 
 * Published by: Ingestion Service
 * Consumed by: Validation Service, Audit Service
 */
public record DocumentUploadedEvent(
    UUID eventId,
    String eventType,
    UUID aggregateId,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant timestamp,
    String documentName,
    String contentType,
    Long fileSize
) implements BaseEvent {

    /**
     * Factory method for creating events with default eventType.
     */
    public static DocumentUploadedEvent create(UUID aggregateId, String documentName, 
                                                String contentType, Long fileSize) {
        return new DocumentUploadedEvent(
            UUID.randomUUID(),
            EventTypes.DOCUMENT_UPLOADED,
            aggregateId,
            Instant.now(),
            documentName,
            contentType,
            fileSize
        );
    }
}
