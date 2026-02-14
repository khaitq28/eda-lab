-- ============================================================
-- Idempotency Table for Event Processing
-- ============================================================
-- This table tracks which events have been processed to ensure
-- idempotent message consumption (at-least-once delivery).
--
-- Key Design Decisions:
-- - event_id as PRIMARY KEY ensures uniqueness (no duplicates)
-- - processed_at for auditing and monitoring
-- - Minimal schema (only what's needed for idempotency)
-- - No foreign keys (events may reference aggregates in other services)
--
-- Usage Pattern:
-- 1. Consumer receives message with eventId
-- 2. Check: SELECT EXISTS(SELECT 1 FROM processed_events WHERE event_id = ?)
-- 3. If exists => ACK and skip (idempotent)
-- 4. If not exists => INSERT event_id, then process message
--
-- Archival Strategy (Production):
-- - Periodically archive old records (e.g., > 30 days)
-- - Or partition by processed_at for efficient cleanup
-- ============================================================

CREATE TABLE processed_events (
    -- Event ID from message (messageId or header)
    event_id UUID PRIMARY KEY,
    
    -- When this event was first processed
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    -- Optional: Event type for debugging/monitoring
    event_type VARCHAR(100),
    
    -- Optional: Aggregate ID for correlation
    aggregate_id UUID
);

-- Index for monitoring queries (e.g., "events processed in last hour")
CREATE INDEX idx_processed_events_processed_at ON processed_events(processed_at);

-- Index for aggregate correlation (optional, useful for debugging)
CREATE INDEX idx_processed_events_aggregate_id ON processed_events(aggregate_id);

-- ============================================================
-- Comments for Documentation
-- ============================================================

COMMENT ON TABLE processed_events IS 
'Tracks processed events for idempotent message consumption. Prevents duplicate processing in at-least-once delivery scenarios.';

COMMENT ON COLUMN processed_events.event_id IS 
'Unique event identifier from message properties (messageId). Primary key ensures no duplicates.';

COMMENT ON COLUMN processed_events.processed_at IS 
'Timestamp when event was first successfully processed. Used for monitoring and archival.';

COMMENT ON COLUMN processed_events.event_type IS 
'Optional event type for debugging (e.g., DocumentUploaded). Helps with monitoring and troubleshooting.';

COMMENT ON COLUMN processed_events.aggregate_id IS 
'Optional aggregate ID (e.g., documentId). Useful for correlation and debugging.';
