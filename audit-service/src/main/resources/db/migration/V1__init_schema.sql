-- Initial schema for Audit Service
-- This migration creates the base tables needed for the service

-- Processed events table for idempotency (inbox pattern)
-- Audit service still needs idempotency to avoid duplicate audit logs
CREATE TABLE IF NOT EXISTS processed_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_processed_events_type ON processed_events(event_type);

-- Audit log table - immutable store of all document events
CREATE TABLE IF NOT EXISTS audit_log (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    aggregate_id UUID NOT NULL,
    payload JSONB NOT NULL,
    event_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_aggregate_id ON audit_log(aggregate_id);
CREATE INDEX idx_audit_log_event_type ON audit_log(event_type);
CREATE INDEX idx_audit_log_event_timestamp ON audit_log(event_timestamp);

-- Note: Audit service does NOT have an outbox table
-- It is a read-only observer that does not produce events
