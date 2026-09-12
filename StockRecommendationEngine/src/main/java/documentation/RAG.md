* SECClient
    * Methods
        * resolveCik(String ticker)
            * Get SEC CIK for ticker.
            * Example: AAPL → 0000320193.
        * getRecentFilings(String ticker, List filingTypes)
            * Retrieve recent SEC filing metadata for ticker.
            * Supported types initially: 10-Q, 10-K, 8-K.
            * Return List.
            * Metadata includes:
                * accessionNo
                * filingType
                * filingDate
                * reportDate
                * primaryDocument
                * sourceUrl
        * fetchFilingHTML(String sourceUrl)
            * Retrieve raw filing HTML directly from SEC EDGAR.
            * Return HTML as String.
* FilingHtmlParser
    * Methods
        * parse(String html)
            * Parse raw SEC filing HTML.
            * Remove non-content HTML elements.
            * Normalize filing text.
            * Identify SEC Item sections.
            * Preserve section key and section title.
            * Return List.
* FilingChunker
    * Configuration
        * MAX_CHARS = 4000.
        * OVERLAP_CHARS = 500.
    * Methods
        * chunk(List sections)
            * Split parsed SEC sections into overlapping chunks.
            * Preserve section metadata.
            * Prevent chunks from crossing SEC section boundaries.
            * Prefer paragraph, sentence, and whitespace boundaries instead of hard character cuts.
            * Estimate token count for each chunk.
            * Return List.
* FilingEmbeddingService
    * Methods
        * embed(String content)
            * Generate an embedding vector for one filing chunk.
        * embedChunks(List chunks)
            * Generate embeddings for all filing chunks.
            * Return List.
    * Development model
        * text-embedding-3-small.
        * Vector dimensions must match the pgvector database column.
* FilingIngestionService
    * Purpose
        * Orchestrate the complete SEC filing ingestion pipeline.
    * Methods
        * ingest(String ticker, List filingTypes)
            * Normalize ticker.
            * Resolve ticker CIK.
            * Retrieve recent filing metadata.
            * Skip filings already ingested using accessionNo.
            * Ingest each new filing.
        * ingestFiling(String ticker, String cik, SECFilingMetadata metadata)
            * Create SECFiling database entity.
            * Fetch filing HTML.
            * Parse HTML into FilingSection objects.
            * Chunk sections into FilingChunkData objects.
            * Convert chunk DTOs into FilingChunk entities.
            * Generate embeddings.
            * Persist filing and chunks.
            * Update ingestion status.
        * toEntities(SECFiling filing, List chunks)
            * Convert FilingChunkData DTOs into FilingChunk JPA entities.
            * Associate each chunk with its parent SECFiling.
* SECFilingRepository
    * Purpose
        * Persistence operations for SEC filings.
    * Methods
        * existsByAccessionNo(String accessionNo)
            * Check whether filing has already been ingested.
        * findByAccessionNo(String accessionNo)
            * Retrieve filing using SEC accession number.
* FilingChunkRepository
    * Purpose
        * Persistence operations for SEC filing chunks.
* SECFiling
    * JPA entity representing one SEC filing.
    * Maps to sec_filings.
    * Has one-to-many relationship with FilingChunk.
* FilingChunk
    * JPA entity representing one retrievable SEC filing chunk.
    * Maps to sec_filing_chunks.
    * Has many-to-one relationship with SECFiling.
    * Stores:
        * chunkIndex
        * sectionKey
        * sectionTitle
        * sectionChunkIndex
        * content
        * startChar
        * endChar
        * tokenCount
        * embedding


* FilingRetrievalController
    * Purpose
        * Expose filing evidence retrieval through POST /api/rag/retrieve.
        * Validate the request before embedding or database calls.
    * Methods
        * retrieve(RetrievalRequest request)
            * Pass the validated request to FilingRetrievalService.
            * Return RetrievalResponse as JSON.
            * Return HTTP 400 for missing/blank inputs, invalid limits, or reversed date ranges.
* FilingRetrievalService
    * Purpose
        * Orchestrate filtered vector retrieval and optional reranking.
        * Return SEC evidence without generating a recommendation or answer.
    * Methods
        * retrieve(RetrievalRequest request)
            * Normalize ticker, filing types, and section keys using Locale.ROOT.
            * Resolve topK and latest-filings policy.
            * Embed the query using FilingEmbeddingService.embed(query).
            * Retrieve the closest eligible chunks from FilingRetrievalRepository.
            * Keep vector order when reranking is disabled.
            * Invoke FilingReranker when enabled and a provider adapter is configured.
            * Return selected passages with unchanged citation metadata and cosine scores.
            * Log query-embedding, vector-search, selection, completion, and failure steps.
        * normalizeValues(List<String> values)
            * Trim, uppercase, and deduplicate optional filter values.
        * validateRerankedEvidence(List candidates, List selectedEvidence, int requestedResultCount)
            * Reject duplicate, altered, or invented evidence from a reranker.
            * Enforce the requested result limit.
* FilingRetrievalRepository
    * Purpose
        * Run parameterized PostgreSQL/pgvector retrieval queries.
        * Keep retrieval SQL separate from JPA persistence repositories.
    * Methods
        * findSimilarChunks(float[] queryEmbedding, FilingRetrievalFilter retrievalFilter, int candidateCount)
            * Filter filings by ticker and EMBEDDED ingestion status.
            * Apply optional filing type and inclusive filing-date filters.
            * Optionally select the latest eligible filing per filing type.
            * Apply optional section filters and exclude null/zero embeddings.
            * Materialize eligible chunks before distance ranking for exact vector search.
            * Sort by cosine distance ascending, then chunk ID for deterministic ties.
            * Return at most candidateCount RetrievedFilingChunk records.
        * serializeEmbedding(float[] queryEmbedding)
            * Require 1536 finite dimensions and a nonzero vector.
            * Serialize the vector as a bound SQL parameter.
        * findKeywordChunks(String query, float[] queryEmbedding, FilingRetrievalFilter retrievalFilter, int candidateCount)
            * Full-text search over the same eligibility CTE as findSimilarChunks (ticker, EMBEDDED, type, date, latest-per-type, section, usable embedding), matching `content_tsv @@ to_tsquery('english', :terms)`.
            * Sort by ts_rank_cd descending, then cosine similarity descending, then chunk ID; every row still carries the cosine similarity to the query embedding as similarityScore.
            * Empty terms (stopword-only or punctuation-only query) return an empty list without a query.
        * keywordTerms(String query) (package-private)
            * Distinct case-folded tokens of length 2+ (letters and digits; commas and periods kept between digits so `64,377` and `40.4` stay whole), PostgreSQL english stopwords removed, each quoted for tsquery and joined with ` | `; never concatenated into SQL.
            * PostgreSQL parses the quoted figure `'64,377'` into the phrase `'64' <-> '377'`, the same split the stored vector holds, so the figure matches only where it appears as one number.
    * Keyword index (migration V9, `V9__chunk_keyword_index.sql`)
        * `sec_filing_chunks.content_tsv tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED`, populated for every existing row on migration and kept in step with content by PostgreSQL.
        * GIN index `idx_sec_filing_chunks_content_tsv` on that column; the keyword CTE is NOT MATERIALIZED so the planner can use it.
    * Similarity score
        * similarityScore = 1 - cosine distance.
        * Higher scores indicate greater vector similarity, not recommendation confidence.
        * No calibrated relevance threshold is applied in this baseline.
* FilingRetrievalProperties
    * Configuration prefix
        * rag.retrieval
    * Defaults
        * default-top-k: 5.
        * candidate-count: 40.
        * latest-filings-only: true.
        * reranking-enabled: false.
    * Validation
        * default-top-k must be between 1 and 20.
        * candidate-count must be between 20 and 200.
* FilingReranker
    * Purpose
        * Define the extension point for a future model-based reranker.
        * No hosted or local reranker implementation is installed in this baseline.
    * Methods
        * rerank(String query, List<RetrievedFilingChunk> candidates, int topK)
            * Return a ranked subset of the supplied candidate records.
            * Preserve original text, citation metadata, and similarityScore.
    * Configuration
        * Disabled by default pending provider/model selection and benchmarking.
        * Enabling reranking without a FilingReranker bean fails application startup clearly.
        * A reranker failure propagates; it is not silently reported as successful reranking.
* RetrievalRequest
    * Required fields
        * ticker: nonblank, maximum 16 characters.
        * query: nonblank, maximum 4000 characters.
    * Optional fields
        * filingTypes: nonempty list when supplied; for example ["10-K", "10-Q"].
        * filingDateFrom: inclusive lower filing-date bound.
        * filingDateTo: inclusive upper filing-date bound.
        * sectionKeys: nonempty list when supplied; for example ["ITEM_1A"].
        * topK: final result count, between 1 and 20; default 5.
        * latestFilingsOnly: explicit override for latest-per-type selection.
    * Default filing scope
        * Without dates, use the latest stored EMBEDDED filing per type by default.
        * With either date bound, search all eligible filings in that range by default.
        * An explicit latestFilingsOnly value overrides either default.
        * When enabled with dates, latest means latest within the supplied date range.
        * Omitted filingTypes means all stored filing types, with the same latest-per-type rule.
        * Latest is ordered by filingDate, then accessionNo and database ID for ties.
        * Latest selection occurs before the section filter; it does not fall back to older filings if a section is absent.
    * Date semantics
        * Bounds apply to filingDate, not reportDate.
        * This date-only baseline does not guarantee intraday historical availability.
* FilingRetrievalFilter
    * Purpose
        * Carry normalized company, filing type, date, section, and latest-filings constraints into SQL.
* RetrievedFilingChunk
    * Purpose
        * Represent one retrieved SEC evidence passage and its provenance.
    * Fields
        * chunkId
        * filingId
        * ticker
        * cik
        * accessionNo
        * filingType
        * filingDate
        * reportDate
        * sectionKey
        * sectionTitle
        * chunkIndex
        * content
        * sourceUrl
        * similarityScore
* RetrievalResponse
    * Fields
        * ticker: normalized company ticker.
        * query: trimmed query.
        * retrievalStrategy: FILTERED_VECTOR or FILTERED_VECTOR_RERANKED.
        * latestFilingsOnly: resolved filing-selection policy.
        * topK: requested/default final result limit.
        * candidatesRetrieved: candidate count before final selection.
        * results: evidence passages with citations.
    * Empty results
        * Return HTTP 200 with an empty results list when no eligible chunks match the scope.
        * Retrieval searches stored filings and does not fetch missing companies from SEC.
        * Empty results do not trigger an automatic ingestion or answer-generation call.
    * Baseline limitations
        * Results are nearest passages, not a guarantee the query is answerable.
        * Overlapping chunks may still appear together; contextual deduplication is deferred.
        * Hybrid keyword retrieval, model reranking, and answer generation remain separate next steps.

* Ingestion Pipeline
  Ticker
  ↓
  SECClient
  ↓
  Raw SEC HTML
  ↓
  FilingHtmlParser
  ↓
  List
  ↓
  FilingChunker
  ↓
  List
  ↓
  FilingIngestionService
  ↓
  FilingChunk entities
  ↓
  FilingEmbeddingService
  ↓
  Embedding vectors
  ↓
  PostgreSQL + pgvector


* Retrieval Pipeline
  User Query + Ticker + Optional Filters
  ↓
  FilingRetrievalController
  ↓
  FilingRetrievalService
  ↓
  FilingEmbeddingService.embed(query)
  ↓
  FilingRetrievalRepository
  ↓
  Filter Eligible Filings and Chunks
  ↓
  Exact pgvector Cosine Similarity Search
  ↓
  Top Candidate Filing Chunks
  ↓
  Optional FilingReranker (disabled until a provider is selected)
  ↓
  Top-K SEC Evidence + Citations
  ↓
  RetrievalResponse
  ↓
  Optional Recommendation Pipeline (see Agent_Harness.md)

* Retrieval Request Example
    * Latest stored AAPL 10-K, top 5 passages.

```bash
curl -X POST http://localhost:8080/api/rag/retrieve \
  -H 'Content-Type: application/json' \
  -d '{
    "ticker": "AAPL",
    "query": "What are the main risks to operating margins?",
    "filingTypes": ["10-K"],
    "topK": 5
  }'
```

* Historical Retrieval Example
    * Search all stored AAPL 10-K/10-Q filings published during the specified date range.

```json
{
  "ticker": "AAPL",
  "query": "What risks affected profitability?",
  "filingTypes": ["10-K", "10-Q"],
  "filingDateFrom": "2024-01-01",
  "filingDateTo": "2025-12-31",
  "latestFilingsOnly": false,
  "topK": 5
}
```

* Retrieval Design Decisions
    * See [Retrieval Strategy Research](Retrieval_Strategy_Research.md) for source research and alternatives.
    * Current baseline: filtered exact vector search.
    * Reranker provider/model and default filing policy were raised for user input.
    * In the absence of a different choice, use the recommended configurable defaults above.

* Retrieval Verification
    * 22 new regression checks cover request validation, service selection, optional reranker contracts, and database retrieval.
    * PostgreSQL tests use known vectors to verify cosine ranking, inclusive date filters, filing types, sections, and latest-per-type selection.
    * Null/zero stored embeddings and failed filings are excluded.
    * The full 47-test suite passed against a disposable PostgreSQL/pgvector database.
    * Correctness tests do not establish real-world retrieval relevance or reranker quality.

* Retrieval Evaluation
    * Purpose
        * A fixed, versioned question set with known-good passages, measured the same way every time, so a retrieval or prompt change (reranking RAG-1, hybrid retrieval RAG-2, lean-profile passage tuning AGENT-9, prompt regression AGENT-8) is judged against a baseline instead of a single live run. Closes Follow_Ups AGENT-3.
    * The set
        * `src/main/resources/evaluation/retrieval-set-v1.json`: 30 analyst-style questions (10 each for AAPL, MSFT, NVDA) written against the latest stored 10-K, 10-Q, and 8-K per ticker as of 2026-09-12.
        * Format: `version`, `createdOn`, and `questions`; each question has `id`, `ticker`, `kind` (FIGURE for an exact number stated in the filing, NARRATIVE for a risk, segment change, or policy), `question`, `expected` (one or more passages, any one satisfies), and optional `notes`.
        * An expected passage is `accessionNo`, `sectionKey`, and `phrase`: a verbatim 12 to 200 character excerpt of a chunk stored for that filing and section, using the same characters as the chunk (curly quotes, non-breaking spaces). Chunk IDs change on rebuild, so they are never referenced.
        * `RetrievalEvaluationSetLoader` (`rag.evaluation`) reads the resource and rejects duplicate ids, empty expectation lists, blank or out-of-range phrases, malformed tickers or accession numbers, and phrases shared by two questions, naming the question id.
        * `RetrievalEvaluationSetTests` (database-backed, read-only) proves every expectation is a substring of a stored chunk, case-insensitive with whitespace collapsed. Section keys follow the parser: NVIDIA's Item 8 is a one-line cross-reference, so its financial statement notes sit under ITEM_15; the AAPL 8-K Item 2.02 is stored as ITEM_2 and the MSFT 8-K Item 7.01 as ITEM_7_01.
        * Nothing in the set reaches a model prompt; an evaluation run embeds each question once (the only external call) and never calls a chat model.
    * Adding a question
        * Find the chunk that answers it: `SELECT c.id, c.section_key, c.content FROM sec_filing_chunks c JOIN sec_filings f ON f.id = c.filing_id WHERE f.accession_no = '<accession>' AND c.content ILIKE '%<distinctive words>%'`, then copy a 12 to 200 character excerpt exactly as stored (the loader does not fix quotes or spaces).
        * Add the question with the next id for its ticker (`nvda-11`), the accession, the section key as stored, and the phrase; add a second expected passage when the same fact is stated in another section or chunk, so a correct retrieval of either counts.
        * Keep the set invariants: 24 to 30 questions, at least 8 per ticker, at least 6 FIGURE questions, no phrase used twice, sections ITEM_1A, ITEM_7, ITEM_8, ITEM_1, and ITEM_7_01 all covered.
        * Run `RetrievalEvaluationSetTests` (proves the phrase is stored) and then a live evaluation; when the set changes in a way that moves the metrics, bump `version`, record a new baseline below, and re-derive the floor. Stored snapshots carry `setVersion`, so old ones stay comparable among themselves.
    * Metrics (`RetrievalEvaluationService`)
        * Each question is retrieved once with its ticker, the question text as the query, `latestFilingsOnly` true, and `topK` = window; no section, filing type, or date filter, so the section filter never helps the evaluation.
        * Rank: the 1-based position of the first returned chunk whose accession and section equal an expected passage's and whose content contains the phrase (case-insensitive, whitespace collapsed); null when no chunk in the window matches.
        * hit@k: the fraction of questions with rank at most k; reported at k = 1, 3, 5.
        * MRR: the mean over all questions of 1/rank, counting a null rank as 0; a question found at rank 10 contributes 0.1, so MRR rewards moving a passage up even when hit@5 does not change.
        * Per-ticker hit@5: hit@5 over each ticker's questions, the first place to look when a change helps one filer and hurts another.
        * Window: `rag.evaluation.window` (default 10, 5 to 20); a passage beyond it is a miss, so the window bounds MRR's tail and the cost of a run (one embedding per question regardless of window).
        * A retrieval exception for one question is recorded as a miss with the error string; the other questions still count, so one embedding failure never voids a run.
    * Endpoints (integration token required, `Authorization: Bearer <INTEGRATION_ACCESS_TOKEN>`)
        * `POST /api/rag/evaluate` runs every question through retrieval and stores a snapshot in `retrieval_evaluations` (migration V8); `GET /api/rag/evaluate` returns the newest snapshot (404 before the first); `GET /api/rag/evaluate/{id}` returns one by id.
        * A snapshot carries `hitAt1`, `hitAt3`, `hitAt5`, `mrr`, `tickerHitAt5`, `window`, `retrievalStrategy`, the run `properties` (window, latestFilingsOnly, setCreatedOn, candidateCount, rerankingEnabled), per-question `results` (rank and matched chunk id, null on a miss), and `misses` with the top three returned chunks (chunk id, accession, section, similarity) or the retrieval error.
    * Regression floor
        * `RetrievalEvaluationLiveTests` (opt-in, `@EnabledIfSystemProperty(named = "rag.evaluation.live", matches = "true")`) runs the real evaluation against the local store and asserts hit@5 at or above `rag.evaluation.min-hit-at-5` (default 0.50: baseline 0.6 minus 0.1, rounded down to a multiple of 0.05). It prints the metrics, per-ticker hit@5, and every miss with its top chunks, and it runs inside a rolled-back transaction so no snapshot is stored (the id sequence still advances).
        * Run: `set -a && source .env && set +a && ./mvnw -q -o test -Dtest=RetrievalEvaluationLiveTests -Drag.evaluation.live=true`; override the floor with `-Drag.evaluation.min-hit-at-5=<fraction>`. Without the system property the test is skipped, so `./mvnw -q verify` never embeds anything.
        * Verified 2026-09-12: the default floor passes (exit 0, hit@5 0.600000); a floor of 1.01 fails with an assertion naming hit@5 0.600000 and the nine miss ids (exit 1). Evidence: `documentation/live-runs/2026-09-12-retrieval-eval/`.
        * Raise the floor after a retrieval improvement lands and its new baseline is recorded here; never lower it to make a change pass.
    * First baseline (set v1, snapshot id 13, `GET /api/rag/evaluate/13`)

| Field | Value |
|---|---|
| Snapshot | id 13, evaluated 2026-09-12 09:49:07 UTC (ids 7 and 12 from the same day carry identical figures) |
| Set / questions | v1 / 30 (10 AAPL, 10 MSFT, 10 NVDA) |
| Retrieval | FILTERED_VECTOR, window 10, candidateCount 40, reranking off, latest filings only |
| hit@1 | 0.300000 |
| hit@3 | 0.533333 |
| hit@5 | 0.600000 |
| MRR | 0.435833 |
| Per-ticker hit@5 | AAPL 0.900000, MSFT 0.600000, NVDA 0.300000 |
| Floor derived | rag.evaluation.min-hit-at-5 = 0.50 |
| Evidence | `documentation/live-runs/2026-09-12-retrieval-eval/baseline-snapshot-13.json` |

    * Misses in the baseline (nine questions with no matching chunk in the window) and what they suggest

| Question | Expected | Retrieval returned (top three) | What it suggests |
|---|---|---|---|
| msft-07 (NARRATIVE, 2030 sustainability goals) | 10-K ITEM_1A | 10-K ITEM_1 (chunk 460, similarity 0.53), ITEM_7, 10-Q ITEM_2 | The rank-1 Item 1 chunk states the same carbon negative, water positive, zero waste goals; the expectation is narrower than the filing. Add the Item 1 passage as a second expectation in set v2 (RAG-11) |
| msft-08 (FIGURE, OpenAI commercial revenue) | 10-K ITEM_8 (investments note) | 10-K ITEM_7 (0.67), 10-Q ITEM_2 (0.67), ITEM_7 (0.65) | The MD&A partnership paragraphs outrank the related-party note among 45 Item 8 chunks; the exact term "OpenAI" plus "revenue" is a keyword case (RAG-2) |
| nvda-01 (FIGURE, fiscal 2026 revenue and growth) | 10-K ITEM_7 fiscal-year summary row "Revenue $ 215,938 $ 130,497 Up 65%" | 10-K ITEM_7 (chunk 805, 0.70), ITEM_7, ITEM_15 | Rank 1 is the adjacent segment table with the same totals ("Total $ 215,938 $ 130,497 $ 85,441 65 %"); a second expectation on that chunk would count it (RAG-11). Table rows embed poorly (RAG-2) |
| nvda-02 (FIGURE, Data Center growth) | 10-K ITEM_7 | 10-K ITEM_7 (0.68), ITEM_15 (0.67), ITEM_7 (0.66) | Right section, neighbouring chunks; a reranker over the 40 candidates (RAG-1) or a keyword boost on "Data Center" and "68%" (RAG-2) |
| nvda-03 (FIGURE, share repurchases) | 10-K ITEM_7 | 10-K ITEM_5 (chunk 797, 0.69), ITEM_15, ITEM_5 | Item 5 states the identical sentence ("we repurchased 282 million shares ... $40.4 billion") and was rank 1; the expectation's section is too narrow (RAG-11) |
| nvda-04 (FIGURE, employees and R&D headcount) | 10-K ITEM_1 | 10-K ITEM_7 (0.62), ITEM_15 (0.62), ITEM_7 (0.62) | Low, flat similarities; the headcount sentence sits in a 15-chunk Item 1 that the query does not pull ahead of MD&A. Keyword ("employees") would help (RAG-2) |
| nvda-05 (NARRATIVE, fabless manufacturing) | 10-K ITEM_1 | 10-K ITEM_1 (chunks 742, 744, 743; 0.57 to 0.54) | Right section, the three chunks before the passage (749); Item 1's opening business overview outscores the manufacturing paragraph. A reranker (RAG-1) is the fix; the adjacent-chunk pattern also argues for RAG-5 |
| nvda-07 (NARRATIVE, manufacturing concentration and geopolitics) | 10-K ITEM_1A | 10-Q ITEM_1A (0.59), 10-K ITEM_1A (0.59), 10-K ITEM_7 | Right sections in both filings, wrong chunks among 35 risk-factor chunks; the country list is in chunk 770. Reranking (RAG-1); the 10-Q may restate the risk, worth a second expectation (RAG-11) |
| nvda-09 (FIGURE, Q2 fiscal 2027 Data Center revenue) | 10-Q ITEM_2 | 10-Q ITEM_1 (0.70), ITEM_2 (0.70), ITEM_2 (0.69) | Right filing, financial statements and neighbouring MD&A chunks outrank the sentence in chunk 879; "$89.0 billion" is a keyword case (RAG-2) |

    * Reading the misses
        * Seven of nine are NVDA questions (NVDA hit@5 0.3 against AAPL 0.9): NVDA's 10-K has the longest sections here (Item 1A 35 chunks, Item 15 33 chunks, Item 1 15 chunks), so semantic neighbours crowd the window. NVDA is the first concrete target for RAG-1 and RAG-2.
        * NVDA's consolidated financial statements live under ITEM_15 (33 chunks), with ITEM_8 a one-chunk cross-reference; a consumer that filters on ITEM_8 for financial statements misses NVDA entirely (RAG-7). The evaluation itself applies no section filter, so this does not affect the baseline.
        * Figure questions whose phrases are table rows ("Greater China 64,377 (4) % 66,952", "Revenue $ 215,938 $ 130,497 Up 65%") depend on the embedding of a number-dense row; the hits among them come from short sections. Hybrid keyword retrieval (RAG-2) is the direct remedy.
        * Three misses (msft-07, nvda-01, nvda-03) are expectation narrowness rather than retrieval failure: retrieval returned the fact at rank 1 from another section or the adjacent chunk. Set v2 should carry alternative expectations for them (RAG-11); until then the baseline understates hit@5 by up to 0.1.
        * Parser observations recorded while writing the set: 8-K section keys differ by filer (AAPL Item 2.02 as ITEM_2, colliding in name with 10-K/10-Q Item 2; MSFT as ITEM_7_01), and the MSFT 10-Q 0001193125-26-191507 carries ITEM_1 chunks titled ", 1A" and Part II items (Legal Proceedings, Unregistered Sales) under the Part I keys ITEM_1 and ITEM_2 (RAG-8, RAG-9). The whitespace normalisation (`\s+`) excludes U+00A0; no phrase contains one today (RAG-10).
    * Evidence
        * `documentation/live-runs/2026-09-12-retrieval-eval/`: `baseline-snapshot-13.json` (row_to_json of the stored snapshot), `live-test-pass.log` (default floor, exit 0), `live-test-floor-1.01.log` (raised floor, exit 1 with the assertion message), `run.log`.

* Filing Freshness
    * Purpose
        * Keep stored filings current without re-downloading anything already embedded.
        * Tell every consumer what is stored and whether it should be trusted as current.
    * FilingRefreshProperties
        * Configuration prefix: rag.refresh.

| Property | Default | Meaning |
|---|---|---|
| rag.refresh.enabled | true | Nightly SEC index comparison for every stored ticker (RAG_REFRESH_ENABLED) |
| rag.refresh.cron | 0 0 7 * * * | Schedule, after the SEC's daily filing cutoff |
| rag.refresh.zone | Asia/Singapore | Time zone for the cron expression |
| rag.refresh.quarterly-cadence-days | 100 | A newest 10-Q/10-K older than this is past cadence |
| rag.refresh.recheck-hours | 24 | An index comparison within this window counts as verified |
| rag.refresh.index-limit | 40 | Recent filings of any type read from the SEC index before per-type limits |
| rag.refresh.limits | 10-K:1, 10-Q:1, 8-K:3 | Newest N filings kept current per type; YAML keys use bracket syntax |

    * FilingFreshnessService
        * assess(String ticker)
            * Read the newest EMBEDDED filing date per configured type.
            * cadenceExceeded when the newest 10-Q or 10-K is older than quarterly-cadence-days, or none is stored.
            * mayBeStale when cadence is exceeded and no index comparison succeeded within recheck-hours.
            * Verification times live in memory per application instance; a restart costs one extra comparison per ticker.
        * refresh(String ticker)
            * Read index-limit recent filings from the SEC submissions index in one call.
            * Keep the newest limit entries per type; any not stored as EMBEDDED is new.
            * Ingest each type with new entries through FilingIngestionService, which skips completed accessions and locks per accession.
            * One type's failure does not stop the others and is reported in failedTypes; verification is recorded only on full success.
        * ensure(String ticker)
            * Nothing stored: refresh, reporting INGESTED, INGESTION_FAILED, or NO_FILINGS_AVAILABLE.
            * Stored but mayBeStale: refresh, reporting REFRESHED (new filings), VERIFIED (nothing newer at SEC), or REFRESH_FAILED.
            * Otherwise FRESH with no SEC call.
            * Malformed tickers and tickers unknown to SEC raise UnknownTickerException.
    * FilingRefreshScheduler
        * Runs refresh(ticker) for every distinct stored ticker on the cron schedule; failures are isolated per ticker and summarized in the log.
        * Created only when rag.refresh.enabled=true. Each run downloads and embeds only new filings, so the nightly cost is proportional to what changed.
    * Endpoints (same local-only posture as ingestion)
        * POST /api/rag/refresh?ticker=AAPL: one index comparison; returns checkedAt, indexFilings, newAccessions, ingestedTypes, failedTypes.
        * GET /api/rag/freshness?ticker=AAPL: latest dates per type, newestQuarterly, cadenceExceeded, lastVerifiedAt, mayBeStale.
    * Known limitations
        * Cadence is a heuristic; a company that files late looks stale until the index is compared, which the recommendation loop does on demand.
        * Every index comparison re-reads the SEC ticker map and submissions JSON through SECClient; there is no HTTP cache yet.
        * Amended filings (10-K/A, 10-Q/A) are not tracked.

* Recommendation Consumer
    * [Agent Harness](Agent_Harness.md) documents the opt-in qualitative research loop using FilingRetrievalService.
    * [Direct IBKR Integration](IBKR.md) documents broker reads, TWS socket configuration, and diagnostics.
    * Ingestion and retrieval remain usable when broker and recommendation features are disabled.
    * Since 2026-09-11 the recommendation loop ingests a ticker with no EMBEDDED filings itself, and since 2026-09-12 it also refreshes a ticker past its filing cadence, through FilingFreshnessService.ensure.

* Change log — 2026-09-10: chunk quality and retrieval redundancy
    * Read-only database audit found 6 filings and 189 chunks, with no duplicate accession numbers, filing source URLs, or per-filing chunk indexes. Repeated text included contents entries and legitimate short disclosures.
    * Parser now removes contents tables with at least two distinct Item links resolving to targets outside the table. This conservative filter preserves ordinary financial tables; unlinked contents layouts are not yet covered.
    * Chunk embedding input now prefixes the section key and title. Stored passage text, character offsets, and citations remain unchanged. The existing token_count remains an estimate for the passage, excluding the added heading.
    * Retrieval suppresses whitespace-normalized identical text and substantial overlapping text within the same filing, section key, and section title before top-K selection or reranking. Substantial overlap means at least half the shorter passage and at least 200 characters; normal 500-character overlap between full chunks remains eligible.
    * Evidence across different filings or sections stays distinct, even when its text or source URL repeats. Retained results preserve their original chunk IDs and source metadata. Candidate counts report raw vector candidates; diversity filtering may return fewer than top-K.
    * Deployment: retrieval filtering applies to existing rows after restart. Parser and embedding improvements apply only to newly processed filings. Ordinary ingestion skips completed filings; existing EMBEDDED rows need a separately controlled rebuild to receive these changes. This change does not delete or re-embed existing database content.
    * Focused regression checks cover linked contents removal, preservation of financial values and short disclosures, section-aware embedding input, citation text preservation, duplicate/overlap suppression, and preservation of distinct sections, filings, and ordinary chunk overlap.

* Change log — 2026-09-10: explicit filing rebuild workflow
    * Added `POST /api/rag/filings/{filingId}/rebuild` for rebuilding one stored filing using its stored source URL. Example: `curl -i -X POST http://localhost:8080/api/rag/filings/3/rebuild`.
    * The synchronous response includes runId, filingId, outcome, processingVersion, and resulting chunk count. Unknown IDs return 404; concurrent processing of the same accession returns 409 before downloading or embedding.
    * Normal ingestion still skips completed filings, regardless of processing version. Explicit rebuilding replaces chunks and embeddings in one transaction; fetch, parsing, embedding, flush, or commit failure retains the previously committed filing and chunks.
    * New migration V3 adds nullable sec_filings.processing_version and filing_rebuild_runs. Existing filings retain a null version until rebuilt; successful processing records `sections-v2-context-v2`.
    * Successful rebuild audits commit atomically with replacement, including previous version and chunk counts. Failed rebuild audits are written after rollback, with a sanitized exception class. Audit persistence itself can fail during a database outage; the application still logs the failed run ID. Process termination rolls back replacement but may leave no outcome audit.
    * PostgreSQL transaction advisory locks serialize normal ingestion and rebuilds by accession across application instances. Locks release on commit/rollback; a hash collision can conservatively reject an unrelated request. Requests rejected as missing/busy do not create rebuild audit rows.
    * Rebuilding holds a database connection and transaction during external processing, matching the current ingestion model. This is a synchronous local workflow, without a background queue. Apply the same local access restrictions as the existing ingestion endpoint.
    * Restart the application to apply V3 before calling the endpoint. Rebuild selected IDs sequentially; each call downloads and embeds again. Chunk IDs change on successful replacement, so previously saved chunk-ID references may become obsolete; filing source URLs remain stable.
    * Inspect outcomes with `SELECT * FROM filing_rebuild_runs ORDER BY recorded_at DESC;` and versions with `SELECT id, ticker, processing_version FROM sec_filings;`.
    * Verification: disposable PostgreSQL suite passed (89 discovered, 88 passed, 1 opt-in IBKR test skipped), including replacement, preservation of original chunk IDs after a vector write failure, audit persistence, normal-ingest skipping, and lock rejection/release. Production filings were not rebuilt during development.

* Live rebuild verification — 2026-09-10
    * Started the application with local configuration and verified schema V3. Sequentially rebuilt all six stored AAPL filings through the explicit rebuild endpoint; all six audit outcomes were SUCCEEDED.
    * Chunk counts by filing ID: 3 (10-K) 99 → 76; 4 (10-Q) 42 → 31; 5 (8-K) 2 → 2; 6 (10-Q) 43 → 32; 7 (8-K) 2 → 2; 8 (8-K) 1 → 1. Total: 189 → 144.
    * Live concurrent request against filing 3 returned HTTP 409 during rebuilding.
    * Automated read-only SQL assertions passed: all six filings EMBEDDED with version sections-v2-context-v2; non-null, nonzero 1536-dimensional embeddings; no duplicate chunk indexes or filing source URLs; known contents-only Risk Factors / Financial Statements / Legal Proceedings page-number snippets absent.
    * Real retrieval smoke test passed for “What are the main business risks and legal proceedings?”: five results, distinct chunk IDs, nonempty passages, SEC source URLs, from filings 3 and 4. This checks retrieval operation and metadata, not a full evaluation of answer quality.
    * Legitimate short disclosures remain. Some page-footer strings also remain, including a footer-only ITEM_6 passage; broader footer cleanup is a remaining parser improvement.
    * Local execution artifacts: /tmp/stock-rebuild-results.json, /tmp/stock-rebuild-retrieval.json, /tmp/stock-rebuild-app.log. The application remains running on port 8080.

* Change log — 2026-09-12: filing freshness
    * Added the rag.freshness package: FilingRefreshProperties, FilingFreshness, FilingRefreshResult, FilingFreshnessService, and FilingRefreshScheduler, plus SECFilingRepository queries for the newest filing per type, stored-accession checks, and distinct tickers.
    * The recommendation loop's RAG branch now calls ensure(ticker): a missing ticker is ingested, a stale one refreshed, a fresh one left alone. recommendation.auto-ingest-filing-types is removed; rag.refresh.limits governs both paths, so 8-K filings are now kept current too.
    * RecommendationResponse gains dataFreshness (latest filing dates per type, filingsVerifiedAt, filingsMayBeStale, barsAsOf, quoteUpdatedAt, quoteAvailability) and the limitation FILINGS_MAY_BE_STALE.
    * Refresh ingests each new filing on its own through the new FilingIngestionService.ingestOne, so one unparseable filing never blocks the others; results carry ingestedAccessions and failedAccessions. The index counts as compared even when a filing fails to parse; only a failed SEC call leaves a ticker unverified.
    * Parser: headings typeset with Unicode spaces (thin space U+2009 after "Item", as in Donnelley 8-K HTML) were invisible to the item pattern because Java's \s does not match them; normalize now maps every space separator and zero-width character to a plain space. 8-K decimal items keep their sub-item in the key (ITEM_7_01, title "Regulation FD Disclosure") instead of ITEM_7 with a title beginning "01". Filings stored before this change keep their old keys until rebuilt.
    * Tests: freshness assessment and cadence, per-type limits and new-accession detection, per-filing failure isolation, ensure outcomes, scheduler isolation, the parser cases above, and the harness path with scripted responses.
    * Live verification — 2026-09-12, 09:45 SGT: AAPL index comparison in 1.0 second found nothing newer than the stored 2026-07-31 10-Q; freshness reported cadenceExceeded=false and lastVerifiedAt set. MSFT comparison found three 8-Ks (2026-06-05, 2026-07-29, 2026-09-02) absent from the store; the first attempt failed on the thin-space heading and left the 2026-09-02 filing FAILED, the retry after the parser fix ingested all three in 4.4 seconds with sections ITEM_5_02, ITEM_2_02/ITEM_9_01, and ITEM_7_01/ITEM_9_01. Evidence: [freshness before](live-runs/2026-09-12-filing-freshness/freshness-before.json), [AAPL refresh](live-runs/2026-09-12-filing-freshness/refresh-aapl.json), [MSFT first refresh](live-runs/2026-09-12-filing-freshness/refresh-msft.json), [MSFT retry](live-runs/2026-09-12-filing-freshness/refresh-msft-after-fix.json), [MSFT freshness](live-runs/2026-09-12-filing-freshness/freshness-msft.json), [run log](live-runs/2026-09-12-filing-freshness/run.log).
