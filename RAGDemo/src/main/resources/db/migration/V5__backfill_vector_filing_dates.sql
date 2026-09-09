-- Preserve embeddings; derive missing dates only from matching authoritative filing rows.
-- On a fresh install Spring AI may create vector_store after Flyway; there are no old vectors then.
DO $$
BEGIN
    IF to_regclass('public.vector_store') IS NOT NULL THEN
        UPDATE public.vector_store v
        SET metadata = (v.metadata::jsonb || jsonb_build_object('filedDate', to_char(f.filed_date, 'YYYY-MM-DD')))::json
        FROM filings f JOIN stocks s ON s.id = f.stock_id
        WHERE v.metadata->>'accessionNo' = f.accession_no
          AND v.metadata->>'symbol' = s.symbol
          AND v.metadata->>'sourceUrl' = f.source_url
          AND v.metadata->>'filedDate' IS NULL;
    END IF;
END $$;
