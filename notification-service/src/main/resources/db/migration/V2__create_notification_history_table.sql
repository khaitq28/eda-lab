-- Notification History Table
-- Stores history of all notifications sent (for audit and debugging)

CREATE TABLE notification_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID UNIQUE NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    aggregate_id UUID NOT NULL,
    routing_key VARCHAR(255) NOT NULL,
    notification_type VARCHAR(100) NOT NULL, -- EMAIL, SMS, PUSH, etc.
    recipient VARCHAR(255) NOT NULL,
    subject VARCHAR(500),
    message TEXT NOT NULL,
    status VARCHAR(50) NOT NULL, -- SENT, FAILED
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    error_message TEXT
);

-- Index for querying by aggregate (document) ID
CREATE INDEX idx_notification_history_aggregate_id ON notification_history(aggregate_id, sent_at DESC);

-- Index for querying by event type
CREATE INDEX idx_notification_history_event_type ON notification_history(event_type, sent_at DESC);

-- Index for querying by recipient
CREATE INDEX idx_notification_history_recipient ON notification_history(recipient, sent_at DESC);

-- Index for time-based queries
CREATE INDEX idx_notification_history_sent_at ON notification_history(sent_at DESC);

COMMENT ON TABLE notification_history IS 'History of all notifications sent for observability';
COMMENT ON COLUMN notification_history.event_id IS 'Event ID from RabbitMQ message (for idempotency)';
COMMENT ON COLUMN notification_history.notification_type IS 'Type of notification (EMAIL, SMS, PUSH)';
COMMENT ON COLUMN notification_history.status IS 'SENT (success) or FAILED';
