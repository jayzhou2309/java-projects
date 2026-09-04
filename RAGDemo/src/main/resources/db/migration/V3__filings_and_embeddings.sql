CREATE TYPE filing_type AS ENUM (
    '10-K',
    '10-Q',
    '8-K',
    'OTHER'
);

CREATE TYPE filing_status AS ENUM (
    'PENDING',
    'PARSED',
    'EMBEDDED',
    'FAILED'
);

CREATE TABLE filings (
                         id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),

                         stock_id            BIGINT NOT NULL,

                         filing_type         filing_type NOT NULL,
                         accession_no        VARCHAR(25) NOT NULL,
                         filed_date          DATE NOT NULL,

                         source_url          TEXT NOT NULL,

                         status              filing_status NOT NULL DEFAULT 'PENDING',

                         ingested_at         TIMESTAMPTZ,
                         embedded_at         TIMESTAMPTZ,

                         created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

                         CONSTRAINT fk_filings_stock
                             FOREIGN KEY (stock_id)
                                 REFERENCES stocks(id)
                                 ON DELETE CASCADE,

                         CONSTRAINT uq_filings_stock_accession
                             UNIQUE (stock_id, accession_no)
);

CREATE INDEX idx_filings_stock_date
    ON filings (stock_id, filed_date DESC);

CREATE INDEX idx_filings_status
    ON filings (status);