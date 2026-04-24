-- Create analysis processing log table for idempotency and reconciliation
CREATE TABLE analysis_processing_log (
    id BIGSERIAL PRIMARY KEY,
    activity_id VARCHAR(255) NOT NULL,
    database_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    processing_status VARCHAR(50) NOT NULL,
    document_id VARCHAR(255),
    retry_count INTEGER DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP,
    last_retry_at TIMESTAMP,
    CONSTRAINT uk_activity_database UNIQUE (activity_id, database_id)
);

-- Create indexes for efficient querying
CREATE INDEX idx_activity_id ON analysis_processing_log(activity_id);
CREATE INDEX idx_processing_status ON analysis_processing_log(processing_status);
CREATE INDEX idx_created_at ON analysis_processing_log(created_at);
CREATE INDEX idx_database_id ON analysis_processing_log(database_id);
CREATE INDEX idx_retry_lookup ON analysis_processing_log(processing_status, retry_count, last_retry_at);

-- Add comment
COMMENT ON TABLE analysis_processing_log IS 'Tracks processing status of Garmin run events for idempotency and reconciliation';
