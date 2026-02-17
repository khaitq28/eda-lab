-- Processed Events Table for Idempotent Consumer Pattern
-- Tracks which events have already been processed to prevent duplicate processing

CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    aggregate_id UUID,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index for querying by aggregate (document) ID
CREATE INDEX idx_processed_events_aggregate_id ON processed_events(aggregate_id);

-- Index for querying by processing time (for monitoring/cleanup)
CREATE INDEX idx_processed_events_processed_at ON processed_events(processed_at);

COMMENT ON TABLE processed_events IS 'Tracks processed events for idempotency';
COMMENT ON COLUMN processed_events.event_id IS 'Unique event identifier from message messageId';
COMMENT ON COLUMN processed_events.event_type IS 'Type of event (DocumentValidated, etc.)';
COMMENT ON COLUMN processed_events.aggregate_id IS 'Document ID this event relates to';
COMMENT ON COLUMN processed_events.processed_at IS 'When this event was successfully processed';
