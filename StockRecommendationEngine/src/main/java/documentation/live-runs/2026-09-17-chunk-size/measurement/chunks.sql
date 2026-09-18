-- Chunk export of the store (plan plans/2026-09-17-chunk-size.md, Milestone 3, F7): every chunk's id, filing, section, length, token count,
-- and its first 80 characters with whitespace runs collapsed to one space, as a JSON array on one line, ordered by id.
-- Run: docker exec -i trading-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -f - < chunks.sql > chunks-S0.json
select json_agg(json_build_object('id', id, 'filingId', filing_id, 'chunkIndex', chunk_index, 'sectionKey', section_key, 'sectionTitle', section_title,
    'sectionChunkIndex', section_chunk_index, 'chars', length(content), 'tokens', token_count,
    'head', regexp_replace(left(content, 80), '\s+', ' ', 'g')) order by id)
from sec_filing_chunks;
