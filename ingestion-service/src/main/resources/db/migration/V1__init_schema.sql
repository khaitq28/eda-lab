-- Initial schema for Ingestion Service
-- This migration creates the base tables needed for the service

-- Outbox table for transactional outbox pattern
CREATE TABLE IF NOT EXISTS outbox (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(255) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    sent_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING'
);

CREATE INDEX idx_outbox_status ON outbox(status);
CREATE INDEX idx_outbox_created_at ON outbox(created_at);

-- Placeholder for future document table
-- Documents will be added when business logic is implemented
