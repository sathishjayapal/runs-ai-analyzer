CREATE TABLE analysis_job
(
    id            UUID PRIMARY KEY,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP,
    completed_at  TIMESTAMP,
    error_message TEXT,
    result        JSONB
);

CREATE INDEX idx_analysis_job_status ON analysis_job (status);
