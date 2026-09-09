-- ============================================================
-- SEC RAG STORAGE
-- PostgreSQL + pgvector
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;


-- ============================================================
-- SEC FILINGS
-- One row = one SEC filing/document
-- ============================================================

CREATE TABLE sec_filings (
                             id                  BIGSERIAL PRIMARY KEY,

                             ticker              VARCHAR(16) NOT NULL,
                             cik                 VARCHAR(20) NOT NULL,

    -- SEC accession number is the natural idempotency key
                             accession_no        VARCHAR(32) NOT NULL UNIQUE,

                             filing_type         VARCHAR(10) NOT NULL,
                             filing_date         DATE NOT NULL,
                             report_date         DATE,

    -- SEC primary HTML document, e.g. aapl-20250927.htm
                             primary_document    VARCHAR(255),

    -- Direct URL to the filing HTML
                             source_url          TEXT NOT NULL,

    /*
     * Normalized text extracted from SEC HTML.
     *
     * Do NOT store the HTML DOM here unless we later have
     * a specific audit/debugging reason to do so.
     */
                             raw_content         TEXT,

    /*
     * SHA-256 (or similar) hash of normalized filing content.
     * Useful for detecting changes / avoiding unnecessary
     * re-chunking and re-embedding.
     */
                             content_hash        VARCHAR(64),

    /*
     * Ingestion lifecycle:
     *
     * PENDING
     * FETCHED
     * PARSED
     * CHUNKED
     * EMBEDDED
     * FAILED
     */
                             ingestion_status    VARCHAR(20) NOT NULL DEFAULT 'PENDING',

                             ingestion_error     TEXT,

                             created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                             updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                             CONSTRAINT chk_sec_filings_ingestion_status
                                 CHECK (
                                     ingestion_status IN (
                                                          'PENDING',
                                                          'FETCHED',
                                                          'PARSED',
                                                          'CHUNKED',
                                                          'EMBEDDED',
                                                          'FAILED'
                                         )
                                     )
);


-- ============================================================
-- SEC FILING INDEXES
-- ============================================================

CREATE INDEX idx_sec_filings_ticker
    ON sec_filings (ticker);

CREATE INDEX idx_sec_filings_cik
    ON sec_filings (cik);

CREATE INDEX idx_sec_filings_type
    ON sec_filings (filing_type);

CREATE INDEX idx_sec_filings_filing_date
    ON sec_filings (filing_date DESC);

CREATE INDEX idx_sec_filings_ticker_type_date
    ON sec_filings (
                    ticker,
                    filing_type,
                    filing_date DESC
        );

CREATE INDEX idx_sec_filings_ingestion_status
    ON sec_filings (ingestion_status);


-- ============================================================
-- SEC FILING CHUNKS
-- One row = one retrievable vector chunk
-- ============================================================

CREATE TABLE sec_filing_chunks (
                                   id                  BIGSERIAL PRIMARY KEY,

                                   filing_id           BIGINT NOT NULL
                                       REFERENCES sec_filings(id)
                                           ON DELETE CASCADE,

    /*
     * Position within the entire filing.
     */
                                   chunk_index         INTEGER NOT NULL,

    /*
     * Machine-friendly SEC section identifier.
     *
     * Examples:
     * ITEM_1
     * ITEM_1A
     * ITEM_7
     * ITEM_7A
     * ITEM_8
     */
                                   section_key         VARCHAR(64),

    /*
     * Human-readable section.
     *
     * Examples:
     * Business
     * Risk Factors
     * Management's Discussion and Analysis
     */
                                   section_title       VARCHAR(255),

    /*
     * Position inside the section.
     *
     * Useful when a single SEC section produces many chunks.
     */
                                   section_chunk_index INTEGER,

                                   content             TEXT NOT NULL,

    /*
     * Character positions inside normalized filing text.
     * Makes chunks auditable / reproducible.
     */
                                   start_char          INTEGER,
                                   end_char            INTEGER,

    /*
     * Number of tokens sent to the embedding model.
     */
                                   token_count         INTEGER,

    /*
     * Full text-embedding-3-large dimensions.
     *
     * If we later choose dimensionality reduction such as
     * 1536 dimensions, this schema must be migrated accordingly.
     */
                                   embedding           VECTOR(3072),

                                   created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                                   CONSTRAINT uq_sec_filing_chunk
                                       UNIQUE (filing_id, chunk_index),

                                   CONSTRAINT chk_sec_filing_chunk_index
                                       CHECK (chunk_index >= 0),

                                   CONSTRAINT chk_sec_filing_token_count
                                       CHECK (token_count IS NULL OR token_count >= 0),

                                   CONSTRAINT chk_sec_filing_char_range
                                       CHECK (
                                           start_char IS NULL
                                               OR end_char IS NULL
                                               OR end_char >= start_char
                                           )
);


-- ============================================================
-- CHUNK INDEXES
-- ============================================================

CREATE INDEX idx_sec_filing_chunks_filing
    ON sec_filing_chunks (filing_id);

CREATE INDEX idx_sec_filing_chunks_section_key
    ON sec_filing_chunks (section_key);

CREATE INDEX idx_sec_filing_chunks_section_title
    ON sec_filing_chunks (section_title);

CREATE INDEX idx_sec_filing_chunks_filing_section
    ON sec_filing_chunks (
                          filing_id,
                          section_key
        );