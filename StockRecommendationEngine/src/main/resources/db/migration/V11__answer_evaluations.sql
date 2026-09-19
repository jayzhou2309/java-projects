-- Snapshots of the answer evaluation: evaluation-set questions run through the recommendation loop (runs stored with
-- purpose EVALUATION), one row per pass. results holds the per-question measures with each run id and the ids of the
-- selected questions that were not attempted; aggregates the counts and shares over them; properties the settings in
-- force. A pass that stopped early is kept and marked partial with the reason. Appended, never updated.
CREATE TABLE answer_evaluations (
    id              BIGSERIAL PRIMARY KEY,
    evaluated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    set_version     VARCHAR(32) NOT NULL,
    question_count  INTEGER NOT NULL,
    attempted       INTEGER NOT NULL,
    partial         BOOLEAN NOT NULL DEFAULT FALSE,
    partial_reason  VARCHAR(255),
    aggregates      JSONB NOT NULL,
    properties      JSONB NOT NULL,
    results         JSONB NOT NULL,
    CONSTRAINT chk_answer_evaluation_counts CHECK (question_count >= 0 AND attempted >= 0 AND attempted <= question_count),
    CONSTRAINT chk_answer_evaluation_partial CHECK (partial = (partial_reason IS NOT NULL))
);
CREATE INDEX idx_answer_evaluations_latest ON answer_evaluations (evaluated_at DESC, id DESC);
