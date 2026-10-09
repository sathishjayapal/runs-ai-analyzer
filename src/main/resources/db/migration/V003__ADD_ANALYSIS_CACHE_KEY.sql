-- Exact-match cache key for run analyses.
--
-- Previously the "Analyze My Runs" cache was a pgvector similarity lookup (threshold 0.85).
-- Embeddings of two run lists that share 9 of 10 runs are nearly identical, so a newly
-- imported run still hit the old analysis. cache_key is a SHA-256 of the exact LLM input
-- (system prompt + run data), so any new or edited run produces a different key.
--
-- cache_evicted_at is set when runs-app publishes a new/updated Garmin run event. Evicted
-- rows stay in the table (and in the vector store) for RAG search and "Recent analyses";
-- they are just no longer served as a cache hit.
--
-- Existing rows get cache_key = NULL, so they will never be served from cache again.

ALTER TABLE run_analysis_document ADD COLUMN cache_key VARCHAR(64);
ALTER TABLE run_analysis_document ADD COLUMN cache_evicted_at TIMESTAMP WITHOUT TIME ZONE;

CREATE INDEX idx_run_analysis_document_active_cache_key
    ON run_analysis_document (cache_key, created_at DESC)
    WHERE cache_evicted_at IS NULL;
