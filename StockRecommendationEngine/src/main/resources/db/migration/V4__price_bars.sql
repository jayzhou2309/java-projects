-- Daily regular-hours bars retrieved from the broker for deterministic quant analysis.
-- One row = one contract-day. observed_at is retrieval time, not a market timestamp.
CREATE TABLE price_bars (
    conid        BIGINT NOT NULL,
    bar_date     DATE NOT NULL,
    symbol       VARCHAR(16) NOT NULL,
    currency     VARCHAR(8),
    open         NUMERIC(18, 6) NOT NULL,
    high         NUMERIC(18, 6) NOT NULL,
    low          NUMERIC(18, 6) NOT NULL,
    close        NUMERIC(18, 6) NOT NULL,
    volume       NUMERIC(24, 4),
    source       VARCHAR(32) NOT NULL,
    observed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conid, bar_date),
    CONSTRAINT chk_price_bars_range CHECK (high >= low AND open > 0 AND high > 0 AND low > 0 AND close > 0),
    CONSTRAINT chk_price_bars_volume CHECK (volume IS NULL OR volume >= 0)
);
CREATE INDEX idx_price_bars_conid_date ON price_bars (conid, bar_date DESC);
