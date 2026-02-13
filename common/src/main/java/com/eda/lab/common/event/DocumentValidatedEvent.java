package com.eda.lab.common.event;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published when a document passes validation.
 * Uses Java 21 Record for immutability and conciseness.
 * 
 * Published by: Validation Service
 * Consumed by: Enrichment Service, Audit Service
 */
public record DocumentValidatedEvent(
    UUID eventId,
    String eventType,
    UUID aggregateId,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant timestamp,
    String validationResult,
    String validatedBy
) implements BaseEvent {

    /**
     * Factory method for creating events with default eventType.
     */
    public static DocumentValidatedEvent create(UUID aggregateId, String validationResult, 
                                                 String validatedBy) {
        return new DocumentValidatedEvent(
            UUID.randomUUID(),
            EventTypes.DOCUMENT_VALIDATED,
            aggregateId,
            Instant.now(),
            validationResult,
            validatedBy
        );
    }
}
