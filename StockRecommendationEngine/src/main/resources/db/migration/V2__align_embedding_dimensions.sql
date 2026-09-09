-- Keep V1 unchanged so databases that already applied it retain valid checksums.
-- Existing 3072-dimensional embeddings must be regenerated with the configured
-- model before this migration can proceed; do not truncate or discard them.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM sec_filing_chunks
        WHERE embedding IS NOT NULL AND vector_dims(embedding) <> 1536
    ) THEN
        RAISE EXCEPTION 'Cannot migrate embeddings to vector(1536): incompatible existing vectors require an explicit backup and re-embedding migration.';
    END IF;
END
$$;

ALTER TABLE sec_filing_chunks
    ALTER COLUMN embedding TYPE vector(1536)
    USING embedding::vector(1536);
