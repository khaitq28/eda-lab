package com.eda.lab.common.event;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Event published when a document has been enriched with metadata.
 * Uses Java 21 Record for immutability and conciseness.
 * 
 * Published by: Enrichment Service
 * Consumed by: Audit Service
 */
public record DocumentEnrichedEvent(
    UUID eventId,
    String eventType,
    UUID aggregateId,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant timestamp,
    String classification,
    Map<String, String> extractedMetadata
) implements BaseEvent {

    /**
     * Factory method for creating events with default eventType.
     */
    public static DocumentEnrichedEvent create(UUID aggregateId, String classification, 
                                                Map<String, String> extractedMetadata) {
        return new DocumentEnrichedEvent(
            UUID.randomUUID(),
            EventTypes.DOCUMENT_ENRICHED,
            aggregateId,
            Instant.now(),
            classification,
            Map.copyOf(extractedMetadata) // Defensive copy for true immutability
        );
    }
}
