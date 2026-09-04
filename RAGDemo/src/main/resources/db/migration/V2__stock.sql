CREATE TABLE stocks (
                        id              BIGSERIAL PRIMARY KEY,
                        symbol          VARCHAR(10) NOT NULL UNIQUE,
                        company_name    VARCHAR(255) NOT NULL,
                        exchange        VARCHAR(20),
                        sector          VARCHAR(100),
                        industry        VARCHAR(100),
                        cik             VARCHAR(10) NOT NULL UNIQUE,
                        is_active       BOOLEAN NOT NULL DEFAULT TRUE,
                        created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_stocks_symbol
    ON stocks (symbol);

CREATE INDEX idx_stocks_cik
    ON stocks (cik);