-- Keyword search foundation for hybrid retrieval (Follow_Ups RAG-2). A stored generated tsvector of each
-- chunk's content, indexed with GIN, lets FilingRetrievalRepository.findKeywordChunks find exact terms and
-- figures ("64,377", "OpenAI") that an embedding blurs. to_tsvector('english', text) is immutable, so the
-- column is generated and kept in step with content by PostgreSQL; existing rows are populated on ALTER.
ALTER TABLE sec_filing_chunks
    ADD COLUMN content_tsv tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED;

CREATE INDEX idx_sec_filing_chunks_content_tsv
    ON sec_filing_chunks USING GIN (content_tsv);
