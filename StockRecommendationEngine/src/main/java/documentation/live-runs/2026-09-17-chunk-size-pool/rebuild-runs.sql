-- Rebuild-run export (plan plans/2026-09-17-chunk-size-pool.md, Milestone 1): every filing_rebuild_runs row as a JSON array, ordered by recorded_at. Read-only.
-- Run: docker exec -i trading-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -f - < rebuild-runs.sql > filing-rebuild-runs-<point>.json
select json_agg(r order by recorded_at, run_id) from filing_rebuild_runs r;
