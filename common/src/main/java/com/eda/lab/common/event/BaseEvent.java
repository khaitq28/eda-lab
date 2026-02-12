package com.eda.lab.common.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.UUID;

/**
 * Base class for all integration events in the EDA system.
 * 
 * All events are immutable and contain:
 * - eventId: unique identifier for idempotency
 * - eventType: discriminator for event routing
 * - aggregateId: the document ID this event relates to
 * - timestamp: when the event was created
 */
public abstract class BaseEvent {

    private final UUID eventId;
    private final String eventType;
    private final UUID aggregateId;
    
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private final Instant timestamp;

    protected BaseEvent(UUID eventId, String eventType, UUID aggregateId, Instant timestamp) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.timestamp = timestamp;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
