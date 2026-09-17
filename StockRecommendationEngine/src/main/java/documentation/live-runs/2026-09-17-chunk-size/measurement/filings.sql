-- Filings export of the store (plan plans/2026-09-17-chunk-size.md, Milestone 3; the keys block of RAG.md, Chunk size measurement): every sec_filings row's
-- id, ticker, type, accession number, filing date, ingestion and last-update times (UTC), processing version, and status, as a JSON array on one line, ordered by id.
-- Run: docker exec -i trading-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -f - < filings.sql > filings-S2.json
-- Read-only. The rows carry no store point of their own: created_at is the ingestion time and updated_at the time of the last rebuild of that filing, so at
-- S2 (after the rollback) updated_at is the rollback rebuild's time per filing (filing-rebuild-runs-after-rollback.json records the same rebuilds).
select json_agg(json_build_object('filingId', id, 'ticker', ticker, 'filingType', filing_type, 'accessionNo', accession_no, 'filingDate', filing_date,
    'createdAt', to_char(created_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
    'updatedAt', to_char(updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
    'processingVersion', processing_version, 'ingestionStatus', ingestion_status) order by id)
from sec_filings;
