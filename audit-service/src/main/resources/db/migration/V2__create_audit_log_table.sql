-- Audit Log Table for Event-Driven Architecture
-- Stores an immutable log of all document-related events for observability and debugging

CREATE TABLE audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID UNIQUE NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL DEFAULT 'Document',
    routing_key VARCHAR(255) NOT NULL,
    payload_json TEXT NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Optional metadata
    message_id VARCHAR(255),
    correlation_id VARCHAR(255),
    
    CONSTRAINT unique_event_id UNIQUE (event_id)
);

-- Index for querying by aggregate (document) ID
CREATE INDEX idx_audit_log_aggregate_id ON audit_log(aggregate_id, received_at DESC);

-- Index for querying by event type
CREATE INDEX idx_audit_log_event_type ON audit_log(event_type, received_at DESC);

-- Index for querying by routing key
CREATE INDEX idx_audit_log_routing_key ON audit_log(routing_key, received_at DESC);

-- Index for time-based queries
CREATE INDEX idx_audit_log_received_at ON audit_log(received_at DESC);

COMMENT ON TABLE audit_log IS 'Immutable audit log of all document events for observability';
COMMENT ON COLUMN audit_log.id IS 'Primary key (internal)';
COMMENT ON COLUMN audit_log.event_id IS 'Business event ID from message (for idempotency)';
COMMENT ON COLUMN audit_log.event_type IS 'Type of event (DocumentUploaded, DocumentValidated, etc.)';
COMMENT ON COLUMN audit_log.aggregate_id IS 'Document ID this event relates to';
COMMENT ON COLUMN audit_log.aggregate_type IS 'Type of aggregate (Document)';
COMMENT ON COLUMN audit_log.routing_key IS 'RabbitMQ routing key (document.uploaded, document.validated, etc.)';
COMMENT ON COLUMN audit_log.payload_json IS 'Full event payload as JSON';
COMMENT ON COLUMN audit_log.received_at IS 'When the event was received by audit-service';
COMMENT ON COLUMN audit_log.message_id IS 'RabbitMQ message ID';
COMMENT ON COLUMN audit_log.correlation_id IS 'Correlation ID for distributed tracing';
