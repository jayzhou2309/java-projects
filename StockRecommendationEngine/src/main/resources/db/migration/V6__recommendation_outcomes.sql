-- Realized outcomes per stored recommendation and horizon, computed from stored daily bars after the
-- recommendation's bars_as_of date. Deterministic; never model-generated. Rows are rewritten per evaluation.
CREATE TABLE recommendation_outcomes (
    run_id                VARCHAR(36) NOT NULL REFERENCES recommendations(run_id) ON DELETE CASCADE,
    horizon_days          INTEGER NOT NULL,
    evaluated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    as_of                 DATE NOT NULL,
    entry_price           NUMERIC(18, 6) NOT NULL,
    exit_date             DATE NOT NULL,
    exit_price            NUMERIC(18, 6) NOT NULL,
    return_pct            NUMERIC(12, 6) NOT NULL,
    benchmark_conid       BIGINT,
    benchmark_return_pct  NUMERIC(12, 6),
    excess_return_pct     NUMERIC(12, 6),
    direction_correct     BOOLEAN,
    first_touch           VARCHAR(16) NOT NULL,
    touch_date            DATE,
    days_to_touch         INTEGER,
    max_favorable_pct     NUMERIC(12, 6) NOT NULL,
    max_adverse_pct       NUMERIC(12, 6) NOT NULL,
    bars_used             INTEGER NOT NULL,
    PRIMARY KEY (run_id, horizon_days),
    CONSTRAINT chk_outcome_horizon CHECK (horizon_days > 0),
    CONSTRAINT chk_outcome_touch CHECK (first_touch IN ('TAKE_PROFIT', 'STOP_LOSS', 'BOTH_SAME_DAY', 'NONE', 'NO_LEVELS'))
);
CREATE INDEX idx_recommendation_outcomes_horizon ON recommendation_outcomes (horizon_days, evaluated_at DESC);
