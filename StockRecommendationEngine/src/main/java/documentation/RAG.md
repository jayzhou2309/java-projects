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

* Recommendation Consumer
    * [Agent Harness](Agent_Harness.md) documents the opt-in qualitative research loop using FilingRetrievalService.
    * [Direct IBKR Integration](IBKR.md) documents broker reads, gateway configuration, and diagnostics.
    * Ingestion and retrieval remain usable when broker and recommendation features are disabled.
