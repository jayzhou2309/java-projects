-- System-of-record audit for every recommendation run, including limit-stopped and failed runs.
-- Version tags let outcomes be attributed to the prompt, model, quant parameters, and filing processing in use.
CREATE TABLE recommendations (
    run_id              VARCHAR(36) PRIMARY KEY,
    ticker              VARCHAR(16) NOT NULL,
    conid               BIGINT,
    requested_at        TIMESTAMPTZ NOT NULL,
    completed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    question            TEXT NOT NULL,
    status              VARCHAR(32) NOT NULL,
    assessment          VARCHAR(32) NOT NULL,
    take_profit         NUMERIC(18, 6),
    stop_loss           NUMERIC(18, 6),
    confidence          NUMERIC(5, 4),
    last_close          NUMERIC(18, 6),
    bars_as_of          DATE,
    quote_availability  VARCHAR(32),
    cited_chunk_ids     BIGINT[] NOT NULL DEFAULT '{}',
    limitations         TEXT[] NOT NULL DEFAULT '{}',
    model_calls         INTEGER NOT NULL DEFAULT 0,
    observed_tokens     INTEGER NOT NULL DEFAULT 0,
    prompt_version      VARCHAR(80) NOT NULL,
    model               VARCHAR(120) NOT NULL,
    quant_version       VARCHAR(120),
    processing_version  VARCHAR(80) NOT NULL,
    response            JSONB NOT NULL,
    CONSTRAINT chk_recommendations_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);
CREATE INDEX idx_recommendations_ticker_requested ON recommendations (ticker, requested_at DESC);
CREATE INDEX idx_recommendations_requested ON recommendations (requested_at DESC);
