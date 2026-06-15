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
-- Fix: Create primary_sequence if it doesn't exist (for databases where V001 failed partially)
CREATE SEQUENCE IF NOT EXISTS primary_sequence START WITH 1 INCREMENT BY 1;
-- Create analysis processing log table for idempotency and reconciliation
CREATE TABLE analysis_processing_log
(
    id                BIGSERIAL PRIMARY KEY,
    activity_id       VARCHAR(255) NOT NULL,
    database_id       BIGINT       NOT NULL,
    event_type        VARCHAR(100) NOT NULL,
    processing_status VARCHAR(50)  NOT NULL,
    document_id       VARCHAR(255),
    retry_count       INTEGER DEFAULT 0,
    error_message     TEXT,
    created_at        TIMESTAMP    NOT NULL,
    processed_at      TIMESTAMP,
    last_retry_at     TIMESTAMP,
    CONSTRAINT uk_activity_database UNIQUE (activity_id, database_id)
);
-- Subjective runner journal entries.
-- Authored by the user (not imported), embedded into pgvector for RAG retrieval
-- alongside run analyses. References a run loosely by Garmin activity_id, the same
-- way analysis_processing_log does -- this service does not own run records.

CREATE TABLE run_journal_entry
(
    id               BIGINT                      NOT NULL DEFAULT nextval('primary_sequence'),
    activity_id      VARCHAR(255),
    entry_date       DATE                        NOT NULL,
    perceived_effort SMALLINT,
    feel             VARCHAR(20),
    body_notes       VARCHAR(500),
    context_notes    VARCHAR(500),
    narrative        TEXT,
    embedded         BOOLEAN                     NOT NULL DEFAULT FALSE,
    embedded_at      TIMESTAMP WITHOUT TIME ZONE,
    created_at       TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT run_journal_entry_pkey PRIMARY KEY (id)
);

CREATE INDEX idx_run_journal_entry_activity_id ON run_journal_entry (activity_id);
CREATE INDEX idx_run_journal_entry_entry_date ON run_journal_entry (entry_date DESC);
CREATE INDEX idx_run_journal_entry_embedded ON run_journal_entry (embedded);

COMMENT ON TABLE run_journal_entry IS 'Subjective runner journal entries; embedded into pgvector for RAG retrieval alongside run analyses';
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

