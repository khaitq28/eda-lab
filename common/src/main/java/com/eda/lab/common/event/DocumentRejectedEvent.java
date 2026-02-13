package com.eda.lab.common.event;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published when a document fails validation.
 * Uses Java 21 Record for immutability and conciseness.
 * 
 * Published by: Validation Service
 * Consumed by: Audit Service
 */
public record DocumentRejectedEvent(
    UUID eventId,
    String eventType,
    UUID aggregateId,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant timestamp,
    String rejectionReason,
    String failedValidationRule
) implements BaseEvent {

    /**
     * Factory method for creating events with default eventType.
     */
    public static DocumentRejectedEvent create(UUID aggregateId, String rejectionReason, 
                                                String failedValidationRule) {
        return new DocumentRejectedEvent(
            UUID.randomUUID(),
            EventTypes.DOCUMENT_REJECTED,
            aggregateId,
            Instant.now(),
            rejectionReason,
            failedValidationRule
        );
    }
}
