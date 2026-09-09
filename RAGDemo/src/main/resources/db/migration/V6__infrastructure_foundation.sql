-- Install extension now; time-series tables and hypertables belong to Phase 2.
CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE stock_identifiers (
    id UUID PRIMARY KEY,
    stock_id BIGINT NOT NULL REFERENCES stocks(id),
    identifier_type VARCHAR(30) NOT NULL CHECK (length(trim(identifier_type)) > 0),
    identifier_value VARCHAR(100) NOT NULL CHECK (length(trim(identifier_value)) > 0),
    source VARCHAR(100) NOT NULL CHECK (length(trim(source)) > 0),
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_identifier_window CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT uq_stock_identifier UNIQUE (stock_id, identifier_type, identifier_value, source, valid_from)
);
CREATE INDEX idx_stock_identifiers_lookup ON stock_identifiers(identifier_type, identifier_value, valid_from);

CREATE TABLE ingestion_runs (
    id UUID PRIMARY KEY,
    provider VARCHAR(100) NOT NULL CHECK (length(trim(provider)) > 0),
    dataset VARCHAR(100) NOT NULL CHECK (length(trim(dataset)) > 0),
    stock_id BIGINT REFERENCES stocks(id),
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    records_processed BIGINT NOT NULL DEFAULT 0 CHECK (records_processed >= 0),
    error_summary VARCHAR(1000),
    CONSTRAINT ck_ingestion_completion CHECK (
        (status = 'RUNNING' AND completed_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED') AND completed_at >= started_at AND completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_ingestion_error CHECK (status = 'FAILED' OR error_summary IS NULL)
);
CREATE INDEX idx_ingestion_runs_provider_started ON ingestion_runs(provider, started_at DESC);
