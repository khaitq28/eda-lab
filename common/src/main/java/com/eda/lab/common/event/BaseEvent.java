package com.eda.lab.common.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.UUID;

/**
 * Sealed base class for all integration events in the EDA system.
 * Uses Java 21 sealed classes to restrict which events can exist in the system.
 * 
 * All events are immutable and contain:
 * - eventId: unique identifier for idempotency
 * - eventType: discriminator for event routing
 * - aggregateId: the document ID this event relates to
 * - timestamp: when the event was created
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "eventType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = DocumentUploadedEvent.class, name = EventTypes.DOCUMENT_UPLOADED),
    @JsonSubTypes.Type(value = DocumentValidatedEvent.class, name = EventTypes.DOCUMENT_VALIDATED),
    @JsonSubTypes.Type(value = DocumentRejectedEvent.class, name = EventTypes.DOCUMENT_REJECTED),
    @JsonSubTypes.Type(value = DocumentEnrichedEvent.class, name = EventTypes.DOCUMENT_ENRICHED)
})
public sealed interface BaseEvent
    permits DocumentUploadedEvent, DocumentValidatedEvent, DocumentRejectedEvent, DocumentEnrichedEvent {

    UUID eventId();
    String eventType();
    UUID aggregateId();
    
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant timestamp();
}
