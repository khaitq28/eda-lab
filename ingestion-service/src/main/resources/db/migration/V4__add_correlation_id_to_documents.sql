-- Add correlation_id column to documents table for traceability
ALTER TABLE documents ADD COLUMN correlation_id VARCHAR(255);

-- Index for searching by correlation ID
CREATE INDEX idx_documents_correlation_id ON documents(correlation_id);

-- Comment
COMMENT ON COLUMN documents.correlation_id IS 'Correlation ID for tracing requests across services';
