-- Outbox Events Table for Transactional Outbox Pattern
-- Ensures reliable event publishing with at-least-once delivery guarantee

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    event_id UUID UNIQUE NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    payload_json JSONB NOT NULL,
    status VARCHAR(50) NOT NULL, -- PENDING, SENT, FAILED
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE,
    retry_count INT DEFAULT 0,
    last_error TEXT,
    next_retry_at TIMESTAMP WITH TIME ZONE
);

-- Index for finding the event by its business ID
CREATE INDEX idx_outbox_event_id ON outbox_events(event_id);

-- Critical index for OutboxPublisher to fetch pending events efficiently
-- Partial index only on PENDING status for better performance
CREATE INDEX idx_outbox_status_created ON outbox_events(status, created_at) 
WHERE status = 'PENDING';

-- Index for finding failed events ready for retry
CREATE INDEX idx_outbox_retry ON outbox_events(status, next_retry_at) 
WHERE status = 'FAILED';

-- Combined index for the publisher query (PENDING + next_retry_at check)
CREATE INDEX idx_outbox_pending_next_retry ON outbox_events(status, next_retry_at) 
WHERE status = 'PENDING';

-- Index for monitoring and querying by aggregate (document)
CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_id, created_at);

COMMENT ON TABLE outbox_events IS 'Transactional outbox for reliable event publishing';
COMMENT ON COLUMN outbox_events.id IS 'Primary key (internal)';
COMMENT ON COLUMN outbox_events.event_id IS 'Business event ID (for idempotency on consumer side)';
COMMENT ON COLUMN outbox_events.event_type IS 'Type of event (DocumentEnriched)';
COMMENT ON COLUMN outbox_events.aggregate_type IS 'Type of aggregate (Document)';
COMMENT ON COLUMN outbox_events.aggregate_id IS 'Document ID';
COMMENT ON COLUMN outbox_events.payload_json IS 'Full event payload as JSON';
COMMENT ON COLUMN outbox_events.status IS 'PENDING (not yet sent), SENT (published), FAILED (max retries exceeded)';
COMMENT ON COLUMN outbox_events.created_at IS 'When the event was created';
COMMENT ON COLUMN outbox_events.sent_at IS 'When the event was successfully published to RabbitMQ';
COMMENT ON COLUMN outbox_events.retry_count IS 'Number of publish attempts';
COMMENT ON COLUMN outbox_events.last_error IS 'Last error message if publish failed';
COMMENT ON COLUMN outbox_events.next_retry_at IS 'When to retry publishing (exponential backoff)';
