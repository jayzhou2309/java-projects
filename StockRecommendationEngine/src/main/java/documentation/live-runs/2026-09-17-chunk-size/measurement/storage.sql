-- Storage snapshot of the chunk store (plan plans/2026-09-17-chunk-size.md, Milestone 3, F9), one JSON object on one line.
-- Run at each point with: docker exec -i trading-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -v point=S0 -f - < storage.sql > storage-S0.json
-- Sizes are pg_* functions as they stand at run time (no VACUUM is run first; dead tuples from replaced chunks count until autovacuum);
-- pg_stat_user_tables live and dead tuple estimates are recorded beside them. Every index of sec_filing_chunks is listed with its size
-- (there is no index on the embedding column: retrieval is an exact scan; the content_tsv GIN index is idx_sec_filing_chunks_content_tsv).
select json_build_object(
    'point', :'point',
    'recordedAt', to_char(now() at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
    'chunks', (select count(*) from sec_filing_chunks),
    'contentChars', (select coalesce(sum(length(content)), 0) from sec_filing_chunks),
    'meanChunkChars', (select round(avg(length(content)), 1) from sec_filing_chunks),
    'maxChunkChars', (select max(length(content)) from sec_filing_chunks),
    'minChunkChars', (select min(length(content)) from sec_filing_chunks),
    'tokenCountSum', (select coalesce(sum(token_count), 0) from sec_filing_chunks),
    'maxTokenCount', (select max(token_count) from sec_filing_chunks),
    'filings', (select count(*) from sec_filings),
    'embeddedFilings', (select count(*) from sec_filings where ingestion_status = 'EMBEDDED'),
    'storeVersions', (select coalesce(json_agg(v order by v), '[]'::json) from (select distinct processing_version v from sec_filings where ingestion_status = 'EMBEDDED') s),
    'totalRelationBytes', pg_total_relation_size('sec_filing_chunks'),
    'heapBytes', pg_relation_size('sec_filing_chunks'),
    'toastBytes', pg_table_size('sec_filing_chunks') - pg_relation_size('sec_filing_chunks'),
    'indexesBytes', pg_indexes_size('sec_filing_chunks'),
    'indexes', (select json_agg(json_build_object('name', indexrelname, 'bytes', pg_relation_size(indexrelid)) order by indexrelname)
                from pg_stat_user_indexes where relname = 'sec_filing_chunks'),
    'liveTuples', (select n_live_tup from pg_stat_user_tables where relname = 'sec_filing_chunks'),
    'deadTuples', (select n_dead_tup from pg_stat_user_tables where relname = 'sec_filing_chunks'),
    'lastAutovacuum', (select last_autovacuum from pg_stat_user_tables where relname = 'sec_filing_chunks'),
    'filingsDetail', (select json_agg(json_build_object('filingId', f.id, 'ticker', f.ticker, 'filingType', f.filing_type,
                          'processingVersion', f.processing_version, 'ingestionStatus', f.ingestion_status,
                          'chunks', coalesce(c.n, 0), 'contentChars', coalesce(c.chars, 0), 'maxChunkChars', c.longest) order by f.id)
                      from sec_filings f
                      left join (select filing_id, count(*) n, sum(length(content)) chars, max(length(content)) longest from sec_filing_chunks group by filing_id) c
                        on c.filing_id = f.id)
);
