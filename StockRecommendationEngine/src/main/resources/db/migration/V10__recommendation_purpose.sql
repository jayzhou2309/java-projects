-- Marks why a run was made. EVALUATION runs measure the recommendation loop and are excluded from outcome scoring,
-- the per-ticker history and track record, and calibration. Existing rows are user runs.
ALTER TABLE recommendations
    ADD COLUMN purpose VARCHAR(16) NOT NULL DEFAULT 'USER',
    ADD CONSTRAINT chk_recommendations_purpose CHECK (purpose IN ('USER', 'EVALUATION'));
