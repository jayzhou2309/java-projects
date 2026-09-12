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
        * Orchestrate filtered vector retrieval, keyword plus vector fusion (on by default since 2026-09-12), and optional reranking.
        * Return SEC evidence without generating a recommendation or answer.
    * Methods
        * retrieve(RetrievalRequest request)
            * Normalize ticker, filing types, and section keys using Locale.ROOT.
            * Resolve topK and latest-filings policy.
            * Embed the query using FilingEmbeddingService.embed(query).
            * Retrieve the closest eligible chunks from FilingRetrievalRepository.
            * Resolve hybrid retrieval: the request's `hybrid` field when present, else `rag.retrieval.hybrid-enabled`. When on and `keywordTerms(query)` is non-empty, also call findKeywordChunks (keyword-candidate-count rows) and fuse the rankings (a figure leg joins them for numeric queries, Hybrid Retrieval below) by weighted reciprocal rank fusion: fused score = sum over the legs containing the chunk of weight / (rrf-k + rank), rank 1-based per leg, weights `rrf-vector-weight` 1.0, `rrf-keyword-weight` 0.5, `rrf-figure-weight` 1.0 since the 2026-09-12 fusion tuning; order by fused score descending, then similarityScore descending, then chunk id; `candidatesRetrieved` is the fused set size.
            * A stopword-only query skips the keyword search (repository not called); a keyword-search exception is logged at WARN with its class name and the vector candidates are used alone; neither fails the retrieval.
            * Keep vector order when reranking is disabled; diversify and cut to topK after fusion exactly as without it.
            * Invoke FilingReranker when enabled and a provider adapter is configured.
            * Return selected passages with unchanged citation metadata and cosine scores; a chunk found only by the keyword path carries the cosine similarity the keyword query computed.
            * Log query-embedding, vector-search, keyword-search (candidates, elapsed ms), fusion (fused size), selection, completion, and failure steps.
        * fuse(List vectorCandidates, List keywordCandidates, int k) and reciprocalRankScores(List rankings, int k) (package-private, static)
            * Reciprocal rank fusion with the scores kept as exact decimals (scale 18) so equal rank multisets tie exactly; a chunk in both lists keeps the vector list's instance.
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
        * keywordTerms(String query) (public static)
            * Distinct case-folded tokens of length 2+ (letters and digits; commas and periods kept between digits so `64,377` and `40.4` stay whole), PostgreSQL english stopwords removed, each quoted for tsquery and joined with ` | `; never concatenated into SQL.
            * PostgreSQL parses the quoted figure `'64,377'` into the phrase `'64' <-> '377'`, the same split the stored vector holds, so the figure matches only where it appears as one number.
    * Keyword index (migration V9, `V9__chunk_keyword_index.sql`)
        * `sec_filing_chunks.content_tsv tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED`, populated for every existing row on migration and kept in step with content by PostgreSQL.
        * GIN index `idx_sec_filing_chunks_content_tsv` on that column; the keyword CTE is NOT MATERIALIZED so the planner can use it, and only that variant of the shared CTE projects `content_tsv` (the materialized vector CTE never reads it).
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
        * hybrid-enabled: true (keyword plus vector fusion; on by default since 2026-09-12 after the comparison in Hybrid Retrieval below, a request's `hybrid` field overrides per call).
        * keyword-candidate-count: 40 (keyword rows fetched per query when the hybrid path runs).
        * rrf-k: 60 (the k in reciprocal rank fusion's 1 / (k + rank)).
        * rrf-vector-weight: 1.0 (weight of the vector ranking in reciprocal rank fusion: a chunk at rank r in it scores weight / (k + r)).
        * rrf-keyword-weight: 0.5 (weight of the keyword ranking; 0.5 since the 2026-09-12 fusion tuning, snapshot 51, Fusion tuning below).
        * rrf-figure-weight: 1.0 (weight of the figure ranking, chunks holding every number in the query; 0 leaves that leg off; 1.0 since the 2026-09-12 fusion tuning, snapshot 51: the leg runs only for queries that carry a figure, which none of the evaluation set's questions do).
    * Validation
        * default-top-k must be between 1 and 20.
        * candidate-count must be between 20 and 200.
        * keyword-candidate-count must be between 20 and 200.
        * rrf-k must be between 1 and 1000.
        * rrf-vector-weight, rrf-keyword-weight, and rrf-figure-weight must be between 0 and 10.
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
        * hybrid: true forces keyword plus vector fusion for this call, false forces vector only; absent follows `rag.retrieval.hybrid-enabled`.
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
        * retrievalStrategy: FILTERED_VECTOR or FILTERED_VECTOR_RERANKED; HYBRID_RRF or HYBRID_RRF_RERANKED when the keyword search ran and returned (an empty keyword result included), FILTERED_VECTOR when hybrid is off, the query has no keyword terms, or the keyword search failed.
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
        * Model reranking and answer generation remain separate next steps; hybrid keyword retrieval is in place with tuned weights and a figure leg (Hybrid Retrieval and Fusion tuning below, RAG-12 done).

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


* Retrieval Methods (overview, as of 2026-09-12)
    * What retrieval is for
        * Given a ticker and a question, return the few stored filing passages most likely to hold the answer. The recommendation loop searches with the user's question before the RAG specialist model runs and again on the specialist's own queries; the passages returned become the evidence the manager reasons over and cites, and the critic checks the answer against them. Retrieval quality therefore bounds recommendation quality.
    * The eligibility pool (one SQL CTE shared by every method)
        * Only the requested ticker; only filings whose ingestion status is EMBEDDED; by default only the latest stored filing of each type (10-K, 10-Q, 8-K), unless a date range is given; optional filing-type and section filters. Every method below draws candidates from exactly this pool, so combining them never widens what is searched.
    * Method 1: vector similarity (since 2026-09-09)
        * The question is embedded once with `text-embedding-3-small` (1536 dimensions). Each chunk's stored embedding is compared by cosine distance in pgvector (`<=>`), exact search over the pool, `candidate-count` 40 nearest chunks. Strong on paraphrase and narrative questions ("why did margins fall"), weak on exact tokens: an embedding blurs "215,938" or "OpenAI" into their surroundings.
    * Method 2: keyword search (since 2026-09-12, RAG-2)
        * PostgreSQL full-text search over a stored generated column `content_tsv = to_tsvector('english', content)` with a GIN index (migration V9). The question is turned into an OR query of its distinct alphanumeric tokens (stopwords dropped; numbers keep their inner commas and periods, so "64,377" matches only the phrase '64' followed by '377'), bound as a parameter to `to_tsquery`, never concatenated. Ranked by `ts_rank_cd`, `keyword-candidate-count` 40. Finds exact terms and figures; scores common words as much as rare ones, which is why it is fused rather than used alone.
    * Method 3: figure search (since 2026-09-12, RAG-12)
        * Runs only when the question carries a number that is not a lone year: an AND query of the question's numeric tokens over the same column, so a chunk must contain every number. Rewards the single chunk that states the figure ("Revenue $ 215,938") over neighbours that share the surrounding words. Off for narrative questions by construction.
    * Combining them: weighted reciprocal rank fusion
        * Each method yields a ranked list. A chunk's fused score is the sum, over the lists containing it, of weight / (k + rank), with k = 60 and weights vector 1.0, keyword 0.5, figure 1.0 (chosen by measurement, see Fusion tuning). Rank-based fusion needs no calibration between cosine similarity and text-search rank; the weights and k are configuration. Ties break by vector similarity, then chunk id. Exact decimal arithmetic makes the order deterministic.
        * Every returned chunk still reports its cosine similarity to the question, whichever method found it, so the recommendation loop's input-coverage confidence keeps its meaning. If the keyword method fails, retrieval degrades to vector-only and says so in `retrievalStrategy` (FILTERED_VECTOR instead of HYBRID_RRF); it never fails because of the keyword path.
    * After fusion
        * Diversify: near-duplicate chunks from the same filing and section (large text overlap) are dropped so the top-k is not five copies of one passage. Then the requested top-k (5 by default; 3 under the lean profile) is returned with citation metadata. A reranker hook (`FilingReranker`) can re-score the fused, diversified candidates with a model; none is installed (RAG-1).
    * Per-request control
        * `hybrid` on `POST /api/rag/retrieve` and `?hybrid=` on `POST /api/rag/evaluate` override the property default for one call, which is how the two strategies are compared without a restart.
    * How it is measured
        * The 30-question evaluation set (Retrieval Evaluation below) records hit@1/3/5 and MRR per stored snapshot. Vector-only: hit@5 0.600, MRR 0.436 (snapshot 35). Hybrid, equal weights: 0.633, 0.437 (snapshot 34). Hybrid with the tuned weights: 0.633, MRR 0.463, hit@1 0.333 (snapshot 51, the current default). The set contains no figure-bearing questions, so the figure method is evidenced only by live queries until set v2 (RAG-11). NVDA remains the weak ticker (hit@5 0.3) for structural reasons recorded in RAG-7 and RAG-1.
    * What is deliberately not done
        * No approximate vector index (exact search over a few hundred chunks per ticker is fast); no model reranker (RAG-1); no query rewriting or expansion; no cross-ticker search; passages are cut only at the model boundary (`recommendation.model-passage-chars`), never in the store.

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
  Filter Eligible Filings and Chunks (one CTE shared by both legs)
  ↓
  Exact pgvector Cosine Similarity Search ∥ Full-text Keyword Search (content_tsv, GIN; hybrid-enabled, default true) ∥ Figure Search (AND of the query's numbers; only when the query carries one)
  ↓
  Weighted Reciprocal Rank Fusion (k = 60; weights vector 1.0, keyword 0.5, figure 1.0), then Diversify
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
    * Current baseline: filtered exact vector search fused with full-text keyword search by weighted reciprocal rank (since 2026-09-12; Hybrid Retrieval below), the keyword leg at 0.5 and a figure leg at 1.0 for queries that carry a number (Fusion tuning below).
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
        * `POST /api/rag/evaluate?hybrid=true|false` passes that value as the `hybrid` field of every retrieval request (forcing keyword plus vector fusion on or off for the whole run without a restart) and records it as `properties.hybrid`; without the parameter every request carries null (each follows `rag.retrieval.hybrid-enabled`) and `properties.hybrid` is null.
        * A snapshot carries `hitAt1`, `hitAt3`, `hitAt5`, `mrr`, `tickerHitAt5`, `window`, `retrievalStrategy`, the run `properties` (window, latestFilingsOnly, setCreatedOn, candidateCount, rerankingEnabled, hybridEnabled, keywordCandidateCount, rrfK, hybrid), per-question `results` (rank and matched chunk id, null on a miss), and `misses` with the top three returned chunks (chunk id, accession, section, similarity) or the retrieval error.
    * Regression floor
        * `RetrievalEvaluationLiveTests` (opt-in, `@EnabledIfSystemProperty(named = "rag.evaluation.live", matches = "true")`) runs the real evaluation against the local store and asserts hit@5 at or above `rag.evaluation.min-hit-at-5` (default 0.50: the current baseline's hit@5 minus 0.1, rounded down to a multiple of 0.05, never lowered; vector-only 0.6 gave 0.50 and the hybrid baseline 0.633333 gives 0.533 rounded down to 0.50, so the floor stays). It prints the metrics, per-ticker hit@5, and every miss with its top chunks, and it runs inside a rolled-back transaction so no snapshot is stored (the id sequence still advances).
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

* Hybrid Retrieval (keyword plus vector, Follow_Ups RAG-2; plan `plans/2026-09-12-hybrid-keyword-retrieval.md`)
    * How it works
        * Keyword terms: `FilingRetrievalRepository.keywordTerms(query)` lowercases the query and keeps the distinct alphanumeric tokens of length 2 or more (commas and periods inside numbers kept, so `215,938` and `40.4` stay whole), drops a fixed english stopword list, quotes each token, and OR-joins them (`'fiscal' | '2026' | 'revenue' | '215,938'`); the string is a bound parameter of `to_tsquery('english', :terms)`, never concatenated into SQL. An empty term string (stopwords or punctuation only) means no keyword search.
        * Generated column: migration V9 adds `sec_filing_chunks.content_tsv tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED` with the GIN index `idx_sec_filing_chunks_content_tsv`; PostgreSQL keeps it in step with `content`, so rebuilds need nothing extra.
        * Candidates: `findKeywordChunks` draws from the same eligibility CTE as the vector search (same ticker, EMBEDDED filings, latest-per-type policy, type, date, and section filters), matches `content_tsv @@ to_tsquery`, orders by `ts_rank_cd` then cosine similarity then chunk id, and returns `keyword-candidate-count` rows (default 40), each carrying the cosine similarity to the query embedding as `similarityScore`, so the score keeps one meaning whichever leg found the chunk.
        * Fusion: weighted reciprocal rank fusion with `rrf-k` (default 60): fused score = sum over the legs that contain the chunk of weight_leg / (k + rank), rank 1-based within each leg, with `rrf-vector-weight` 1.0, `rrf-keyword-weight` 0.5, and `rrf-figure-weight` 1.0 by default since the 2026-09-12 fusion tuning (snapshot 51; the RAG-2 measurement used 1.0 / 1.0 with no figure leg), scores kept as exact decimals so equal rank pairs tie exactly; ties break by vector similarity descending, then chunk id ascending; a chunk repeated within one list counts once at its first position without shifting later ranks. The diversify step and the topK cut apply to the fused list exactly as they did to the vector list; a reranker, when one exists, receives the fused, diversified candidates (RAG-1).
        * Figure leg (Follow_Ups RAG-12; plan `plans/2026-09-12-fusion-tuning.md`, Milestone 1): a third ranking for figure-like queries. `FilingRetrievalRepository.figureTerms(query)` keeps only the numeric tokens of `keywordTerms` (`65%` gives `65`, `$40.4` gives `40.4`) and AND-joins them (`'2026' & '215,938' & '65'`), so `findFigureChunks` returns, from the same eligibility pool and with the same `ts_rank_cd` order and cosine similarity per row, only the chunks that contain every figure; a four-digit token from 1900 to 2100 is a year and never makes a figure query alone (`risks in fiscal 2025` gives no figure leg), though it rides along beside another figure. The leg runs only when hybrid resolved on, `rrf-figure-weight` is above 0, and the figure string is non-empty; fusion is then weighted: fused score = sum over the legs containing the chunk of weight / (k + rank) with `rrf-vector-weight`, `rrf-keyword-weight`, and `rrf-figure-weight`, the same exact decimals and tie-breaks as before, and with equal weights and no figure leg (1.0, 1.0, 0.0) the fused order is exactly the RAG-2 two-leg order (snapshot 48 reproduces snapshot 34). A figure-leg exception is logged at WARN with the exception class and fusion proceeds over the vector and keyword legs; the strategy stays HYBRID_RRF whether or not the figure leg ran. The weights were chosen in Milestone 2 (Fusion tuning below).
        * Fallback: with hybrid off, an empty term string, or a keyword-path exception (logged at WARN with the exception class) the vector candidates are used alone and the strategy is FILTERED_VECTOR; retrieval never fails because of the keyword path. When the keyword search ran (an empty keyword result included) the strategy is HYBRID_RRF, or HYBRID_RRF_RERANKED with a reranker.
        * Override: `RetrievalRequest.hybrid` (true forces fusion, false forces vector only, absent follows `rag.retrieval.hybrid-enabled`) and `POST /api/rag/evaluate?hybrid=` for a whole evaluation run, so both strategies can be compared without a restart.
        * Token effect: none. The keyword leg is a PostgreSQL query; a hybrid retrieval still embeds the query exactly once, and no chat model is involved anywhere in retrieval or evaluation.
    * Comparison (set v1, both snapshots made on 2026-09-12 with the same store and the same code, `GET /api/rag/evaluate/35` and `/34`)

| Field | Vector only | Hybrid RRF |
|---|---|---|
| Snapshot | id 35, evaluated 2026-09-12 14:15:06 UTC, `properties.hybrid` null (no override; the property was false at the time) | id 34, evaluated 2026-09-12 14:14:57 UTC, `properties.hybrid` true (`POST /api/rag/evaluate?hybrid=true`) |
| Set / questions | v1 / 30 (10 AAPL, 10 MSFT, 10 NVDA) | v1 / 30 |
| Retrieval | FILTERED_VECTOR, window 10, candidateCount 40, reranking off, latest filings only | HYBRID_RRF, window 10, candidateCount 40, keywordCandidateCount 40, rrfK 60, reranking off, latest filings only |
| hit@1 | 0.300000 | 0.300000 |
| hit@3 | 0.533333 | 0.533333 |
| hit@5 | 0.600000 | 0.633333 |
| MRR | 0.435833 | 0.436667 |
| Per-ticker hit@5 | AAPL 0.900000, MSFT 0.600000, NVDA 0.300000 | AAPL 0.900000, MSFT 0.700000, NVDA 0.300000 |
| Misses (no matching chunk in the window) | 9: msft-07, msft-08, nvda-01, nvda-02, nvda-03, nvda-04, nvda-05, nvda-07, nvda-09 | 7: msft-08, nvda-01, nvda-02, nvda-03, nvda-04, nvda-05, nvda-07 |
| Evidence | `documentation/live-runs/2026-09-12-hybrid-retrieval/vector-snapshot-35.json` | `documentation/live-runs/2026-09-12-hybrid-retrieval/hybrid-snapshot-34.json` |

    * Per-question changes (every question whose rank differs between snapshot 35 and 34; the other 19 questions kept their rank and matched chunk)

| Question | Kind | Rank 35 (vector) | Rank 34 (hybrid) | Note |
|---|---|---|---|---|
| msft-07 (2030 sustainability goals) | NARRATIVE | miss | 4 | Miss to hit@5: the Item 1A passage itself (chunk 495) is now in the top 5, so it no longer needs the alternative Item 1 expectation planned in RAG-11 |
| nvda-09 (Q2 fiscal 2027 Data Center revenue, "$89.0 billion") | FIGURE | miss | 8 | Miss to hit within the window (chunk 879): counts for MRR, not yet for hit@5 |
| msft-05 (three reportable segments) | NARRATIVE | 6 | 2 | Into the top 5 |
| msft-06 (power and energy constraints) | NARRATIVE | 2 | 1 | |
| msft-03 (Microsoft Cloud gross margin decline) | NARRATIVE | 3 | 2 | |
| aapl-06 (total deferred revenue, "$13.7 billion") | FIGURE | 4 | 3 | |
| msft-02 (commercial remaining performance obligation) | FIGURE | 2 | 3 | |
| msft-10 (Q3 fiscal 2026 Microsoft Cloud revenue) | FIGURE | 2 | 5 | Still a hit@5 |
| aapl-09 (Q3 fiscal 2026 buyback, "$25.8 billion") | FIGURE | 8 | 10 | |
| msft-01 (Microsoft Cloud revenue growth) | FIGURE | 6 | 10 | |
| msft-04 (employee count and U.S. split) | FIGURE | 1 (chunk 467) | 8 (chunk 466) | Out of the top 5: the largest question-level regression; the expected sentence is in both overlapping chunks and the keyword leg lifted neighbours over them |

    * Default decision
        * Rule (plan, Milestone 3): enable `rag.retrieval.hybrid-enabled` by default only if hit@5 improves and no ticker's hit@5 decreases between the vector-only and the hybrid snapshot; otherwise keep it off.
        * Numbers: hit@5 0.600000 (35) to 0.633333 (34) improves; per-ticker hit@5 AAPL 0.900000 to 0.900000, MSFT 0.600000 to 0.700000, NVDA 0.300000 to 0.300000, none lower. Both conditions hold, so `hybrid-enabled` is true by default since 2026-09-12 (application.yaml and `FilingRetrievalProperties` agree; `FilingRetrievalServiceTests` asserts the property default). hit@1, hit@3 unchanged; MRR 0.435833 to 0.436667.
        * What the rule does not see: msft-04 and msft-01 moved out of, or further from, the top 5 (table above). The rule is ticker-level by design; the question-level regressions were recorded as RAG-12 (fusion tuning), and the Fusion tuning rule below adds the requirement that no FIGURE question drops out of the top 5.
        * Effect on consumers: the RAG specialist's searchFilings tool and the filings prefetch in recommendation runs now retrieve hybrid by default (Agent_Harness.md); a cited passage found by the keyword leg can carry a lower vector similarity, so the raw input-coverage confidence may dip for the same question. Nothing in the harness code changed.
    * Floor decision
        * Rule: `rag.evaluation.min-hit-at-5` = the new hit@5 minus 0.1, rounded down to a multiple of 0.05, never lower than the current floor. 0.633333 minus 0.1 = 0.533333, rounded down to 0.50, equal to the current 0.50, so the floor stays at 0.50 (the value is unchanged; its derivation now cites snapshot 34).
        * Verified 2026-09-12 with the final default: `./mvnw -q -o test -Dtest=RetrievalEvaluationLiveTests -Drag.evaluation.live=true` exit 0, `hybrid=null` so the run followed the property, strategy HYBRID_RRF, hit@5 0.633333, metrics identical to snapshot 34; the transaction rolled back (id 36 consumed, not stored). Evidence: `documentation/live-runs/2026-09-12-hybrid-retrieval/live-test-pass.log`.
    * Observation: exact-figure chunks can still sit behind their neighbours
        * With two legs of equal weight and k = 60, a chunk at keyword rank 1 adds 1/61 = 0.0164 while ranks 1 and 3 on one leg differ by only 1/61 minus 1/63 = 0.0005, so the fused order follows the sum of both legs, and neighbouring chunks that share the query's words (the same table, the 500-character overlap) and score on both legs stay ahead of the one chunk holding the exact figure: aapl-02 and aapl-06 sit at rank 3 under hybrid, msft-02 moved from 2 to 3.
        * The OR-joined keyword leg also scores common query words as much as the rare figure. For the query "fiscal 2026 revenue 215,938 up 65%" the keyword leg alone (`ts_rank_cd` over the latest NVDA filings, checked with psql on 2026-09-12) ranks the chunk with the "Revenue $ 215,938 $ 130,497 Up 65%" row (802) sixth, behind five chunks that contain "revenue", "fiscal", and "2026" but not the figure; fusion cannot lift what neither leg ranks first. Weighting the keyword leg, a smaller k for digit-bearing queries, or AND-ing numeric tokens are the candidates (RAG-12), each to be judged by a fresh pair of snapshots.
    * Evidence
        * `documentation/live-runs/2026-09-12-hybrid-retrieval/`: `vector-snapshot-35.json` and `hybrid-snapshot-34.json` (row_to_json of the stored snapshots), `live-test-pass.log` (floor test with the final default, exit 0), `run.log` (the psql queries, the rank diff, the decision, the build).
    * Fusion tuning (Follow_Ups RAG-12; plan `plans/2026-09-12-fusion-tuning.md`, Milestone 2; measured 2026-09-12)
        * Method: seven configurations of (`rrf-k`, `rrf-keyword-weight`, `rrf-figure-weight`; `rrf-vector-weight` 1.0 throughout), each a fresh application start with the three properties overridden through environment variables and one `POST /api/rag/evaluate` (no `hybrid` parameter, so every request followed `hybrid-enabled` true; `properties.hybrid` null, `properties.hybridEnabled` true), 30 embeddings per run, no chat model. The first row reproduces snapshot 34 and proves the harness. Each snapshot's `properties` carry the k and the three weights it was run with (`GET /api/rag/evaluate/{id}`).
        * Finding before the rule: none of the set's 30 questions carries a figure (they name years such as "fiscal 2026", which `figureTerms` treats as a year alone), so the figure leg was skipped for all 30 questions in every run (`Skipping figure search: reason=noFigureTerms`, 30 per run log) and `rrf-figure-weight` cannot move any set metric: snapshots 48, 49, and 50 are identical, and so are 51 and 53. The keyword weight and k are the only levers the set can see; the figure leg is judged on the NVDA figure query below.
    * Grid (set v1, 30 questions, window 10, candidateCount 40, keywordCandidateCount 40, reranking off, latest filings only; references 35 and 34 from the RAG-2 comparison above)

| Configuration (k / vector / keyword / figure) | Snapshot | hit@1 | hit@3 | hit@5 | MRR | Per-ticker hit@5 (AAPL / MSFT / NVDA) | FIGURE questions in the top 5 (of 16) | Misses (no hit in the window) |
|---|---|---|---|---|---|---|---|---|
| reference: vector only | 35 | 0.300000 | 0.533333 | 0.600000 | 0.435833 | 0.9 / 0.6 / 0.3 | 8: aapl-01, aapl-02, aapl-06, aapl-07, msft-02, msft-04, msft-10, nvda-08 | 9 |
| reference: 60 / 1.0 / 1.0 / off (RAG-2 hybrid) | 34 | 0.300000 | 0.533333 | 0.633333 | 0.436667 | 0.9 / 0.7 / 0.3 | 7: as 35 without msft-04 | 7 |
| 60 / 1.0 / 1.0 / 0.0 (current defaults, harness check) | 48 | 0.300000 | 0.533333 | 0.633333 | 0.436667 | 0.9 / 0.7 / 0.3 | 7 | 7: msft-08, nvda-01, nvda-02, nvda-03, nvda-04, nvda-05, nvda-07 |
| 60 / 1.0 / 1.0 / 1.0 | 49 | 0.300000 | 0.533333 | 0.633333 | 0.436667 | 0.9 / 0.7 / 0.3 | 7 | 7 (as 48) |
| 60 / 1.0 / 1.0 / 2.0 | 50 | 0.300000 | 0.533333 | 0.633333 | 0.436667 | 0.9 / 0.7 / 0.3 | 7 | 7 (as 48) |
| 60 / 1.0 / 0.5 / 1.0 | 51 | 0.333333 | 0.533333 | 0.633333 | 0.463373 | 0.9 / 0.7 / 0.3 | 8: aapl-01, aapl-02, aapl-06, aapl-07, msft-02, msft-04, msft-10, nvda-08 | 7: msft-08, nvda-01, nvda-02, nvda-03, nvda-04, nvda-07, nvda-09 |
| 30 / 1.0 / 1.0 / 1.0 | 52 | 0.300000 | 0.533333 | 0.633333 | 0.434524 | 0.9 / 0.7 / 0.3 | 7 | 8: aapl-09, msft-08, nvda-01, nvda-02, nvda-03, nvda-04, nvda-05, nvda-07 |
| 60 / 1.0 / 0.5 / 2.0 | 53 | 0.333333 | 0.533333 | 0.633333 | 0.463373 | 0.9 / 0.7 / 0.3 | 8 (as 51) | 7 (as 51) |
| 60 / 1.0 / 0.75 / 1.0 (added: 0.5 and 1.0 swap msft-04 and msft-07 across the top-5 line, so a middle value might keep both) | 54 | 0.300000 | 0.566667 | 0.600000 | 0.432910 | 0.9 / 0.6 / 0.3 | 7 | 7 (as 48) |

    * Rule (supersedes the RAG-2 flip rule, which was ticker-level only): choose the highest hit@5 configuration such that, against both snapshot 35 and snapshot 34, (a) no ticker's hit@5 decreases, (b) no FIGURE question that was in the top 5 under either snapshot leaves the top 5 (the union is the eight questions listed for 35), and (c) hit@5 is at least 0.633333; ties on hit@5 break by MRR, then by the smaller change from the current defaults; if nothing qualifies the defaults stay.
    * Rule applied row by row

| Snapshot | (a) tickers vs 35 and 34 | (b) FIGURE top 5 kept | (c) hit@5 ≥ 0.633333 | Result |
|---|---|---|---|---|
| 48 (60 / 1.0 / 1.0 / 0.0) | holds | fails: msft-04 rank 8 (rank 1 under 35) | holds | out |
| 49 (60 / 1.0 / 1.0 / 1.0) | holds | fails: msft-04 rank 8 | holds | out |
| 50 (60 / 1.0 / 1.0 / 2.0) | holds | fails: msft-04 rank 8 | holds | out |
| 51 (60 / 1.0 / 0.5 / 1.0) | holds: 0.9 / 0.7 / 0.3 against 0.9 / 0.6 / 0.3 and 0.9 / 0.7 / 0.3 | holds: aapl-01 1, aapl-02 3, aapl-06 4, aapl-07 1, msft-02 2, msft-04 4, msft-10 1, nvda-08 5 | holds: 0.633333 | qualifies |
| 52 (30 / 1.0 / 1.0 / 1.0) | holds | fails: msft-04 rank 7 | holds | out |
| 53 (60 / 1.0 / 0.5 / 2.0) | holds | holds (the same ranking as 51) | holds | qualifies; ties 51 on hit@5 and MRR, loses the tie-break (figure weight 2.0 is the larger change from 0.0) |
| 54 (60 / 1.0 / 0.75 / 1.0) | fails: MSFT 0.6 against 0.7 under 34 | fails: msft-04 rank 7 | fails: 0.600000 | out |

        * Chosen: snapshot 51, `rrf-k` 60, `rrf-vector-weight` 1.0, `rrf-keyword-weight` 0.5, `rrf-figure-weight` 1.0, now the defaults in application.yaml and `FilingRetrievalProperties` (`FilingRetrievalServiceTests.measuredDefaultsHalveTheKeywordLegAndRunTheFigureLegForNumericQueries` asserts them; the equal-leg fusion tests pin 1.0 / 1.0 / 0.0 explicitly). hit@5 is unchanged at 0.633333; hit@1 0.300000 to 0.333333 and MRR 0.436667 to 0.463373 against 34.
        * Why the keyword weight, and why 0.5: with equal legs a keyword rank-1 neighbour that also sits in the vector top 40 outscores the vector rank-1 chunk (1/61 + 1/(60 + r) against 1/61 alone); at 0.5 the vector rank decides unless the keyword leg agrees strongly, which returns the vector-only order for the MSFT figure questions (msft-04, msft-10, msft-02, msft-01 all back at their snapshot 35 ranks) while keeping msft-05 and msft-06 in the top 5. 0.75 is worse than both ends (54: msft-04 rank 7 and msft-07 rank 6, MSFT 0.6); k 30 sharpens both legs equally and loses aapl-09 from the window (52).
    * Per-question rank changes, winner 51 against 35 (vector only; the other 23 questions kept rank and matched chunk)

| Question | Kind | Rank 35 | Rank 51 | Chunk 35 → 51 |
|---|---|---|---|---|
| msft-04 (employee count and U.S. split) | FIGURE | 1 | 4 | 467 → 466 (the expected sentence sits in both overlapping chunks) |
| msft-10 (Q3 fiscal 2026 Microsoft Cloud revenue) | FIGURE | 2 | 1 | 637 |
| msft-03 (Microsoft Cloud gross margin decline) | NARRATIVE | 3 | 2 | 517 |
| msft-05 (three reportable segments) | NARRATIVE | 6 | 3 | 460 (into the top 5) |
| msft-06 (power and energy constraints) | NARRATIVE | 2 | 1 | 489 |
| msft-07 (2030 sustainability goals) | NARRATIVE | miss | 7 | 495 (into the window) |
| nvda-05 (fabless manufacturing) | NARRATIVE | miss | 10 | 749 (into the window) |

    * Per-question rank changes, winner 51 against 34 (current hybrid; the other 20 questions kept rank and matched chunk)

| Question | Kind | Rank 34 | Rank 51 | Chunk 34 → 51 |
|---|---|---|---|---|
| msft-04 (employee count and U.S. split) | FIGURE | 8 | 4 | 466 (back into the top 5; MSFT stays 0.7 because msft-07 leaves as msft-04 enters) |
| msft-10 (Q3 fiscal 2026 Microsoft Cloud revenue) | FIGURE | 5 | 1 | 637 |
| msft-02 (commercial remaining performance obligation) | FIGURE | 3 | 2 | 514 |
| msft-01 (Microsoft Cloud revenue growth) | FIGURE | 10 | 6 | 514 (closer, still outside the top 5) |
| aapl-09 (Q3 fiscal 2026 buyback) | FIGURE | 10 | 8 | 280 |
| aapl-06 (total deferred revenue) | FIGURE | 3 | 4 | 236 (still a hit@5) |
| msft-05 (three reportable segments) | NARRATIVE | 2 | 3 | 460 |
| msft-07 (2030 sustainability goals) | NARRATIVE | 4 | 7 | 495 (out of the top 5: the cost of the change; RAG-11's alternative Item 1 expectation would cover it) |
| nvda-05 (fabless manufacturing) | NARRATIVE | miss | 10 | 749 |
| nvda-09 (Q2 fiscal 2027 Data Center revenue) | FIGURE | 8 | miss | 879 → none in the window (was never a hit@5) |

        * nvda-01 (fiscal 2026 revenue "215,938") is a miss under 35, 34, and 51 alike: the question text carries no figure, so the figure leg does not run for it, and the expected row chunk (802) is not in the window; the adjacent segment-table chunk 805 with the same totals is rank 3 under 51 (rank 1 under 35, outside the top 3 under 34), which is RAG-11's alternative expectation.
        * The figure leg on the NVDA figure query, checked against the running application with the new defaults on 2026-09-12 (`POST /api/rag/retrieve` `{"ticker":"NVDA","query":"fiscal 2026 revenue 215,938 up 65%","topK":5}`, no `hybrid` field): HYBRID_RRF, 53 fused candidates, figure leg 2 candidates (the only two eligible chunks containing 215,938 and 65), top 5 chunks 802, 805, 807, 882, 880; the "Revenue $ 215,938 $ 130,497 Up 65%" chunk 802 is rank 1 (rank 3 under the RAG-2 defaults: 806, 880, 802, 807, 882). msft-04's question text (`{"ticker":"MSFT","query":"How many people did Microsoft employ at the end of fiscal 2026, and how were they split between the U.S. and other countries?","topK":5}`) returns chunk 466 at rank 4 (top 5: 514, 573, 518, 466, 643), as snapshot 51 records.
    * Floor decision
        * Rule unchanged: winner's hit@5 minus 0.1, rounded down to a multiple of 0.05, never lower than the current 0.50. 0.633333 minus 0.1 = 0.533333, rounded down to 0.50, not higher than the current 0.50, so `rag.evaluation.min-hit-at-5` stays 0.50 and its comment is unchanged.
        * Verified 2026-09-12 with the final defaults: `./mvnw -q -o test -Dtest=RetrievalEvaluationLiveTests -Drag.evaluation.live=true` exit 0 (`live-test-pass.log`), the run following the new properties (weights 1.0 / 0.5 / 1.0, HYBRID_RRF, hit@5 0.633333, MRR 0.463373); the transaction rolled back.
    * Evidence
        * `documentation/live-runs/2026-09-12-fusion-tuning/`: `snapshot-<id>-<configuration>.json` for 48 to 54 (row_to_json of the stored rows), `rule-table.txt` (the metrics, the rule per row, and the per-question ranks of 35, 34, and 48 to 54 side by side), `retrieve-nvda-figure-top5.json`, `retrieve-nvda-figure-top10.json`, `retrieve-msft-04-top5.json` (the checks above), `live-test-pass.log`, `run.log` (each start command's overrides, the snapshot ids, the decision, the builds). Snapshot ids 36 to 47 were consumed by rolled-back live-test transactions and are not stored.

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

* Change log — 2026-09-12: hybrid keyword plus vector retrieval (RAG-2)
    * Migration V9 adds the generated `content_tsv` column and GIN index; `FilingRetrievalRepository.findKeywordChunks` and `keywordTerms` run the keyword leg from the same eligibility CTE as the vector search, every row carrying its cosine similarity; `FilingRetrievalService` fuses both legs by reciprocal rank (`rrf-k` 60, `keyword-candidate-count` 40), diversifies, and cuts to topK; strategy HYBRID_RRF / HYBRID_RRF_RERANKED when the keyword leg ran, FILTERED_VECTOR on hybrid off, an empty term string, or a keyword-path failure (WARN, never propagated). `RetrievalRequest.hybrid` and `POST /api/rag/evaluate?hybrid=` override per call or per run.
    * Measured on set v1 against the same store: vector-only snapshot 35 hit@5 0.600000, MRR 0.435833, 9 misses; hybrid snapshot 34 hit@5 0.633333, MRR 0.436667, 7 misses; per-ticker hit@5 AAPL 0.9 to 0.9, MSFT 0.6 to 0.7, NVDA 0.3 to 0.3. msft-07 became a hit@5 and nvda-09 a hit within the window; msft-04 fell from rank 1 to 8. Under the plan's rule (hit@5 up, no ticker down) `rag.retrieval.hybrid-enabled` is now true by default; `rag.evaluation.min-hit-at-5` stays 0.50 (0.633333 minus 0.1 rounds down to 0.50). Keyword search costs no model tokens.
    * Fix in the same change: `reciprocalRankScores` increments the per-list rank only after the duplicate check, so a repeated id in one list no longer shifts the ranks after it (unreachable with database lists; unit-asserted).
    * Live verification — 2026-09-12, 22:24 SGT: `RetrievalEvaluationLiveTests` with the final default, exit 0, HYBRID_RRF, hit@5 0.633333. Evidence: [vector snapshot 35](live-runs/2026-09-12-hybrid-retrieval/vector-snapshot-35.json), [hybrid snapshot 34](live-runs/2026-09-12-hybrid-retrieval/hybrid-snapshot-34.json), [floor test](live-runs/2026-09-12-hybrid-retrieval/live-test-pass.log), [run log](live-runs/2026-09-12-hybrid-retrieval/run.log).

* Change log — 2026-09-12: fusion tuning for figure-like queries (RAG-12)
    * `FilingRetrievalRepository.figureTerms` and `findFigureChunks` add a third ranking for queries that carry a figure (AND of the numeric tokens, a year alone excluded); `FilingRetrievalService` fuses the legs by weighted reciprocal rank (`rrf-vector-weight`, `rrf-keyword-weight`, `rrf-figure-weight`, `rrf-k`), the snapshot `properties` record the three weights, and a figure-leg failure falls back to the two-leg fusion with a WARN.
    * Measured on set v1 (Fusion tuning above): seven configurations, snapshots 48 to 54, against 35 and 34 under a rule that also protects every FIGURE question either reference had in the top 5. Winner snapshot 51 (k 60, weights 1.0 / 0.5 / 1.0): hit@5 0.633333 unchanged, hit@1 0.333333, MRR 0.463373, no ticker lower, msft-04 back from rank 8 to 4, msft-10 5 to 1; msft-07 4 to 7 and nvda-09 8 to miss are the costs. The set's questions carry no figures, so the figure weight moved nothing there; on the NVDA figure query the "215,938" chunk is rank 1 instead of 3. Defaults changed accordingly; `rag.evaluation.min-hit-at-5` stays 0.50.
    * Live verification — 2026-09-12: `RetrievalEvaluationLiveTests` with the final defaults, exit 0, HYBRID_RRF, hit@5 0.633333. Evidence: [snapshot 51](live-runs/2026-09-12-fusion-tuning/snapshot-51-k60-kw0.5-fig1.0.json), [rule table](live-runs/2026-09-12-fusion-tuning/rule-table.txt), [floor test](live-runs/2026-09-12-fusion-tuning/live-test-pass.log), [run log](live-runs/2026-09-12-fusion-tuning/run.log).
