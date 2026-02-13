-- Documents table - stores uploaded documents
CREATE TABLE documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'UPLOADED',
    metadata JSONB,
    
    -- Audit fields
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    
    -- Constraints
    CONSTRAINT chk_file_size_positive CHECK (file_size > 0),
    CONSTRAINT chk_status_valid CHECK (status IN ('UPLOADED', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

-- Indexes for common queries
CREATE INDEX idx_documents_status ON documents(status);
CREATE INDEX idx_documents_created_at ON documents(created_at DESC);
CREATE INDEX idx_documents_name ON documents(name);

-- Comments for documentation
COMMENT ON TABLE documents IS 'Stores uploaded documents with metadata';
COMMENT ON COLUMN documents.status IS 'Document processing status: UPLOADED, PROCESSING, COMPLETED, FAILED';
COMMENT ON COLUMN documents.metadata IS 'Additional metadata as JSON (e.g., tags, user info)';
