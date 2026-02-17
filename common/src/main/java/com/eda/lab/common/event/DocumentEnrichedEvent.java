package com.eda.lab.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Event emitted when a document has been successfully enriched.
 * 
 * Enrichment includes:
 * - Classification
 * - Metadata extraction
 * - Content analysis
 * - Additional processing
 * 
 * This event is published by enrichment-service via Transactional Outbox
 * and consumed by audit-service for event logging.
 */
public record DocumentEnrichedEvent(
        UUID eventId,
        UUID documentId,
        Instant enrichedAt,
        String enrichmentType,
        Instant timestamp
) implements BaseEvent {

    public DocumentEnrichedEvent {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId cannot be null");
        }
        if (documentId == null) {
            throw new IllegalArgumentException("documentId cannot be null");
        }
        if (enrichedAt == null) {
            throw new IllegalArgumentException("enrichedAt cannot be null");
        }
        if (enrichmentType == null || enrichmentType.isBlank()) {
            throw new IllegalArgumentException("enrichmentType cannot be null or blank");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp cannot be null");
        }
    }

    @Override
    public String eventType() {
        return "DocumentEnriched";
    }

    @Override
    public UUID aggregateId() {
        return documentId;
    }
}
