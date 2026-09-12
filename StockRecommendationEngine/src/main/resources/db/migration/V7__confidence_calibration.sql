-- Snapshots of the confidence calibration computed from stored directional outcomes at the reference horizon.
-- The recommendation loop applies the newest READY snapshot and records its id in the response, so every
-- calibrated figure can be reproduced from the snapshot that produced it. Deterministic; never model-generated.
CREATE TABLE confidence_calibrations (
    id                          BIGSERIAL PRIMARY KEY,
    computed_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    horizon_days                INTEGER NOT NULL,
    status                      VARCHAR(32) NOT NULL,
    samples                     INTEGER NOT NULL,
    min_samples                 INTEGER NOT NULL,
    prior_weight                INTEGER NOT NULL,
    base_rate                   NUMERIC(8, 6),
    expected_calibration_error  NUMERIC(8, 6),
    brier_score                 NUMERIC(8, 6),
    bins                        JSONB NOT NULL,
    assessments                 JSONB NOT NULL,
    prompt_versions             JSONB NOT NULL,
    CONSTRAINT chk_calibration_status CHECK (status IN ('READY', 'INSUFFICIENT_SAMPLE')),
    CONSTRAINT chk_calibration_horizon CHECK (horizon_days > 0),
    CONSTRAINT chk_calibration_samples CHECK (samples >= 0)
);
CREATE INDEX idx_confidence_calibrations_latest ON confidence_calibrations (horizon_days, computed_at DESC, id DESC);
