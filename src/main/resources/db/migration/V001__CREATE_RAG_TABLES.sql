CREATE EXTENSION IF NOT EXISTS vector;

CREATE SEQUENCE IF NOT EXISTS primary_sequence START WITH 1 INCREMENT BY 1;

CREATE TABLE run_analysis_document (
    id BIGINT NOT NULL DEFAULT nextval('primary_sequence'),
    document_id UUID NOT NULL UNIQUE,
    activity_ids TEXT NOT NULL,
    query_text TEXT NOT NULL,
    analysis_content TEXT NOT NULL,
    summary TEXT,
    total_runs INTEGER,
    total_distance_km DOUBLE PRECISION,
    metadata JSONB,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT run_analysis_document_pkey PRIMARY KEY (id)
);

CREATE INDEX idx_run_analysis_document_created_at ON run_analysis_document(created_at DESC);
CREATE INDEX idx_run_analysis_document_document_id ON run_analysis_document(document_id);
