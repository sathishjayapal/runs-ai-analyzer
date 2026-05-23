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
