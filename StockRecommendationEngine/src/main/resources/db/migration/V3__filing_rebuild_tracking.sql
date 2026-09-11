ALTER TABLE sec_filings ADD COLUMN processing_version VARCHAR(80);

CREATE TABLE filing_rebuild_runs (
    run_id VARCHAR(36) PRIMARY KEY,
    filing_id BIGINT NOT NULL REFERENCES sec_filings(id) ON DELETE CASCADE,
    processing_version VARCHAR(80) NOT NULL,
    previous_version VARCHAR(80),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('SUCCEEDED', 'FAILED')),
    previous_chunks INTEGER,
    resulting_chunks INTEGER,
    error_code VARCHAR(255),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_filing_rebuild_runs_filing ON filing_rebuild_runs(filing_id, recorded_at DESC);
