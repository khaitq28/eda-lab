-- Outbox events table - implements Transactional Outbox pattern
-- Events are stored in the same transaction as business data,
-- then published asynchronously by a separate process
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Event identification (for idempotency on consumer side)
    event_id UUID NOT NULL UNIQUE,
    event_type VARCHAR(100) NOT NULL,
    
    -- Aggregate reference
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    
    -- Event payload (JSON format for flexibility)
    payload JSONB NOT NULL,
    
    -- Outbox processing status
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    
    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    sent_at TIMESTAMP WITH TIME ZONE,
    
    -- Retry handling
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    next_retry_at TIMESTAMP WITH TIME ZONE,
    
    -- Constraints
    CONSTRAINT chk_status_valid CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT chk_retry_count_non_negative CHECK (retry_count >= 0),
    CONSTRAINT chk_sent_at_when_sent CHECK (
        (status = 'SENT' AND sent_at IS NOT NULL) OR 
        (status != 'SENT' AND sent_at IS NULL)
    )
);

-- Indexes for outbox publisher queries
CREATE INDEX idx_outbox_status_created ON outbox_events(status, created_at) 
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_retry ON outbox_events(status, next_retry_at) 
    WHERE status = 'FAILED' AND next_retry_at IS NOT NULL;

CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_type, aggregate_id);

CREATE INDEX idx_outbox_event_type ON outbox_events(event_type);

-- Comments for documentation
COMMENT ON TABLE outbox_events IS 'Transactional Outbox pattern - events stored with business data, published asynchronously';
COMMENT ON COLUMN outbox_events.event_id IS 'Unique event identifier for consumer idempotency';
COMMENT ON COLUMN outbox_events.status IS 'PENDING: not yet published, SENT: successfully published, FAILED: max retries exceeded';
COMMENT ON COLUMN outbox_events.payload IS 'Full event payload as JSON';
COMMENT ON COLUMN outbox_events.retry_count IS 'Number of publish attempts (for monitoring and retry logic)';
COMMENT ON COLUMN outbox_events.next_retry_at IS 'When to retry failed events (exponential backoff)';
