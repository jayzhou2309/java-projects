-- Content hashes of the store (plan plans/2026-09-17-chunk-size-pool.md, Milestone 1, G8): per chunk its id, filing, chunk index, section key, length, and
-- md5(content), as a JSON array on one line ordered by filing and chunk index, so two store points can be compared in whole text where chunk ids differ. Read-only.
-- Run: docker exec -i trading-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -f - < chunk-hashes.sql > chunk-hashes-<point>.json
select json_agg(json_build_object('id', id, 'filingId', filing_id, 'chunkIndex', chunk_index, 'sectionKey', section_key, 'chars', length(content),
    'contentMd5', md5(content)) order by filing_id, chunk_index)
from sec_filing_chunks;
