-- Snapshots of the retrieval evaluation set run through filing retrieval: hit@k and MRR over the whole set,
-- with the per-question rank and the misses kept in results so a retrieval change can be compared against any
-- earlier snapshot. Appended, never updated. Deterministic given the store; never model-generated.
-- (window is a reserved word in PostgreSQL, hence window_size.)
CREATE TABLE retrieval_evaluations (
    id                  BIGSERIAL PRIMARY KEY,
    evaluated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    set_version         VARCHAR(32) NOT NULL,
    question_count      INTEGER NOT NULL,
    hit_at_1            NUMERIC(8, 6) NOT NULL,
    hit_at_3            NUMERIC(8, 6) NOT NULL,
    hit_at_5            NUMERIC(8, 6) NOT NULL,
    mrr                 NUMERIC(8, 6) NOT NULL,
    window_size         INTEGER NOT NULL,
    retrieval_strategy  VARCHAR(64) NOT NULL,
    properties          JSONB NOT NULL,
    results             JSONB NOT NULL,
    CONSTRAINT chk_retrieval_evaluation_questions CHECK (question_count >= 0),
    CONSTRAINT chk_retrieval_evaluation_window CHECK (window_size > 0)
);
CREATE INDEX idx_retrieval_evaluations_latest ON retrieval_evaluations (evaluated_at DESC, id DESC);
