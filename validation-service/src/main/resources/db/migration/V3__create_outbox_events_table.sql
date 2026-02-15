-- ============================================================
-- Transactional Outbox Table for Event Publishing
-- ============================================================
-- This table implements the Transactional Outbox pattern for
-- reliable event publishing from validation-service.
--
-- Purpose:
-- - Store events to be published to RabbitMQ
-- - Ensure atomicity with business logic (processed_events)
-- - Enable retry and failure handling
--
-- Events Published:
-- - DocumentValidated (when validation succeeds)
-- - DocumentRejected (when validation fails)
-- ============================================================

CREATE TABLE outbox_events (
    -- Primary key
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Event identification (for consumer idempotency)
    event_id UUID UNIQUE NOT NULL,
    
    -- Event metadata
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL DEFAULT 'Document',
    aggregate_id UUID NOT NULL,
    
    -- Event payload (full event as JSON)
    payload_json JSONB NOT NULL,
    
    -- Publishing status
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- PENDING: Not yet published
    -- SENT: Successfully published to RabbitMQ
    -- FAILED: Max retries exceeded
    
    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    sent_at TIMESTAMP WITH TIME ZONE,
    
    -- Retry handling
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    next_retry_at TIMESTAMP WITH TIME ZONE,
    
    -- Constraints
    CONSTRAINT chk_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

-- ============================================================
-- Indexes for Performance
-- ============================================================

-- Index for finding pending events to publish (most important query)
CREATE INDEX idx_outbox_status_created 
ON outbox_events(status, created_at) 
WHERE status = 'PENDING';

-- Index for retry logic (events that need retry)
CREATE INDEX idx_outbox_retry 
ON outbox_events(status, next_retry_at) 
WHERE status = 'FAILED' AND next_retry_at IS NOT NULL;

-- Index for event_id lookups (deduplication)
CREATE INDEX idx_outbox_event_id 
ON outbox_events(event_id);

-- Index for aggregate correlation (debugging/monitoring)
CREATE INDEX idx_outbox_aggregate_id 
ON outbox_events(aggregate_id);

-- Index for monitoring queries (events by type)
CREATE INDEX idx_outbox_event_type 
ON outbox_events(event_type);

-- ============================================================
-- Comments for Documentation
-- ============================================================

COMMENT ON TABLE outbox_events IS 
'Transactional Outbox for reliable event publishing. Events are stored atomically with business logic, then published by background process.';

COMMENT ON COLUMN outbox_events.event_id IS 
'Unique event identifier used by consumers for idempotency. Must match eventId in payload_json.';

COMMENT ON COLUMN outbox_events.event_type IS 
'Event type discriminator (e.g., DocumentValidated, DocumentRejected). Used for routing and monitoring.';

COMMENT ON COLUMN outbox_events.aggregate_id IS 
'ID of the document this event relates to. Used for correlation and debugging.';

COMMENT ON COLUMN outbox_events.payload_json IS 
'Full event payload as JSON. Contains all data needed by consumers.';

COMMENT ON COLUMN outbox_events.status IS 
'Publishing status: PENDING (not yet published), SENT (successfully published), FAILED (max retries exceeded).';

COMMENT ON COLUMN outbox_events.retry_count IS 
'Number of publish attempts. Used for exponential backoff and failure detection.';

COMMENT ON COLUMN outbox_events.next_retry_at IS 
'When to retry publishing (for FAILED events with retry_count < max). Implements exponential backoff.';
