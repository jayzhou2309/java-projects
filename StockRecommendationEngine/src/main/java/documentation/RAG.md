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
            * Resolve reranking: the request's `rerank` field when present, else `rag.retrieval.reranking-enabled`. `rerank: true` with no FilingReranker bean throws RerankerUnavailableException before the query is embedded; `/api/rag/retrieve` maps it to HTTP 400 with the reason in the problem detail (`detail`), never a silent skip.
            * When reranking resolves on and candidates exist, pass the first max(`rerank-candidates`, topK) (default 20) of the fused, diversified list to FilingReranker on a small bounded pool of daemon threads (two threads, queue of 16, shut down on bean destroy) and wait at most `rerank-timeout-ms` (default 2000). Taking at least topK means a reranked response never holds fewer chunks than the fused one would when `rerank-candidates` is below the requested topK. The reranked topK is taken from those candidates only; the strategy gains `_RERANKED`.
            * Fallback: a timeout (the call is cancelled; a cross-encoder inference already running natively still finishes on its pool thread), a RuntimeException from the reranker, an interruption, a call rejected by a saturated pool, or a result failing validateRerankedEvidence is logged at WARN with `reason` (timeout, failure, interrupted, invalidEvidence) and the exception class name only, never its message; retrieval then returns the fused order cut to topK from the full fused list with the strategy HYBRID_RRF or FILTERED_VECTOR.
            * An `Error` thrown by the reranker (for example OutOfMemoryError, or a LinkageError from a native library) is not a fallback case: it propagates and fails the retrieval (HTTP 500 on `/api/rag/retrieve`; recorded as that question's error in an evaluation).
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
        * reranking-enabled: false (a request's `rerank` field, or `?rerank=` on evaluate, overrides per call).
        * rerank-candidates: 20 (the reranker receives the first max(rerank-candidates, topK) of the fused, diversified candidates; the rest are dropped when reranking runs; with reranking off topK is cut from the full fused list).
        * rerank-timeout-ms: 2000 (longest wait for the reranker; past it retrieval keeps the fused order).
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
        * rerank-candidates must be between 5 and 40.
        * rerank-timeout-ms must be between 100 and 60000.
* FilingReranker
    * Purpose
        * Define the extension point for a model-based reranker.
        * One implementation exists: CrossEncoderReranker (Cross-encoder reranker below), created only when `rag.retrieval.cross-encoder.enabled` is true. Tests also use a scripted reranker that reverses the candidate order (`ReversingFilingReranker`, test sources only).
    * Methods
        * rerank(String query, List<RetrievedFilingChunk> candidates, int topK)
            * Return a ranked subset of the supplied candidate records.
            * Preserve original text, citation metadata, and similarityScore (cosine similarity from the fused list; the reranker's own score is never written into it, and a changed score fails validation).
            * Receives at most max(`rerank-candidates`, topK) records and must return at most topK of them.
        * version()
            * A short identifier of the exact model, recorded as `properties.rerankerVersion` in evaluation snapshots; the default is null.
        * scoring()
            * A short description of how a passage is scored, recorded as `properties.rerankerScoring` in evaluation snapshots so snapshots from different scoring modes are distinguishable (for the cross-encoder `head` or `max-window/overlap=64/maxWindows=4`); the default is null. Read through `FilingRetrievalService.rerankerScoring()`.
    * Configuration
        * Reranking is off by default (`rag.retrieval.reranking-enabled: false`). Measured twice on 2026-09-13 (Cross-encoder reranker, Reranker measurement: head scoring, snapshots 248 to 250, then windowed scoring, snapshots 296 to 299): no configuration met the selection rule, so it stays off.
        * Enabling reranking without a FilingReranker bean fails application startup clearly.
        * `rerank: true` on a request (or `?rerank=true` on evaluate) with no FilingReranker bean is an HTTP 400, not a silent skip.
        * A reranker RuntimeException, timeout, or invalid result is never reported as successful reranking: retrieval falls back to the fused order with the non-reranked strategy and a WARN naming the exception class only; an `Error` propagates (FilingRetrievalService above).
        * The reranker's name recorded in evaluation snapshots is the simple name of its user class, so a Spring CGLIB proxy suffix never appears (`FilingRetrievalService.rerankerName()`, via `ClassUtils.getUserClass`); its version is `FilingRetrievalService.rerankerVersion()`.
* Cross-encoder reranker (RAG-1)
    * What it is
        * `cross-encoder/ms-marco-MiniLM-L-6-v2` (a MiniLM cross-encoder trained on MS MARCO web-search passage ranking; about 22 million parameters) run on the CPU through ONNX Runtime for Java, with the model's Hugging Face tokenizer. Chosen by Jay on 2026-09-13 (plan `plans/2026-09-13-reranker.md`, Option A).
        * It reads the question and one chunk together and emits one relevance logit per pair; a higher logit ranks higher. No model tokens, no provider call, no rate limit. Its training domain is general web search, so its fit on filings was measured separately: see Reranker measurement below (2026-09-13, no configuration qualified under head scoring or under windowed scoring).
        * No instruction channel: the model has no prompt and produces only a number per pair, so text inside a chunk cannot direct it to do anything; at worst a chunk can score itself higher, and the result is still only a reorder of chunks retrieval already selected, checked by validateRerankedEvidence.
        * Its window is 512 tokens including the question and three special tokens, so a chunk keeps about 480 tokens beside a short question, and 65% of the stored chunks are longer than 469 tokens, the passage budget beside a 40-token question (Windows below). Since 2026-09-13 a chunk longer than its window is scored as the maximum logit over sliding windows that together cover it (`passage-scoring: max-window`, the default; `head` restores the first-window-only behaviour of the 2026-09-13 measurement). A chunk that fits one window is scored identically under both.
    * Classes
        * PairScorer: `float[] score(String query, List<String> passages)`, one score per passage; separates scoring from ordering so ordering is unit-tested without the model. AutoCloseable.
        * OnnxCrossEncoderScorer: loads the ONNX session (optimisation level ALL_OPT) and the tokenizer from local files. Tokenizes the query and each chunk separately (no special tokens, truncation and padding off), then has CrossEncoderPairAssembler build each pair query first and chunk second (`[CLS] query [SEP] chunk [SEP]`), cut to `max-length` (512) tokens longest first (Input bounds below), one row per window of the chunk under `max-window` (Windows below; one row per chunk under `head`), and takes the maximum logit over a chunk's rows as its score. Chunks are tokenized `batch-size` at a time, and each group's rows run as one or more ONNX Runtime calls of at most eight 512-token pairs' attention size, each call padded to its longest row, as int64 tensors of shape [rows, sequence] (Latency below). `scoreWithWindows` returns the scores with the rows scored, and `scoring()` names the mode. Observed model metadata (2026-09-13): inputs `input_ids`, `attention_mask`, `token_type_ids`, each INT64 [batch_size, sequence_length]; output `logits`, FLOAT [batch_size, 1]. A model declaring no `token_type_ids` is fed the other two; a model without `input_ids` and `attention_mask` fails startup. close() waits up to 30 s for scoring calls in flight (including one abandoned on timeout) before releasing the native session and tokenizer, so shutdown never frees a session under a running inference (Shutdown below for a call that outlasts the wait).
        * CrossEncoderPairAssembler (no DJL or ONNX Runtime import): reads the pair template and pad id from `tokenizer.json`, applies the longest-first cut, computes each chunk's windows (`windowLength`, `windowStarts`, `windows`; Windows below), lays out ids, `token_type_ids`, and `attention_mask` per window, pads, and splits a group's rows into ONNX Runtime calls (Input bounds and Latency below).
        * CrossEncoderInputBounds: the Java-side character bound on every query and chunk (Input bounds below).
        * DjlRuntimeDefaults: disables DJL's telemetry call before any DJL class is initialised (Network below).
        * CrossEncoderReranker implements FilingReranker: scores every candidate's content (null content as empty text), orders by score descending with ties kept in the input (fused) order, returns the first topK as the same record instances. A scorer exception, a score count not matching the candidates, or a NaN score is thrown as a RuntimeException, so retrieval falls back. Logs `Cross-encoder scoring completed: candidates, windows, topK, elapsedMs` per call, `windows` being the model rows the scorer ran (equal to `candidates` under `head`, more under `max-window`); `scoring()` is the scorer's.
        * CrossEncoderModelFiles: verifies both files exist and the model's SHA-256 equals `model-sha256` (and the tokenizer's equals `tokenizer-sha256` when set) before any native library loads; `version()` is the first 12 hex characters of the model's SHA-256.
        * CrossEncoderConfiguration (`@ConditionalOnProperty rag.retrieval.cross-encoder.enabled=true`): beans CrossEncoderModelFiles, PairScorer (destroy method close), FilingReranker. Its bean methods return project types only, so with the property off no ONNX Runtime or DJL class is loaded (asserted by CrossEncoderConfigurationTests from the JVM's class list).
    * Files (not in git)
        * `models/cross-encoder-ms-marco-MiniLM-L-6-v2/model.onnx` (91,011,230 bytes, SHA-256 `5d3e70fd0c9ff14b9b5169a51e957b7a9c74897afd0a35ce4bd318150c1d4d4a`) and `tokenizer.json` (711,396 bytes, SHA-256 `d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66`), downloaded 2026-09-13 from `https://huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2/resolve/main/onnx/model.onnx` and `.../resolve/main/tokenizer.json`. `models/` is in `.gitignore`. Record: `live-runs/2026-09-13-reranker/downloads.md`.
        * To recreate on another machine: download the same two URLs into that directory and check `shasum -a 256` against the values above; startup refuses a different model file.
    * Dependencies
        * `com.microsoft.onnxruntime:onnxruntime` 1.29.0 and `ai.djl.huggingface:tokenizers` 0.38.0 (brings `ai.djl:api`, gson, JNA) from Maven Central. Both jars bundle their native libraries, macOS arm64 included (`ai/onnxruntime/native/osx-aarch64/`, `native/lib/osx-aarch64/cpu/libtokenizers.dylib`).
        * No runtime download: DJL extracts the bundled tokenizer library to `~/.djl.ai/tokenizers/<version>-cpu-<platform>/` on first load. DJL would download a CUDA build from its own CDN when it detects CUDA, so OnnxCrossEncoderScorer pins `RUST_FLAVOR` to `cpu` (unless already set) and fails startup if the jar has no library for the platform instead of letting a download happen; an explicit `RUST_LIBRARY_PATH` skips that check. The tokenizer is loaded from the file path only, never by Hugging Face hub name.
    * Input bounds (remediation 2026-09-13)
        * The problem: the tokenizer's native layer turns any tokenizer error into a Rust panic that aborts the whole JVM (exit code 134, `called Result::unwrap() on an Err value: SequenceTooShort ... fatal runtime error ... aborting`); no Java exception is thrown, so retrieval's fallback never runs. Milestone 2 used `only_second` truncation, which fails when the query alone fills the window: at 512 tokens a query of 509 or more wordpieces leaves no chunk tokens to remove. `RetrievalRequest.query` and the model's `searchFilings` tool both allow 4,000 characters, so `POST /api/rag/retrieve` with `"rerank": true` and about 600 short words killed the application. Reproduced with the real tokenizer (508 words scored, 509 aborted): `live-runs/2026-09-13-reranker/live-test-only-second-abort.log`.
        * The first fix (remediation 1, since replaced by the second fix below): native `longest_first` truncation (the strategy the reference sentence-transformers CrossEncoder uses) plus a Java character bound, not a Java-side query cap in tokens. For a pair, `longest_first` computes two target lengths from the two sequence lengths (the shorter sequence keeps its length up to half the content budget, the longer takes the rest, halving both when both are long) and cuts each sequence to its target; that arithmetic has no error branch, whereas `only_second` returns `SequenceTooShort` whenever the chunk has no more tokens than must be removed. A token cap on the query would need a second encode, a decode or string cut, and a re-encode that is not guaranteed to produce the same count (a word cut mid-wordpiece re-tokenizes longer), so it could not by itself guarantee the chunk has enough tokens. Evidence for the choice: DJL's `HuggingFaceTokenizer$Builder.optTruncation(true)` stores `"true"`, which `HuggingFaceTokenizer$TruncationStrategy.fromValue` maps to `LONGEST_FIRST`, passed by name to the native `TokenizersLibrary.setTruncation` (javap, tokenizers 0.38.0); the bundled native library is Hugging Face tokenizers 0.21.0 (cache directory `0.21.0-0.38.0-cpu-osx-aarch64`) and its strings contain `SequenceTooShort`, `SecondSequenceNotProvided`, `LongestFirst`, `OnlyFirst`, `OnlySecond`. The Rust source of `truncate_encodings` is not in this repository or the local Maven or Cargo caches and was not downloaded; the no-error property of `longest_first` rests on that upstream code as recalled plus the child-JVM sweep below, which covers every boundary (query 0, 1, 253, 254, 255, 256, 508, 509, 510, 1,000 words against chunks of 0, 1, 254, 255, 509, 510 words and 2,000 characters) without an abort. `SecondSequenceNotProvided` cannot occur because every call is a pair, and `max-length` is validated to 16 or more, so the content budget is never below 13.
        * The second problem (Scrutiny after remediation 1): native pair truncation builds overflow encodings for every combination of query pieces and chunk pieces (about 2 x (query tokens / half the budget) x (chunk tokens / half the budget) encodings per pair) although nothing reads them, and holds every pair of a batch until the batch finishes. Memory therefore grew with the product of the two lengths, and an OS memory kill (exit 137) bypasses the fallback exactly like a panic. Measured by Scrutiny through the production scorer on the 16 GB development Mac: max-length 16, batch 20, a 4,000-character CJK query with 20 chunks, 41 GB footprint in 44 s; max-length 16, batch 64, 40 CJK chunks of 4,000 characters, killed (exit 137); max-length 16, batch 64, 20,000-character inputs, killed after 66 GB; at the defaults (512, batch 20) a 20,000-character query with 20 chunks of 20,000 characters, 7.9 GB. The earlier Shutdown and Latency claim (at most ceil(candidates / batch-size) batches, worst batch 542 to 882 ms) was therefore false.
        * The second fix (remediation 2, current): the native tokenizer never encodes a pair. `OnnxCrossEncoderScorer.singleSequenceTokenizer` builds the tokenizer with `optAddSpecialTokens(false)`, `optWithOverflowingTokens(false)`, `optTruncation(false)` and `optPadding(false)`; DJL maps `"false"` to `DO_NOT_TRUNCATE` (native `disableTruncation`) and `DO_NOT_PAD` (native `disablePadding`), and construction fails unless `getTruncation()` and `getPadding()` report exactly those (DJL 0.38.0 sources jar). With truncation off the native encoding has no overflow to build: `CrossEncoderSeparateTokenizationLiveTests` encodes 20,000-character texts with overflow requested and gets `getOverflowing()` empty, `exceedMaxLength()` false, all 10,000 or 20,000 tokens, no id equal to 101, 102 or 0, and an all-zero special-token mask. The query is tokenized once per call and each chunk once; CrossEncoderPairAssembler then works on the id arrays, so work per pair is linear in the input length.
        * Longest-first cut, in Java (`CrossEncoderPairAssembler.keptLengths`): with budget = `max-length` - 3, a pair that fits is kept whole; otherwise one token at a time is removed from the end of the longer sequence until it fits, and on a tie the query loses the token unless the query was the longer sequence before any removal, when the chunk does. Closed form: a shorter side of at most half the budget is kept whole and the longer keeps the rest; otherwise the two keep floor(budget / 2) and ceil(budget / 2), the ceiling going to the query only when the query started longer (at 512: 254 and 255). CrossEncoderPairAssemblerTests checks the closed form against the literal one-token-at-a-time rule for every length pair from 0 to 3 x `max-length` at five window sizes.
        * Layout, read from `tokenizer.json` (not hard-coded): the `post_processor` pair template must be special, A, special, B, special with one id each (here `[CLS]` 101, `[SEP]` 102, `[SEP]` 102) and types A, A, B (here 0, 0, 1); `token_type_ids` are 0 through the first `[SEP]` inclusive and 1 after; `attention_mask` is 1 for every real token; a call's rows are padded to its longest pair with the pad id (`padding.pad_id` when declared, else the `[PAD]` added token, here 0), type 0 and mask 0. Any other template shape fails construction naming the path. A chunk or query containing the literal text `[SEP]` gets id 102 for it, as the native pair encoding did (added tokens are matched in text either way).
        * Parity with the replaced native pair encoding (`CrossEncoderParityProbe` in a child JVM with -Xmx1g, the reference tokenizer used only there): 320 generated pairs that need no truncation (English and digits only, CJK only, accents, punctuation and emoji only, or mixed; empty sides; two exactly full 509-token pairs; longest pair 512 tokens) gave identical `input_ids`, `token_type_ids` and `attention_mask`, zero mismatches, alone and as rows of padded batches of 20. Truncating pairs at 512 with each side 300 to 1,000 tokens: 60 of 60 identical (29 query longer, 26 chunk longer, 5 equal), so the logit difference on the 50-pair sample is 0.0 maximum and 0.0 median; truncating pairs where one side has at most half the budget: 100 of 100 identical. The three handwritten live pairs and all 18 boundary and sweep rows (8 cases, 10 sweeps) of the forked length test score bit-identically to remediation 1 (11.371214, 5.7503047, 5.5065556; for example words509 -6.8142157). The rule was designed from Hugging Face tokenizers' `longest_first` target-length arithmetic as recalled; the equality rests on these measured samples, not on reading the Rust source.
        * Java character bound (CrossEncoderInputBounds): null becomes empty text; a query or chunk longer than 20,000 UTF-16 units is cut to 20,000 (never between the two halves of a surrogate pair); an unpaired surrogate (possible from a JSON escape) is replaced with U+FFFD, so the native side only receives well-formed Unicode. Why 20,000: untruncated single-text tokenization is linear, about 25 ms for 20,000 CJK characters (20,000 tokens) and 10 ms for 20,000 characters of `"a "`, so a call with a query and 40 bounded chunks spends about 1 s tokenizing (the max-length 16 rows under Latency are almost all tokenization); and it is five times the 4,000-character query limit and ten times a 2,000-character chunk, so ordinary text reaches its first 512 tokens long before the bound (only text made mostly of characters that yield no token, such as whitespace or combining marks, can be cut earlier). Unchanged since remediation 1.
        * Effect on scores: a query is cut only when both it and the chunk are long; at 512 a cut query keeps 254 or 255 tokens. Questions far below that score exactly as before (both remediation live runs reproduced Milestone 2's logits bit for bit). A cut is logged at INFO with lengths only: `Cross-encoder query truncated: queryChars, queryCharsKept, queryTokens, queryTokensKept` (queryTokens is now the exact untruncated count of the bounded query; remediation 1 printed at most `max-length` with a `+`). Since windowed scoring a chunk longer than its window is no longer cut to its head: every token of it is inside one of its windows (Windows below), while the query is still cut exactly as here.
    * Windows (2026-09-13, plan `plans/2026-09-13-reranker-windows.md`, Milestone 1)
        * Why: the 2026-09-13 measurement (Reranker measurement below) found no qualifying configuration, and the diagnosis with the real tokenizer and model against every stored chunk (`live-runs/2026-09-13-reranker-windows/truncation-probe-512.txt`) found the cause: the chunker cuts at 4,000 characters and stores `token_count` as characters / 4 (median 780); exact WordPiece lengths on filing text are somewhat lower but still well past the window (median 647, 90th percentile 849, longest 1,214 tokens), so 371 of 569 chunks (65%) are longer than 469 tokens, the probe's passage budget beside a 40-token question (the evaluation questions' own budgets are 473 to 500), and at that budget the model saw 78% of a chunk on average. For 11 of the 39 questions with a matched chunk the expected phrase starts past the kept tokens. Every kind-FIGURE question the head measurement pushed out of the top 5 was such a question (nvda-11: answer at token 596 of 854, 477 kept; nvda-14: 820 of 996, 477 kept; msft-12: 544 of 605, 490 kept; msft-04: 606 of 693, 480 kept) or had its phrase at the very end of the kept tokens (nvda-01: token 485, 489 kept); the one other question it pushed out, msft-05 (NARRATIVE, out at 20 and 40 candidates), has its answer at token 121, visible to the head cut. Rescoring the same question on the chunk's text from 200 characters before the phrase flipped the logit (nvda-11 2.6 to 5.3, nvda-14 -0.3 to 4.8, msft-12 -1.8 to 5.1, msft-10 -9.5 to 9.9). Corrected 2026-09-13 after Milestone 2 Scrutiny: this bullet first said, as the plan's Why section did, that exact WordPiece lengths are higher than the stored estimate (the probe shows the reverse, estimate median 780 and exact median 647, and the database confirms the 780), gave nvda-11's chunk as 820 tokens (the probe counts 854), set the 65% against 475 to 495 tokens (the probe counted it against 469), and called every top-5 loss of the head measurement a truncation case, which msft-05 is not.
        * Arithmetic (`CrossEncoderPairAssembler`, pure Java on the separately tokenized ids; no re-tokenization, no re-chunking, no re-embedding): budget = `max-length` - 3; the query keeps exactly what the longest-first rule gives it against the whole chunk (never cut more than before); the window length W = budget - query tokens kept (`windowLength`; 484 for a 25-token question at 512, 255 when both sides are long). A chunk of at most W tokens (an empty one included) is one window, its whole self, and its tensors equal the head cut's bit for bit. A longer chunk yields windows that start at 0 and advance by W - `window-overlap-tokens` while a window does not reach the last token; the final window starts at length - W, so it ends exactly at the last token as a full W tokens (with overlap 0 the windows are disjoint except that final one, which shares tokens with the previous unless W divides the length); at most `max-windows` windows, the first ones, so a chunk longer than they cover is scored on its head only (`windowStarts`). Each window is one model row: the first query-kept tokens, `[SEP]`, the window's tokens, `[SEP]`, typed and masked as before. With the defaults (overlap 64, at most 4) a 25-token question gives windows of 484 advancing by 420: a 1,214-token chunk takes three (0, 420, 730), 4 windows cover chunks up to about 1,740 tokens, and the longest stored chunk needs 3. An overlap of W or more still advances one token per window.
        * Modes (`passage-scoring`): `max-window` (default) scores a chunk as the maximum logit over its rows (`OnnxCrossEncoderScorer.scoreWithWindows`, `-infinity` before the first row, so a NaN logit still surfaces as NaN and fails the call); `head` scores the first window only, which is exactly the pre-window row, so `head` reproduces the 2026-09-13 measurement's scores (CrossEncoderRerankerLiveTests, CrossEncoderLengthProbe and CrossEncoderResourceProbe construct the scorer with `HEAD` and pass unchanged; CrossEncoderSeparateTokenizationLiveTests never constructs it).
        * Bounds: all windows of a group flow through the same per-call attention cap as rows (`MAX_ATTENTION_CELLS_PER_RUN`, eight 512-token rows per ONNX Runtime call), so windowing adds rows, never wider ones, and peak memory per call is unchanged; the character bound admits at most 20,000 tokens per chunk, which yields at most `max-windows` windows, so the work per call is at most `max-windows` times the head figure. Ties keep the fused order (unchanged comparator); identical input forms identical windows, calls, and scores.
        * Position sensitivity (observed on the handwritten live chunk, `live-runs/2026-09-13-reranker-windows/live-test-windows.log`, `positionSweep`): the model's logit for the same answering sentence depends strongly on how deep it sits in unrelated prose within a window (on synthetic risk-factor filler, as logged: +10.29 at the start of the window, +5.91 at token 141, -11.09 at token 213, -7.10 at 257, +2.57 at 340, not monotonically), so a window that merely contains the answer late in it can still score low. Windowing guarantees every token is seen, not that the answer sits near a window start; the measured effect on the evaluation set is in Reranker measurement below (Windowed rows, 2026-09-13): nvda-11's answer sits 219 tokens into its second window under the default overlap and its rank recovers to 5, not 1, and the 224-token overlap row of plan Amendment 1 lifted one further question into the top 5 (nvda-09) and no other.
    * Network
        * DJL 0.38.0's `ai.djl.util.Ec2Utils.callHome(String)` is called by every `HuggingFaceTokenizer.newInstance`, so by `HuggingFaceTokenizer.Builder.build()`. At most once a day per JVM it sends a PUT and GETs to the EC2 metadata endpoint `http://169.254.169.254` (1 s timeout) and, on an AWS host, a GET to `https://djl-telemetry-<region>.s3.<region>.amazonaws.com/telemetry.txt?instance-id=...`. Its connections are opened with `Proxy.NO_PROXY`, so proxy settings neither see nor block them. It returns before connecting when `Utils.isOfflineMode()` is true (environment `DJL_OFFLINE`, else system property `ai.djl.offline`) or when `Boolean.parseBoolean(Utils.getEnvOrSystemProperty("OPT_OUT_TRACKING"))` is true (environment variable first, then system property). Verified with javap on `ai.djl:api:0.38.0` and `ai.djl.huggingface:tokenizers:0.38.0`.
        * DjlRuntimeDefaults, run from OnnxCrossEncoderScorer's static initializer (before any DJL class is initialised), sets the system properties `OPT_OUT_TRACKING=true` and `ai.djl.offline=true`, each only when neither its environment variable nor its property is present; an existing value is kept, and when neither leaves the call disabled it logs `DJL telemetry is not disabled (...)` at WARN. `ai.djl.offline` also makes DJL's own URL helper (`Utils.openUrl`) and its model-zoo and repository download paths refuse to connect; it changes nothing on the tokenizer's local-file and bundled-library paths (its only other use there, in `LibUtils`, picks the `cpu` flavor, which is pinned anyway).
        * JVM-wide effects, only when `rag.retrieval.cross-encoder.enabled` is true (the scorer class is never loaded otherwise): the system properties `RUST_FLAVOR=cpu` (DJL tokenizer native flavor), `OPT_OUT_TRACKING=true`, and `ai.djl.offline=true` apply to the whole JVM, so any other DJL use in the same process would also be CPU-only, untracked, and offline. Nothing else in this application uses DJL. Set any of them (environment or `-D`) before startup to choose otherwise.
        * What was observed: the child-JVM live test samples the child's TCP and UDP sockets with `lsof -nP -a -p <pid> -iTCP -iUDP` for its whole life (tokenizer build, all scoring, close) with no system or firewall change; 368 and 371 samples in two runs each saw exactly one socket, the loopback control connection the probe opens on purpose so the sampler is shown to work (`live-test-forked-lengths.log`). Samples are taken back to back, one lsof call each, so a connection shorter than one lsof call can be missed, so the claim that the call is disabled rests on the code inspection above, supported by that observation. Milestone 2's run with HTTP(S) proxies pointed at a closed port proved only that no proxied HTTP(S) download happened (the bundled library was extracted, not downloaded); it could not see the metadata call, which bypasses proxies.
    * Shutdown
        * close() takes the write side of a read-write lock that every scoring call holds, waiting at most 30 s. If a call is still running after 30 s (for example an inference a timed-out retrieval abandoned), close logs `Cross-encoder scoring still in flight after 30 s; leaving the native session open` at WARN and returns without freeing the session or tokenizer. The JVM then exits with them still allocated; an inference still running natively while the process tears down can crash at exit, which an operator sees as that WARN followed by a native crash report or a non-zero exit instead of a clean shutdown. Freeing the session under the running inference instead crashed the JVM in an early Milestone 2 run, which is why close does not.
        * Unlikely with separate tokenization and bounded ONNX Runtime calls (remediation 2). Remediation 1 claimed here that a call was at most ceil(candidates / batch-size) batches taking at most 542 to 882 ms; that was false, because native pair truncation cost grew with the product of query and chunk lengths (Input bounds). Measured in child JVMs under `/usr/bin/time -l` over a 16-case grid (max-length 16 and 512, batch-size 20 and 64, a query and 40 chunks of 4,000 and 20,000 characters, `"a "` repeated and CJK): the slowest call took 2,105 ms (max-length 512, batch-size 64, CJK, 20,000 characters, 977 MB peak process footprint), the largest footprint in that grid was 987 MB (batch-size 20, `"a "`, 20,000 characters), and at max-length 16 no call exceeded 1,107 ms or 372 MB (Latency). That grid is not every extreme. The independent Scrutiny probes on 2026-09-13 measured 45 single-thread cases with one scorer loaded, including the shape the per-call cap makes worst (many short pairs in one call): the largest single-call footprint was 1,008 MiB (1.06 GB), 64 chunks of 181-token pairs at batch-size 64; with 40 chunks, the most retrieval sends, the largest was 966 MiB (max-length 512, batch-size 20, CJK, 20,000 characters); at max-length 16 the largest was 376 MiB and the slowest call 1,028 ms. The slowest single-thread call was 2,119 ms (max-length 512, batch-size 64, CJK, 20,000 characters); with two calls running at once a call took up to 3,142 ms. Probes that loaded the scorer several times in one JVM (for example the batch-alignment probe, 12 loads, 1,291 MiB) are excluded, because the application loads it once. Per-call memory is therefore about 1 GB, from these measurements rather than a proof (evidence: `live-runs/2026-09-13-reranker/scrutiny-probes/`, one log per case with the raw `/usr/bin/time -l` peak memory footprint in bytes; figures are MiB, and GB means 10^9 bytes). A 30 s wait is therefore about 14 worst-case calls queued behind one another on the CPU, or a stalled or heavily oversubscribed machine.
    * Properties (`rag.retrieval.cross-encoder`)
        * enabled: false (`RAG_CROSS_ENCODER_ENABLED`). True creates the reranker bean, verifies the files, and loads the model at startup (about 1 s plus hashing the 91 MB file). It does not turn reranking on: `rag.retrieval.reranking-enabled` stays the default for whether retrieval uses the reranker, and a request's `rerank` field overrides per call. With it false, `rerank: true` is Milestone 1's HTTP 400.
        * model-path: `models/cross-encoder-ms-marco-MiniLM-L-6-v2/model.onnx`; tokenizer-path: `models/cross-encoder-ms-marco-MiniLM-L-6-v2/tokenizer.json` (relative paths resolve against the working directory, so run from the project root).
        * model-sha256: the checksum above (required when enabled); tokenizer-sha256: the checksum above (checked when set).
        * max-length: 512 (16 to 512), special tokens included. batch-size: 20 (1 to 64), chunks tokenized and scored together; since remediation 2 a group of long pairs runs in several ONNX Runtime calls of at most `rows x width x width` = 8 x 512 x 512 (Latency), so a call never holds more attention than that cap allows. Batch-size still affects peak memory: batch-size 1 was the lowest in every comparison that included it, and between 20 and 64 the direction depends on the input. For pairs near the 512-token width, 64 was similar or slightly lower (880 against 856 MiB for 40 chunks of 4,000 `"a "` characters); for shorter pairs, which the cap packs many to a call, 64 was much higher (469 against 1,008 MiB for 64 chunks of 181-token pairs; 593 against 908 MiB for 40 chunks of 228-token pairs). At max-length 512, batch-size 1 used 266 to 324 MiB across the probed inputs (evidence: `live-runs/2026-09-13-reranker/scrutiny-probes/`, one log per case with the raw `/usr/bin/time -l` peak memory footprint in bytes; figures are MiB, and GB means 10^9 bytes).
        * passage-scoring: `max-window` (`RAG_CROSS_ENCODER_PASSAGE_SCORING`; `head` or `max-window`), how a chunk longer than one window is scored (Windows above). window-overlap-tokens: 64 (0 to 256), tokens shared by consecutive windows. max-windows: 4 (1 to 16), most windows scored per chunk, from its head. The three are validated at binding (CrossEncoderConfigurationTests) and reported together as `FilingReranker.scoring()` (`head`, or `max-window/overlap=64/maxWindows=4`) in the startup log line (`scoring=`) and in evaluation snapshots (`properties.rerankerScoring`).
        * Startup fails when enabled and a file is missing (`Cross-encoder file not found: <absolute path> (rag.retrieval.cross-encoder.model-path)`), a checksum differs (`Cross-encoder file checksum mismatch: <path> has SHA-256 <actual> but rag.retrieval.cross-encoder.model-sha256 is <expected>`), or `model-sha256` is blank.
    * Enabling (for a manual check; reranking stays off by default)
        * From the project root, with the files in place: `RAG_CROSS_ENCODER_ENABLED=true SERVER_PORT=8081 ./mvnw -q -o spring-boot:run`, then `POST /api/rag/retrieve` with `"rerank": true` returns strategy `HYBRID_RRF_RERANKED` (or `FILTERED_VECTOR_RERANKED` with hybrid off); without `rerank` it returns `HYBRID_RRF`. Without `RAG_CROSS_ENCODER_ENABLED`, `"rerank": true` returns HTTP 400.
        * To make every retrieval rerank, also set `rag.retrieval.reranking-enabled=true`. Not done: neither 2026-09-13 measurement found a qualifying configuration (Reranker measurement below). The nearest is windowed row 299, which fails only criterion 4 (msft-04 and nvda-01 leave the top 5); 296 fails all four criteria, 297 fails the NVDA criterion and criterion 4 on both questions, and 298 fails the NVDA criterion and criterion 4 on nvda-01 alone (msft-04 stays in its top 5).
    * Determinism
        * Same query and candidates give identical scores and order across calls (live test: two calls over 20 passages produced bit-identical float scores). Ties are broken by fused order, never by thread timing. Batches, windows, and their ONNX Runtime calls are formed from the input order and token lengths only, so identical input forms identical calls (windowed live test, 2026-09-13: two calls over 20 chunks of 1,009 tokens, 60 windows, bit-identical scores and order).
    * Latency
        * Live test on the development Mac (Apple silicon, CPU): 20 passages of about 2,000 characters in 299 to 354 ms per call after warm-up (two runs: 324 / 332 / 317 and 354 / 337 / 328; 299 / 300 / 314 and 328 / 319 / 354 after the remediation); 680 to 930 ms when a second inference overlaps (a timed-out call still finishing on the other pool thread). Model load 0.9 to 5.5 s in the test JVM. The default `rerank-timeout-ms` of 2000 covers an ordinary call with margin; the worst allowed inputs below come close to it or pass it (up to 2.1 s), and a call over the timeout falls back to the fused order. Those figures are head scoring (one row per chunk; the live classes that produced them construct the scorer with `HEAD`).
        * Windowed scoring (2026-09-13, `CrossEncoderWindowedScoringLiveTests`, same Mac, defaults `max-window` / overlap 64 / at most 4 windows, a 11-token question, 20 and 40 chunks of 1,009 tokens, three calls after a warm-up, one committed run): 20 chunks, 60 windows: 1,527 / 1,522 / 1,692 ms, against head 543 / 540 / 536 ms for the same chunks (20 rows); 40 chunks, 120 windows: 3,090 / 3,066 / 3,110 ms, against head 1,053 / 1,077 / 1,049 ms. So a call costs about the number of rows: three windows per 1,009-token chunk is about 2.9 times head, and 40 such chunks pass the 2,000 ms `rerank-timeout-ms` (a fallback to the fused order) while 20 stay under it; stored chunks are shorter (median 647 tokens, two windows), so a 20-candidate call on real chunks sits between the head figure and this one: measured on the evaluation set (Reranker measurement, Windowed rows) a 20-candidate call took a median 1,122 ms (43 windows), a 40-candidate call 2,139 ms (83.5 windows; 27 of the 42 evaluation calls took over the 2,000 ms timeout, 15 at or below 1,903 ms and 27 from 2,038 ms up, and so did that row's warm-up call, 2,403 ms; the row ran with `rerank-timeout-ms` 4,000 from the start, plan Amendment 1), and the 224-token overlap 1,323 ms (51 windows); those evaluation-set times are sequential calls from one client. Evidence: `live-runs/2026-09-13-reranker-windows/live-test-windows.log`; for the evaluation-set times `live-runs/2026-09-13-reranker-windows/measurement/latency-297.txt` to `latency-299.txt`.
        * Memory (remediation 2): ONNX Runtime attention dominates a call's memory, about 88 MB of peak footprint per 512-token row (before the split, a call of 40 rows of 512 tokens at batch-size 64 reached 3.9 GB, and calls of 20 such rows at batch-size 20 reached 2.1 GB). Each call is therefore limited to rows x longest-pair width squared <= 8 x 512 x 512 (`CrossEncoderPairAssembler.MAX_ATTENTION_CELLS_PER_RUN`): eight 512-token pairs, or up to 64 pairs of at most 181 tokens. Splitting changed no measured score (the live determinism scores and all forked boundary scores are bit-identical to remediation 1) and cost no time (a query and 40 chunks of 512 tokens took 2.0 to 2.2 s whether calls held 1, 10, 20 or 8 rows).
        * Worst cases, one child JVM each with -Xmx1g under `/usr/bin/time -l`, a query and 40 chunks of the same text, two calls per JVM (call 1 / call 2 ms; peak memory footprint of the whole process, model and JVM included), 2026-09-13: max-length 16: `"a "` 4,000 characters 113 / 104 ms and 286 MB (batch-size 20), 109 / 107 ms and 312 MB (64); `"a "` 20,000: 431 / 415 ms, 292 MB (20); 459 / 440 ms, 372 MB (64); CJK 4,000: 212 / 197 ms, 287 MB (20); 224 / 212 ms, 340 MB (64); CJK 20,000: 1,004 / 1,001 ms, 307 MB (20); 1,107 / 1,028 ms, 372 MB (64). max-length 512: `"a "` 4,000: 1,255 / 1,171 ms, 922 MB (20); 1,248 / 1,162 ms, 903 MB (64); `"a "` 20,000: 1,507 / 1,571 ms, 987 MB (20); 1,530 / 1,445 ms, 971 MB (64); CJK 4,000: 1,302 / 1,254 ms, 925 MB (20); 1,256 / 1,342 ms, 944 MB (64); CJK 20,000: 2,052 / 2,014 ms, 947 MB (20); 2,105 / 2,053 ms, 977 MB (64). Typical at the defaults (512, batch-size 20): a short question with 20 chunks of 2,000 characters 303 / 282 ms and 880 MB; with 40 chunks 630 / 630 ms and 884 MB. Every case exited 0. The same Finding 1 inputs before this fix: 41 GB, 66 GB and OS kills.
        * Concurrency: memory adds up per concurrent call. The retrieval service scores on a two-thread pool (`FilingRetrievalService.newRerankExecutor`, queue 16), so at most two calls run at once, including a call a timed-out request abandoned. Two simultaneous calls of the heaviest case (max-length 512, batch-size 64, a query and 40 chunks of 20,000 characters) peaked at 1,433 MB (CJK, 3,170 / 2,979 ms per round) and 1,379 MB (`"a "`, 2,366 / 2,247 ms). Those are not the largest two-call figures: the Scrutiny probes measured 1,340 MiB for two calls of that CJK case, 1,471 MiB over 40 rounds of two concurrent calls with random shapes, and 1,651 MiB (1.73 GB) for two calls of 64 chunks of 181-token pairs at batch-size 64, a shape retrieval cannot send because it passes at most 40 chunks. For the two calls the pool allows, measured peak memory is therefore about 1.7 GB (evidence: `live-runs/2026-09-13-reranker/scrutiny-probes/`, one log per case with the raw `/usr/bin/time -l` peak memory footprint in bytes; figures are MiB, and GB means 10^9 bytes). Calling the scorer directly from eight threads, which no application path does, reached 3.8 GB in the Scrutiny concurrency probe rerun. Evidence: `live-runs/2026-09-13-reranker/live-test-resource-grid.log`; the live test run repeating the two heaviest cases is in `live-test-remediation-2.log`.
    * Evaluation fields (Amendment 1)
        * Each question result records the `retrievalStrategy` retrieval reported for it (null on a retrieval error or on snapshots stored before 2026-09-13's reranker milestone 2).
        * Snapshot `properties` add `rerankerVersion` (first 12 hex characters of the model SHA-256, null with no reranker), `rerankedQuestions` (questions whose strategy ends `_RERANKED`), and `rerankFallbackQuestions` (when reranking resolved on for the run, questions retrieved without error whose strategy is not reranked: a timeout, a reranker failure, an invalid result, or no candidates; 0 when reranking resolved off). A run with any fallback is therefore visible in the API response and printed by `RetrievalEvaluationLiveTests` (`rerankedQuestions=`, `rerankFallbackQuestions=`, and a `strategies=` line grouping question ids by strategy). The snapshot's top-level `retrievalStrategy` is still the first question's.
        * Since windowed scoring (2026-09-13) snapshot `properties` also carry `rerankerScoring` (`FilingReranker.scoring()`: `head` or `max-window/overlap=64/maxWindows=4` for the cross-encoder; null when there is no reranker or it reports none, and absent from snapshots stored earlier, which read back unchanged), so a windowed run is distinguishable from the head-scored rows 248 to 250.
    * Tests
        * CrossEncoderRerankerTests (fake scorer): order, ties by fused order, topK, same record instances, scorer exception and malformed scores propagate, fallback through retrieval on a scorer failure and on a 100 ms timeout, reranker input max(rerank-candidates, topK), user-class name, version, and scoring; the completion log line reports the scorer's `windows=`.
        * CrossEncoderConfigurationTests: disabled creates no bean and loads no ONNX Runtime or DJL class; missing model or tokenizer, wrong checksum, and blank checksum each fail startup naming the path (temp files, never the real model).
        * CrossEncoderRerankerLiveTests (opt-in, `-Drag.rerank.live=true`, no database or Spring context): under `head` scoring (the pre-window rows), three handwritten queries each rank the answering passage first over two that do not; two calls give identical scores; latency for 20 passages of about 2,000 characters printed; a 1 ms timeout through FilingRetrievalService falls back to FILTERED_VECTOR and a 30 s timeout reranks.
        * CrossEncoderWindowedScoringLiveTests (opt-in, same flag, no database or Spring context; one scorer per mode with the defaults): a handwritten chunk of about 900 tokens whose answering sentence starts after token 600 scores higher under `max-window` than under `head` and ranks first of three candidates; a chunk with the answer inside its first 200 tokens scores the same under both modes within 1e-4, and a chunk that fits one window bit-identically; two calls give identical scores and order; the reranker logs at least 40 windows for 20 chunks of about 1,000 tokens (60) and exactly 20 under `head`; latency for 20 and 40 such chunks under both modes printed (Latency); an answer-position sweep printed (Windows).
        * CrossEncoderForkedLiveTests (opt-in, same flag): runs CrossEncoderLengthProbe in a child JVM (this JVM's classpath; environment without `OPT_OUT_TRACKING`, `DJL_OFFLINE`, `RUST_FLAVOR`, `RUST_LIBRARY_PATH`), so a native abort fails the test with the child's exit code instead of killing Maven. Through the production scorer the child scores queries of 508, 509 and 600 words, 4,000, 20,000 and 25,000 characters, a single 5,000-character word, and mixed Unicode with emoji (plus a lone surrogate and a NUL), each against a 2,000-character passage, then the query-by-chunk sweep (Input bounds) and a worst-case 20-pair batch. Asserts exit code 0, a finite score per passage, the truncation log for every cut case and not for the single word, the three system properties set by the scorer, and that lsof saw the control connection and no other socket. With `only_second` restored temporarily the same test failed with child exit code 134 while Maven kept running.
        * CrossEncoderInputBoundsTests (no model): null, the 20,000 bound, no split surrogate pair at the bound, unpaired surrogates replaced, NUL kept.
        * CrossEncoderPairAssemblerTests (no model): the longest-first closed form equals the one-token-at-a-time rule for every length pair up to 3 x `max-length` at max-length 16, 17, 20, 33 and 64, and each branch and tie at 512; layout, types, mask, padding and the fewest kept query tokens; ONNX Runtime call splitting; special and pad ids read from a `tokenizer.json` template (with and without a padding block); unsupported templates fail naming the path. Windows: `windowLength` per branch of the cut; `windowStarts` on named cases (fits, one token over, the 1,214-token chunk, overlap 0, overlap beyond W, the cap) and for every length up to 130 at windows 1 to 40 and overlaps 0 to 12 (start at 0, advance by W - overlap, cover every token, end at the last token, never more than max-windows, the first ones); a chunk within W yields the head tensors bit for bit under both modes and an empty chunk one empty row; a long chunk's rows hold its tokens in order; a cut query keeps its longest-first count in every window; 20 chunks of 1,000 tokens give 60 rows in calls of at most eight 512-token rows, and a 20,000-token chunk at most max-windows rows.
        * CrossEncoderSeparateTokenizationLiveTests (opt-in, same flag): the scorer's tokenizer reports `DO_NOT_TRUNCATE` and `DO_NOT_PAD` and returns no special-token id, an all-zero special-token mask, and no overflow encodings for texts up to 20,000 characters; CrossEncoderParityProbe in a child JVM (-Xmx1g) asserts at least 300 non-truncating pairs with zero mismatches against the native pair encoding and prints the truncating-pair counts and logit differences; CrossEncoderResourceProbe runs the two heaviest cases (max-length 512, batch-size 64, a query and 40 chunks of 20,000 characters, CJK and `"a "`) in child JVMs with -Xmx1g under `/usr/bin/time -l` and asserts exit 0 and a peak memory footprint under 2 GB (the footprint assertion is skipped with a message where `/usr/bin/time -l` reports none).
        * DjlRuntimeDefaultsTests (no model): both properties set when absent from environment and properties, never over an existing property or environment variable; in a child JVM with those variables removed, initialising OnnxCrossEncoderScorer (without constructing it, so no model or native library) sets both to true, and with `-DOPT_OUT_TRACKING=false -Dai.djl.offline=false` keeps them and logs the WARN.
    * Reranker measurement (2026-09-13, plan Milestone 3 with Amendment 2)
        * What was run: set v2 (42 questions), window 10, the current fusion defaults, `rag.retrieval.cross-encoder.enabled=true` in every start (`RAG_CROSS_ENCODER_ENABLED=true`), one application start per row, one warm-up `POST /api/rag/retrieve` with `"rerank": true` after each start, then `POST /api/rag/evaluate?rerank=false` (reference) or `?rerank=true` with `RAG_RETRIEVAL_RERANK_CANDIDATES` 10, 20, or 40 (each confirmed in the snapshot's `properties.rerankCandidates`). Every rerank row reports `rerankedQuestions` 42 and `rerankFallbackQuestions` 0, so all three are valid and none was rerun. No chat model; no tokens.
        * Reference: snapshot 247 reproduces snapshot 91 question by question (rank, matched chunk, and position of all 42 equal; hit@1/3/5, MRR, per-ticker hit@5, and misses equal; its slices add only the later `notInTop5` key).
        * Scoring time is the reranker's `Cross-encoder scoring completed ... elapsedMs` per question (42 calls per row, topK 10, warm-up excluded), on the development Mac's CPU. Every per-call line is committed as `latency-248.txt`, `latency-249.txt`, and `latency-250.txt` (and `latency-247.txt`, the reference's warm-up call only), extracted verbatim from each run's application log together with the line naming the stored snapshot id; the medians, maxima, and minima in this table and in `run.log` were recomputed from those lines and match. Figure top 5 is the figure slice (the 12 questions whose text carries a figure) in the top 5.

| Snapshot | rerank | Candidates | hit@1 | hit@3 | hit@5 | MRR | AAPL hit@5 | MSFT hit@5 | NVDA hit@5 | Non-figure hit@5 | Figure top 5 | Reranked / fallback | Scoring ms median / max |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 247 (reference) | false | 20 (unused) | 0.547619 | 0.714286 | 0.785714 | 0.655187 | 0.928571 | 0.785714 | 0.642857 | 0.700000 (21/30) | 12/12 | 0 / 0 | none |
| 248 | true | 10 | 0.500000 | 0.690476 | 0.761905 | 0.616988 | 1.000000 | 0.857143 | 0.428571 | 0.733333 (22/30) | 10/12 | 42 / 0 | 258.5 / 425 |
| 249 | true | 20 | 0.500000 | 0.690476 | 0.738095 | 0.607341 | 1.000000 | 0.785714 | 0.428571 | 0.733333 (22/30) | 9/12 | 42 / 0 | 519.5 / 629 |
| 250 | true | 40 | 0.523810 | 0.714286 | 0.761905 | 0.630584 | 1.000000 | 0.857143 | 0.428571 | 0.766667 (23/30) | 9/12 | 42 / 0 | 981 / 1103 |

        * Every row's `properties` carry `reranker` CrossEncoderReranker and `rerankerVersion` 5d3e70fd0c9f; the reference has `rerank` false and `retrievalStrategy` HYBRID_RRF on every question, the rerank rows `rerank` true and HYBRID_RRF_RERANKED on every question. A whole evaluation took 15.9 s (reference), 25.5 s, 36.8 s, and 56.7 s (10, 20, 40 candidates).
        * Rule, applied to each rerank row against 247: a row qualifies only if (1) aggregate hit@5 does not decrease, (2) non-figure hit@5 does not decrease, (3) no ticker's hit@5 decreases, and (4) none of the 22 kind-FIGURE questions in the top 5 under 247 leaves the top 5 ("FIGURE question" is the question's `kind`, as in the fusion-tuning rule, not the figure slice); among qualifying rows, highest MRR, then fewer candidates.
        * 248 (10 candidates) FAILS: (1) 32/42 below 33/42; (2) passes, 22/30 against 21/30; (3) NVDA 6/14 below 9/14 (AAPL 14/14 and MSFT 12/14 pass); (4) msft-04 4 to 10, nvda-01 3 to 9, nvda-11 1 to 7, nvda-14 1 to 7.
        * 249 (20 candidates) FAILS: (1) 31/42 below 33/42; (2) passes, 22/30; (3) NVDA 6/14 below 9/14 (AAPL 14/14, MSFT 11/14 pass); (4) msft-04 4 to no match, msft-12 2 to 6, nvda-01 3 to no match, nvda-11 1 to 10, nvda-14 1 to no match.
        * 250 (40 candidates) FAILS: (1) 32/42 below 33/42; (2) passes, 23/30; (3) NVDA 6/14 below 9/14 (AAPL 14/14, MSFT 12/14 pass); (4) msft-12 2 to 6, nvda-01 3 to no match, nvda-11 1 to 10, nvda-14 1 to no match.
        * Outcome: no row qualifies. Every configuration loses one or two aggregate top-5 hits (33/42 to 32/42, 31/42, 32/42), costs NVDA three of its nine top-5 hits (nvda-01, nvda-11, nvda-14 in every row), and lowers the figure slice in the top 5 from 12/12 to 10/12 at 10 candidates (nvda-11, nvda-14 out) and 9/12 at 20 and 40 (msft-12, nvda-11, nvda-14 out).
        * The non-figure slice shows a net change of plus one or two questions per configuration, from a single run of each, with questions moving in both directions: at 10 candidates aapl-09, msft-01, and msft-07 entered the top 5 and msft-04 and nvda-01 left it (21/30 to 22/30); at 20 candidates aapl-09, msft-01, msft-07, and msft-08 entered and msft-04, msft-05, and nvda-01 left (22/30); at 40 candidates the same four entered and msft-05 and nvda-01 left (23/30). A net difference of one or two of 30 questions in one run per configuration is thin evidence of any non-figure effect, and it does not offset the losses.
        * Per ticker, top-5 hits against 247 (13/14 AAPL, 11/14 MSFT, 9/14 NVDA): at 10 candidates AAPL 14/14 (aapl-09 in), MSFT 12/14 (msft-01 and msft-07 in, msft-04 out), NVDA 6/14; at 20 candidates AAPL 14/14 (aapl-09 in), MSFT unchanged at 11/14 (msft-01, msft-07, and msft-08 in, msft-04, msft-05, and msft-12 out), NVDA 6/14; at 40 candidates AAPL 14/14 (aapl-09 in), MSFT 12/14 (msft-01, msft-07, and msft-08 in, msft-05 and msft-12 out), NVDA 6/14. NVDA gained no question at any setting.
        * Per-question changes, reference 247 against the best-MRR rerank row 250 (no row qualified), rank (matched chunk); "no match" is no match in the 10-chunk window:

| Question | Kind | Slice | 247 | 250 | Top 5 |
|---|---|---|---|---|---|
| aapl-02 | FIGURE | non-figure | 1 (241) | 2 (227) | |
| aapl-03 | NARRATIVE | non-figure | 2 (226) | 1 (226) | |
| aapl-05 | NARRATIVE | non-figure | 1 (190) | 2 (246) | |
| aapl-06 | FIGURE | non-figure | 2 (269) | 1 (269) | |
| aapl-09 | FIGURE | non-figure | 8 (280) | 5 (280) | gained |
| aapl-11 | FIGURE | figure | 1 (225) | 1 (226) | |
| msft-01 | FIGURE | non-figure | 6 (514) | 2 (573) | gained |
| msft-02 | FIGURE | non-figure | 2 (514) | 1 (514) | |
| msft-03 | NARRATIVE | non-figure | 2 (517) | 1 (517) | |
| msft-04 | FIGURE | non-figure | 4 (466) | 1 (467) | |
| msft-05 | NARRATIVE | non-figure | 2 (571) | 8 (460) | lost |
| msft-07 | NARRATIVE | non-figure | 7 (495) | 3 (680) | gained |
| msft-08 | FIGURE | non-figure | no match | 2 (547) | gained |
| msft-10 | FIGURE | non-figure | 1 (637) | 1 (635) | |
| msft-11 | FIGURE | figure | 1 (519) | 3 (519) | |
| msft-12 | FIGURE | figure | 2 (519) | 6 (519) | lost |
| msft-13 | FIGURE | figure | 1 (523) | 1 (524) | |
| msft-14 | FIGURE | figure | 1 (644) | 1 (646) | |
| nvda-01 | FIGURE | non-figure | 3 (805) | no match | lost |
| nvda-03 | FIGURE | non-figure | 1 (797) | 2 (850) | |
| nvda-05 | NARRATIVE | non-figure | 10 (749) | no match | |
| nvda-07 | NARRATIVE | non-figure | no match | 7 (770) | |
| nvda-08 | FIGURE | non-figure | 5 (852) | 1 (852) | |
| nvda-10 | NARRATIVE | non-figure | 1 (895) | 1 (878) | |
| nvda-11 | FIGURE | figure | 1 (805) | 10 (805) | lost |
| nvda-12 | FIGURE | figure | 5 (802) | 3 (802) | |
| nvda-13 | FIGURE | figure | 1 (880) | 4 (880) | |
| nvda-14 | FIGURE | figure | 1 (872) | no match | lost |

        * The other 14 questions keep rank and chunk. Top-5 balance under 250: the non-figure slice gains four (aapl-09, msft-01, msft-07, msft-08) and loses two (msft-05, nvda-01), 21 to 23 of 30; the figure slice loses three (msft-12, nvda-11, nvda-14), 12 to 9 of 12; AAPL gains one (13 to 14), MSFT gains three and loses two (11 to 12), NVDA loses three (9 to 6). Aggregate 33 to 32. Rows 248 and 249 differ from 250 as listed in the rule bullets and `rule-table.txt` (for example msft-04 falls out of the top 5 at 10 and 20 candidates but rises to 1 at 40, and msft-08 is still no match at 10).
        * The five long-standing misses: nvda-02, nvda-04, and nvda-09 are no match in all four rows; nvda-07 moves from no match to 6 (20 candidates) and 7 (40), still outside the top 5; msft-08 moves from no match to 2 (chunk 547) at 20 and 40 candidates and stays no match at 10. For nvda-02 the expected passage is only in chunk 802, which is not among the first 20 fused candidates (`POST /api/rag/retrieve` with topK 20 and `"rerank": false`), so at 10 or 20 candidates the reranker never sees it, and at 40 it is not in the reranked top 10; whether it lies in fused positions 21 to 40 was not determined. The plan's premise that a neighbouring chunk merely outranks the answer does not hold for nvda-02 under the current fusion. The same chunk 802 answers nvda-12, which reranking lifted from 5 to 2 or 3.
        * Retrieval check for nvda-02 (cross-encoder enabled, `rerank-candidates` 20): body `{"ticker":"NVDA","query":"By what percentage did NVIDIA's Data Center revenue grow in fiscal 2026?","topK":10,"latestFilingsOnly":true,"rerank":false}` returns HYBRID_RRF, chunks 806, 852, 805, 851, 881, 873, 745, 876, 800, 744, expected passage not present (no match, as in 247); with `"rerank": true` HYBRID_RRF_RERANKED, chunks 852, 873, 881, 863, 880, 806, 800, 876, 745, 842, expected passage not present (no match, as in 249), identical on a second call.
        * Recommendation: do not enable reranking. `rag.retrieval.reranking-enabled` and `rag.retrieval.cross-encoder.enabled` stay false; `rag.retrieval.rerank-candidates` stays 20 (no row won, so the table gives no reason to move it). Neither floor is re-derived: no row qualified, and with reranking off the floors still guard snapshot 91's ranks (live test exit 0, below).
        * What enabling would take if Jay chose to anyway (against this measurement): `rag.retrieval.cross-encoder.enabled=true` (`RAG_CROSS_ENCODER_ENABLED=true`) and `rag.retrieval.reranking-enabled=true`, optionally `rag.retrieval.rerank-candidates=40` (the best MRR of the three, snapshot 250). Startup then fails on any machine without `models/cross-encoder-ms-marco-MiniLM-L-6-v2/model.onnx` and `tokenizer.json` matching the recorded checksums, because the files are not in git. Each retrieval call would add about 0.26 s, 0.52 s, or 0.98 s of scoring at 10, 20, or 40 candidates (topK 10), and the first scoring call after a start took 446 to 1,110 ms (the warm-up calls, topK 5); at 40 candidates the slowest call (1,103 ms) is about half the 2,000 ms `rerank-timeout-ms`. Expect what the table shows at 40 candidates, gains and losses together: aggregate hit@5 0.786 to 0.762, NVDA hit@5 0.643 to 0.429, and the figure slice in the top 5 12/12 to 9/12, against a net non-figure change of plus two (21/30 to 23/30) and AAPL 13/14 to 14/14, from a single run.
        * Floors with the final configuration (defaults unchanged, reranking off): `set -a && source .env && set +a && ./mvnw -q -o test -Dtest=RetrievalEvaluationLiveTests -Drag.evaluation.live=true` exit 0, hit@5 0.785714 (floor 0.65, 33 of 42, margin 5), non-figure 21 of 30 (floor 0.60, margin 3); `retrieval_evaluations` 26 rows, max id 250, before and after (rolled back).
        * Evidence: `documentation/live-runs/2026-09-13-reranker/measurement/`: `snapshot-247-reference-rerank-off.json`, `snapshot-248-rerank-candidates-10.json`, `snapshot-249-rerank-candidates-20.json`, `snapshot-250-rerank-candidates-40.json` (row_to_json of the stored rows), `rule-table.txt` (metrics recomputed from the stored ranks and checked against the stored aggregates, the rule row by row, rank changes against 247 for each rerank row, every question's rank in all four rows), `run.log` (each start's overrides, warm-up, snapshot ids, fallback counts, scoring times, the reproduction query, the nvda-02 retrieval check, the rule, the recommendation), `latency-247.txt` to `latency-250.txt` (every per-call scoring line of each run, including the warm-up calls), `live-test.log` (floor test with row counts).
        * Windowed rows (2026-09-13, plan `plans/2026-09-13-reranker-windows.md` Milestone 2 with Amendment 1; the head rows above stay as the prior comparison)
            * Cause of the head rows' losses, named in Windows above: the head cut never saw an answer past about 480 tokens, and every kind-FIGURE question the head measurement pushed out of the top 5 was one whose expected phrase starts past the kept tokens (nvda-11, nvda-14, msft-12, msft-04) or at their very end (nvda-01); the one other head top-5 loss, msft-05, has its answer inside the kept tokens. This measurement repeats the grid with `passage-scoring: max-window` (the default since Milestone 1) and adds Amendment 1's row: `rerank-candidates` 20 with `window-overlap-tokens` 224.
            * What was run: the same harness as above (one start per row, `RAG_CROSS_ENCODER_ENABLED=true`, one warm-up retrieval with `"rerank": true` after each start, then `POST /api/rag/evaluate?rerank=false` or `?rerank=true`), overrides as environment variables of the run shell only and confirmed in each snapshot's `properties` (`rerankCandidates`, `rerankerScoring`) and in the startup `scoring=` line: reference 295 (rerank off); 296, 297, 298 at 10, 20, 40 candidates with the defaults `max-window/overlap=64/maxWindows=4`; 298 with `RAG_RETRIEVAL_RERANK_TIMEOUT_MS=4000` from the start (Amendment 1; 27 of its 42 evaluation calls took over 2,000 ms, and so did its warm-up call at 2,403 ms, so at the default timeout it would have been invalid); 299 at 20 candidates with `RAG_RETRIEVAL_CROSS_ENCODER_WINDOW_OVERLAP_TOKENS=224` (`max-window/overlap=224/maxWindows=4`). Every rerank row reports `rerankedQuestions` 42 and `rerankFallbackQuestions` 0, so all four are valid and none was rerun; no reranker WARN, fallback, or query-truncation line in any log. Reference 295 reproduces 247 (and so 91) question by question: rank, matched chunk, and position of all 42 equal, aggregates and slices equal. No chat model; 267 embeddings in all (five evaluations, six warm-ups, nine diagnostic retrievals, the floor test).
            * Scoring time is per question as above (42 calls per row, topK 10, warm-up excluded), from the `Cross-encoder scoring completed ... windows=..., elapsedMs=` lines committed as `latency-295.txt` to `latency-299.txt`; windows is the rows scored in the call (the head rows scored one row per candidate).

| Snapshot | rerank | Candidates | Scoring | hit@1 | hit@3 | hit@5 | MRR | AAPL hit@5 | MSFT hit@5 | NVDA hit@5 | Non-figure hit@5 | Figure top 5 | Reranked / fallback | Scoring ms median / max | Windows per call median |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 295 (reference) | false | 20 (unused) | max-window/64 | 0.547619 | 0.714286 | 0.785714 | 0.655187 | 0.928571 | 0.785714 | 0.642857 | 0.700000 (21/30) | 12/12 | 0 / 0 | none | none |
| 296 | true | 10 | max-window/64 | 0.500000 | 0.714286 | 0.761905 | 0.625198 | 0.928571 | 0.785714 | 0.571429 | 0.666667 (20/30) | 12/12 | 42 / 0 | 539.5 / 623 | 21 |
| 297 | true | 20 | max-window/64 | 0.500000 | 0.761905 | 0.785714 | 0.633135 | 0.928571 | 0.857143 | 0.571429 | 0.700000 (21/30) | 12/12 | 42 / 0 | 1122 / 1228 | 43 |
| 298 | true | 40 (timeout 4000) | max-window/64 | 0.523810 | 0.785714 | 0.809524 | 0.656614 | 0.928571 | 0.928571 | 0.571429 | 0.733333 (22/30) | 12/12 | 42 / 0 | 2139 / 2347 | 83.5 |
| 299 | true | 20 | max-window/224 | 0.476190 | 0.785714 | 0.809524 | 0.635516 | 0.928571 | 0.857143 | 0.642857 | 0.733333 (22/30) | 12/12 | 42 / 0 | 1323 / 1612 | 51 |

            * Rule, the same as above, applied to each windowed row against 295 (22 kind-FIGURE questions in the reference top 5, the same set as under 247):
            * 296 (10 candidates) FAILS: (1) 32/42 below 33/42; (2) 20/30 below 21/30; (3) NVDA 8/14 below 9/14 (AAPL 13/14 and MSFT 11/14 equal); (4) msft-04 4 to 8, nvda-01 3 to 8.
            * 297 (20 candidates) FAILS: (1) passes, 33/42; (2) passes, 21/30; (3) NVDA 8/14 below 9/14 (AAPL 13/14 equal, MSFT 12/14 up); (4) msft-04 4 to no match, nvda-01 3 to no match.
            * 298 (40 candidates, timeout 4,000) FAILS: (1) passes, 34/42; (2) passes, 22/30; (3) NVDA 8/14 below 9/14 (AAPL 13/14 equal, MSFT 13/14 up); (4) nvda-01 3 to no match.
            * 299 (20 candidates, overlap 224) FAILS: (1) passes, 34/42; (2) passes, 22/30; (3) passes (AAPL 13/14, MSFT 12/14, NVDA 9/14); (4) msft-04 4 to no match, nvda-01 3 to no match.
            * Outcome: no row qualifies, so reranking stays off and there is no recommendation. Against the head rows, windowing did what the diagnosis predicted for the questions it named: the figure slice is 12/12 in every row (head 10/12, 9/12, 9/12), nvda-11 is back in the top 5 (rank 5 in every row; head 7, 10, 10), nvda-14 at 1, 1, 1, 2 (head 7, no match, no match), msft-12 at 2 (head 5, 6, 6), NVDA's top-5 loss falls by two thirds (one question, 9/14 to 8/14, against three, 9/14 to 6/14, under head; none with overlap 224), and aggregate hit@5 no longer falls at 20 candidates and rises to 34/42 at 40 candidates or with overlap 224, with hit@3 0.785714 against 0.714286. What it did not do: every row still fails the rule (296 on all four criteria, 297 and 298 on the NVDA criterion and criterion 4, 299 on criterion 4 alone): nvda-01 leaves the top 5 in every row and msft-04 in every row but 298, where the neighbouring chunk 467 answers it at rank 1. And hit@1 falls from 23/42 to 21, 21, 22, and 20: in every row six rank-1 answers move down, aapl-02 (to 3), aapl-05 (to 2), msft-11 (to 3), msft-14 (to 2), nvda-10 (to 2) and nvda-11 (to 5), and four questions rise to rank 1, aapl-03, aapl-06, msft-03 (each from 2) and nvda-08 (from 5), so 23 - 6 + 4 = 21 in 296 and 297; 298 also lifts msft-04 to rank 1 (22), and 299 also moves nvda-14 from 1 to 2 (20).
            * The two blocking questions, against the truncation probe and the Milestone 1 position sweep (Windows above; `run.log` has every named question): nvda-01's phrase starts at token 485 of chunk 805 (854 tokens, window 489), which the head cut kept only as its last four tokens; the default overlap gives windows at 0 and 365, so the phrase sits about 120 tokens into the second window, inside a scored window: not a truncation loss, and overlap 224 (windows 0, 265, 365) does not change it. That the chunk then loses within its best window is inferred from the ranks, not measured: chunk 805 is the third fused candidate, so every row scores it, and it ranks 8 at 10 candidates and outside the reranked top 10 at 20 and 40, below chunks 852 and 873 among others. The probe's 5.03 for nvda-01 is not that window's logit: the probe scored the chunk's text from 200 characters before the phrase (`CrossEncoderTruncationProbe.java.txt` line 53), which puts the phrase about 60 tokens into its window (59, counted with the real tokenizer), and the Milestone 1 sweep changes between those depths (+9.42 at 54, +5.91 at 141, on synthetic filler), so 5.03 does not bound the logit of the production window. msft-04's phrase starts at token 606 of chunk 466 (693 tokens, window 480); the chunk is 213 tokens longer than the window, so both overlaps give the same two windows (0 and 213) with the phrase 393 tokens into the second, whose logit was not measured (the probe's rescoring from 200 characters before the phrase, 39 tokens in, gave 0.20). The following chunk 467 carries the same sentence from character 96 (1,564 characters; 391 tokens is the stored characters / 4 estimate, 282 WordPiece tokens counted with the real tokenizer, so one window, whose logit is its head logit, 3.04 and first of all MSFT chunks in the probe); it is not among the first 20 fused candidates (inferred from the ranks: it ranks first of 40 in 298, so it would rank first in any row whose candidates included it) and enters only at 40 candidates, where the reranker ranks it first: truncation plus the short-chunk edge the plan names. nvda-11 recovers only to 5: the default overlap places its answer 219 tokens into the second window, at the sweep's trough (-11.09 at 213); overlap 224 also places it 343 tokens into another window (+2.57 at 340 in the sweep) and its rank is still 5. The logits of chunk 805's windows for nvda-11 were not measured, so the synthetic sweep is consistent with that rank rather than proof of its cause. The 224 overlap lifted exactly one further question into the top 5, nvda-09 (no match to rank 2, chunk 879, whose phrase begins in the chunk's last quarter and which was not in the probe), and moved aapl-09 from no match to 10 and nvda-14 from 1 to 2.
            * Per slice and ticker, top 5 against 295 (21/30 non-figure, 12/12 figure, AAPL 13, MSFT 11, NVDA 9): 296 gained msft-01 and msft-07, lost msft-04, msft-05, nvda-01 (non-figure 20/30, NVDA 8, aggregate 32); 297 gained msft-01, msft-07, msft-08, lost msft-04, msft-05, nvda-01 (21/30, MSFT 12, NVDA 8, aggregate 33); 298 gained the same three, lost msft-05 and nvda-01 (22/30, MSFT 13, NVDA 8, aggregate 34); 299 gained those three and nvda-09, lost msft-04, msft-05, nvda-01 (22/30, MSFT 12, NVDA 9, aggregate 34). No figure-slice question left the top 5 in any windowed row; AAPL stays 13/14 in every row (aapl-09, which the head rows had lifted to 4 or 5, stays out: its answer sits 181 tokens into its second window). msft-05 (a segments question answered by several MSFT chunks; its answer is at token 121 and visible to the head cut) falls to 6 or 10 in every row, a model preference rather than truncation.
            * Per-question changes, reference 295 against every windowed row, rank (matched chunk); "no match" is no match in the 10-chunk window; the 17 questions not listed keep rank and chunk in every row:

| Question | Kind | Slice | 295 | 296 (10) | 297 (20) | 298 (40) | 299 (20, overlap 224) |
|---|---|---|---|---|---|---|---|
| aapl-02 | FIGURE | non-figure | 1 (241) | 3 (226) | 3 (226) | 3 (226) | 3 (226) |
| aapl-03 | NARRATIVE | non-figure | 2 (226) | 1 (226) | 1 (226) | 1 (226) | 1 (226) |
| aapl-05 | NARRATIVE | non-figure | 1 (190) | 2 (246) | 2 (246) | 2 (246) | 2 (246) |
| aapl-06 | FIGURE | non-figure | 2 (269) | 1 (269) | 1 (269) | 1 (269) | 1 (269) |
| aapl-09 | FIGURE | non-figure | 8 (280) | 8 (280) | no match | no match | 10 (280) |
| msft-01 | FIGURE | non-figure | 6 (514) | 2 (573) | 2 (573) | 2 (573) | 2 (573) |
| msft-03 | NARRATIVE | non-figure | 2 (517) | 1 (517) | 1 (517) | 1 (517) | 1 (517) |
| msft-04 | FIGURE | non-figure | 4 (466) | 8 (466) | no match | 1 (467) | no match |
| msft-05 | NARRATIVE | non-figure | 2 (571) | 6 (460) | 10 (460) | 10 (460) | 10 (460) |
| msft-07 | NARRATIVE | non-figure | 7 (495) | 4 (495) | 2 (680) | 2 (680) | 2 (680) |
| msft-08 | FIGURE | non-figure | no match | no match | 2 (547) | 2 (547) | 2 (547) |
| msft-11 | FIGURE | figure | 1 (519) | 3 (519) | 3 (519) | 3 (519) | 3 (519) |
| msft-13 | FIGURE | figure | 1 (523) | 1 (524) | 1 (524) | 1 (524) | 1 (524) |
| msft-14 | FIGURE | figure | 1 (644) | 2 (646) | 2 (646) | 2 (646) | 2 (646) |
| nvda-01 | FIGURE | non-figure | 3 (805) | 8 (805) | no match | no match | no match |
| nvda-03 | FIGURE | non-figure | 1 (797) | 1 (850) | 1 (850) | 1 (850) | 1 (850) |
| nvda-05 | NARRATIVE | non-figure | 10 (749) | 10 (749) | no match | no match | no match |
| nvda-07 | NARRATIVE | non-figure | no match | no match | 8 (770) | 9 (770) | 8 (770) |
| nvda-08 | FIGURE | non-figure | 5 (852) | 1 (852) | 1 (852) | 1 (852) | 1 (852) |
| nvda-09 | FIGURE | non-figure | no match | no match | no match | no match | 2 (879) |
| nvda-10 | NARRATIVE | non-figure | 1 (895) | 2 (878) | 2 (878) | 2 (878) | 2 (895) |
| nvda-11 | FIGURE | figure | 1 (805) | 5 (805) | 5 (805) | 5 (805) | 5 (805) |
| nvda-12 | FIGURE | figure | 5 (802) | 2 (802) | 2 (802) | 2 (802) | 2 (802) |
| nvda-13 | FIGURE | figure | 1 (880) | 1 (879) | 1 (879) | 1 (879) | 1 (879) |
| nvda-14 | FIGURE | figure | 1 (872) | 1 (872) | 1 (872) | 1 (872) | 2 (872) |

            * Retrieval check at the defaults (one extra start, `RAG_CROSS_ENCODER_ENABLED=true`, 20 candidates, overlap 64, warm-up first; `diagnostic-defaults.txt`), body `{"ticker":<t>,"query":<question text>,"topK":10,"latestFilingsOnly":true,"rerank":<v>}`: nvda-11 with `rerank` false returns HYBRID_RRF, chunks 805, 851, 881, 872, 806, 852, 880, 800, 873, 850 (805 first); with `rerank` true HYBRID_RRF_RERANKED, chunks 852, 881, 873, 851, 805, 872, 880, 850, 871, 800 (805 fifth), identical on a second call. nvda-14: false 872, 873, 879, 880, 881, 851, 806, 852, 805, 863; true 872, 873, 864, 852, 851, 863, 881, 871, 842, 800 (872 first in both). nvda-01: false 806, 852, 805, 881, 851, 873, 872, 800, 839, 863 (805 third); true 852, 873, 864, 851, 842, 881, 872, 857, 800, 863 (805 absent). msft-04: false 514, 573, 518, 466, 643, 645, 635, 637, 516, 460 (466 fourth); true 514, 516, 573, 637, 635, 642, 518, 645, 512, 641 (466 and 467 absent). These are snapshot 297's ranks question by question.
            * Recommendation: do not enable reranking. `rag.retrieval.reranking-enabled` and `rag.retrieval.cross-encoder.enabled` stay false; `rerank-candidates` 20, `passage-scoring` max-window, `window-overlap-tokens` 64 and `max-windows` 4 stay as Milestone 1 set them (no row won, so the table gives no reason to move them). Neither floor is re-derived; with reranking off the floors still guard snapshot 91's ranks (live test below).
            * What enabling would take if Jay chose to anyway (against this measurement): the row nearest the rule is 299, which passes the aggregate, non-figure and per-ticker criteria and fails only criterion 4 (msft-04 and nvda-01 leave the top 5): `rag.retrieval.cross-encoder.enabled=true` (`RAG_CROSS_ENCODER_ENABLED=true`), `rag.retrieval.reranking-enabled=true`, `rag.retrieval.cross-encoder.window-overlap-tokens=224`, `rerank-candidates` 20. Startup then fails on any machine without the model files (not in git). Expect aggregate hit@5 0.786 to 0.810, non-figure 21/30 to 22/30, figure 12/12, AAPL 13/14 and NVDA 9/14 kept, MSFT 11/14 to 12/14, hit@1 0.548 to 0.476 and MRR 0.655 to 0.636, and these top-5 changes against 295, the complete list: msft-01, msft-07, msft-08 and nvda-09 in, msft-04, msft-05 and nvda-01 out; from a single run. Scoring adds about 1.3 s per retrieval at 20 candidates (maximum 1,612 ms, about 0.4 s under the 2,000 ms `rerank-timeout-ms`). That margin is measured only for sequential calls from one evaluation client: the rerank executor runs up to two scoring calls at once, the recommendation flow runs specialists in parallel by default (`recommendation.parallel-specialists`), and an overlapping second inference slowed head-scored calls from 299 to 354 ms to 680 to 930 ms (Latency), so the margin under concurrent retrievals is unmeasured and may not hold. The best-MRR row 298 (40 candidates, MRR 0.657) needs `rerank-timeout-ms` 4,000 (27 of its 42 evaluation calls, and its warm-up call, took over 2,000 ms), costs about 2.1 s per retrieval, and fails NVDA (8/14) as well as criterion 4 on nvda-01.
            * Floors with the final configuration (defaults unchanged, reranking off), in a shell with no `RAG_*` variable and no application enable flag: `set -a && source .env && set +a && ./mvnw -q -o test -Dtest=RetrievalEvaluationLiveTests -Drag.evaluation.live=true` exit 0, HYBRID_RRF, hit@5 0.785714 (floor 0.65, 33 of 42, margin 5), non-figure 21 of 30 (floor 0.60, margin 3); `retrieval_evaluations` 31 rows, max id 299, before and after (rolled back).
            * Evidence: `documentation/live-runs/2026-09-13-reranker-windows/measurement/`: `snapshot-295-reference-rerank-off.json`, `snapshot-296-rerank-candidates-10.json`, `snapshot-297-rerank-candidates-20.json`, `snapshot-298-rerank-candidates-40-timeout-4000.json`, `snapshot-299-rerank-candidates-20-overlap-224.json` (row_to_json of the stored rows), `rule-table.txt` (metrics recomputed from the stored ranks and asserted equal to the stored aggregates, slices and per-ticker values of all nine rows 295 to 299 and 247 to 250, the reproduction check, the rule row by row, rank changes against 295 for each windowed row with the probe's visibility column, the named questions across all nine rows, every question's rank in every row, the top-5 balance, and the scoring times), `run.log` (each start's overrides, warm-up, snapshot id, fallback count, scoring times and window counts, the rule, every named question against the probe and the window positions, the diagnostic, the recommendation), `latency-295.txt` to `latency-299.txt` (every per-call scoring line of each run, warm-up included), `diagnostic-defaults.txt`, `live-test.log` (floor test with row counts).
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
        * rerank: true reranks this call (HTTP 400 when no FilingReranker is configured), false keeps the fused order; absent follows `rag.retrieval.reranking-enabled`.
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


* Retrieval Methods (overview, as of 2026-09-13)
    * What retrieval is for
        * Given a ticker and a question, return the few stored filing passages most likely to hold the answer. The recommendation loop searches with the user's question before the RAG specialist model runs and again on the specialist's own queries; the passages returned become the evidence the manager reasons over and cites, and the critic checks the answer against them. Retrieval quality therefore bounds recommendation quality.
    * The eligibility pool (one SQL CTE shared by every method)
        * Only the requested ticker; only filings whose ingestion status is EMBEDDED; by default only the latest stored filing of each type (10-K, 10-Q, 8-K), unless a date range is given; optional filing-type and section filters. Every method below draws candidates from exactly this pool, so combining them never widens what is searched.
    * Method 1: vector similarity (since 2026-09-09)
        * The question is embedded once with `text-embedding-3-small` (1536 dimensions). Each chunk's stored embedding is compared by cosine distance in pgvector (`<=>`), exact search over the pool, `candidate-count` 40 nearest chunks. Strong on paraphrase and narrative questions ("why did margins fall"), weak on exact tokens: an embedding blurs "215,938" or "OpenAI" into their surroundings.
    * Method 2: keyword search (since 2026-09-12, RAG-2)
        * PostgreSQL full-text search over a stored generated column `content_tsv = to_tsvector('english', content)` with a GIN index (migration V9). The question is turned into an OR query of its distinct alphanumeric tokens (stopwords dropped; numbers keep their inner commas and periods, so "64,377" matches only the phrase '64' followed by '377'), bound as a parameter to `to_tsquery`, never concatenated. Ranked by `ts_rank_cd`, `keyword-candidate-count` 40. Finds exact terms and figures; scores common words as much as rare ones, which is why it is fused rather than used alone.
    * Method 3: figure search (since 2026-09-12, RAG-12)
        * Runs only when the question carries a number that is not a lone year: an AND query of the question's numeric tokens over the same column, so a chunk must contain every number. Rewards the single chunk that states the figure ("Revenue $ 215,938") over neighbours that share the surrounding words. Off for any question without a non-year number, whatever its kind.
    * Combining them: weighted reciprocal rank fusion
        * Each method yields a ranked list. A chunk's fused score is the sum, over the lists containing it, of weight / (k + rank), with k = 60 and weights vector 1.0, keyword 0.5, figure 1.0 (chosen by measurement, see Fusion tuning). Rank-based fusion needs no calibration between cosine similarity and text-search rank; the weights and k are configuration. Ties break by vector similarity, then chunk id. Exact decimal arithmetic makes the order deterministic.
        * Every returned chunk still reports its cosine similarity to the question, whichever method found it, so the recommendation loop's input-coverage confidence keeps its meaning. If the keyword method fails, retrieval degrades to vector-only and says so in `retrievalStrategy` (FILTERED_VECTOR instead of HYBRID_RRF); it never fails because of the keyword path.
    * After fusion
        * Diversify: near-duplicate chunks from the same filing and section (large text overlap) are dropped so the top-k is not five copies of one passage. Then the requested top-k (5 by default; 3 under the lean profile) is returned with citation metadata. A reranker hook (`FilingReranker`) can re-order the fused, diversified candidates with a model: the local cross-encoder (Cross-encoder reranker above) when `rag.retrieval.cross-encoder.enabled` is true and a call asks for it; reranking is off by default, and neither 2026-09-13 measurement (head scoring, then windowed scoring) qualified it (RAG-1, Reranker measurement).
    * Per-request control
        * `hybrid` on `POST /api/rag/retrieve` and `?hybrid=` on `POST /api/rag/evaluate` override the property default for one call, which is how the two strategies are compared without a restart.
    * How it is measured
        * The evaluation sets (Retrieval Evaluation below; v1 has 30 questions, v2, the default since 2026-09-13, has 42) record hit@1/3/5 and MRR per stored snapshot. Vector-only: hit@5 0.600, MRR 0.436 (snapshot 35). Hybrid, equal weights: 0.633, 0.437 (snapshot 34). Hybrid with the tuned weights: 0.633, MRR 0.463, hit@1 0.333 (snapshot 51). Set v1 carries no figure-bearing questions, so under v1 the figure method was evidenced only by live queries. Set v2 (42 questions, the default since 2026-09-13, RAG-11) adds 12 figure-bearing questions and makes the figure method measurable: the current default configuration is still snapshot 51's weights, now also confirmed on set v2 (snapshot 69: hit@5 0.786, MRR 0.655), and turning the figure method off drops v2 hit@5 to 0.738 and MRR from 0.655 to 0.533 (snapshot 71). The v2 numbers are not comparable with v1's and are not a retrieval improvement over 0.633: the 12 new questions state their figure in their own text and all hit under snapshot 69, while the 30 carried questions score 21 of 30 (see Set v2 baseline and figure-leg measurement below). NVDA is still the weakest ticker (v2 hit@5 0.643 under snapshot 69; 0.3 on v1) for structural reasons recorded in RAG-7 and RAG-1. The cross-encoder reranker, measured on set v2 at 10, 20, and 40 candidates (snapshots 248 to 250 against reference 247, which reproduces 91), one run per configuration, lowered aggregate hit@5 in every row (0.786 to 0.762, 0.738, 0.762), NVDA hit@5 from 0.643 to 0.429, and the figure slice in the top 5 from 12/12 to 10/12, 9/12, and 9/12, while the non-figure slice rose by a net one or two questions (21/30 to 22/30, 22/30, and 23/30). Windowed scoring (snapshots 296 to 299 against reference 295, which reproduces 247) repaired the figure slice (12/12 in every row) and brought NVDA to 0.571 (0.643 at the 224-token overlap) and aggregate hit@5 to 0.762, 0.786, 0.810, 0.810, but no row met the rule: nvda-01 (kind FIGURE) leaves the top 5 in every row and msft-04 (kind FIGURE) in every row but 298, NVDA hit@5 still falls in 296 to 298, and 296 also lowers aggregate and non-figure hit@5; it is not enabled (Cross-encoder reranker, Reranker measurement).
    * What is deliberately not done
        * No approximate vector index (exact search over a few hundred chunks per ticker is fast); no reranking by default (the cross-encoder exists and was measured on 2026-09-13; every configuration lowered aggregate hit@5, NVDA hit@5, and the figure slice in the top 5 (12/12 to 10/12, 9/12, 9/12) while raising the non-figure slice by a net one or two questions (21/30 to 22/30, 22/30, 23/30), so it is off, RAG-1); no query rewriting or expansion; no cross-ticker search; passages are cut only at the model boundary (`recommendation.model-passage-chars`), never in the store.

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
  Optional FilingReranker (local cross-encoder; bean off by default, reranking off by default)
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
    * Current baseline: filtered exact vector search fused with full-text keyword search by weighted reciprocal rank (since 2026-09-12; Hybrid Retrieval below), the keyword leg at 0.5 and a figure leg at 1.0 for queries that carry a number (Fusion tuning below; the figure weight re-measured and kept on set v2, Set v2 baseline and figure-leg measurement below).
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
        * `src/main/resources/evaluation/retrieval-set-v2.json` (Follow_Ups RAG-11; plan `plans/2026-09-13-evaluation-set-v2.md`, Milestone 1): the default since 2026-09-13. It carries the 30 v1 questions unchanged in id, ticker, kind, and question text, with alternative expectations where another stored passage genuinely answers the question (nvda-01 the adjacent segment table, nvda-03 Item 5 and the equity note, and aapl-01, aapl-02, aapl-05, aapl-06, msft-01, msft-02, msft-05, msft-07, msft-10, nvda-06 where a note, MD&A paragraph, or the latest 10-Q restates the fact), plus 12 new FIGURE questions (aapl-11 to aapl-14, msft-11 to msft-14, nvda-11 to nvda-14) whose text states the figure in the analyst's own words rather than the filing sentence's, so `FilingRetrievalRepository.figureTerms` is non-empty and the figure leg runs for them; each new question's figure tokens are all present in its expected chunk. A passage counts as an alternative only if it answers the question completely on its own, every part of a multi-part question included (plan Amendment 1): msft-07's alternative is the 10-Q Item 1A passage with the goals and why AI makes them harder, not the 10-K Item 1 paragraph that states the goals alone, and nvda-07 keeps its single 10-K Item 1A expectation because only that chunk states the manufacturing and final-assembly concentration, so a miss on nvda-07 is a genuine retrieval miss. 42 questions, 14 per ticker.
        * Selecting a set: `rag.evaluation.set` names the classpath resource (default `evaluation/retrieval-set-v2.json`; blank is rejected at startup and a resource that is not on the classpath fails the load with an IllegalStateException naming it). Set v1 stays bundled unchanged, so `rag.evaluation.set=evaluation/retrieval-set-v1.json` reproduces any pre-v2 snapshot. Every snapshot records the resource as `properties.set` beside `setVersion`.
        * Metrics are not comparable across sets: a v2 number can be higher or lower than a v1 number purely because the questions and expectations differ. Compare snapshots only when their `setVersion` matches; pre-v2 snapshots (up to and including the fusion tuning) are comparable only with each other.
        * Format: `version`, `createdOn`, and `questions`; each question has `id`, `ticker`, `kind` (FIGURE for an exact number stated in the filing, NARRATIVE for a risk, segment change, or policy), `question`, `expected` (one or more passages, any one satisfies), and optional `notes`.
        * An expected passage is `accessionNo`, `sectionKey`, and `phrase`: a verbatim 12 to 200 character excerpt of a chunk stored for that filing and section, using the same characters as the chunk (curly quotes, non-breaking spaces). Chunk IDs change on rebuild, so they are never referenced.
        * `RetrievalEvaluationSetLoader` (`rag.evaluation`) reads the resource selected by `rag.evaluation.set` (`load()`, or `load(resource)` for an explicit one) and rejects duplicate ids, empty expectation lists, blank or out-of-range phrases, malformed tickers or accession numbers, and phrases shared by two questions, naming the question id.
        * `RetrievalEvaluationSetTests` (database-backed, read-only) proves every expectation is a substring of a stored chunk, case-insensitive with whitespace collapsed, once for the set `rag.evaluation.set` selects and once explicitly for v1, naming the question id and expectation on a miss. `RetrievalEvaluationSetLoaderTests` asserts the v2 invariants without the database (v1 questions carried unchanged, at least 10 questions with figure terms and 3 per ticker, alternatives on nvda-01 and nvda-03, a single expectation on nvda-07, and no run of five or more words shared between msft-11, msft-12, msft-14, or nvda-13 and its phrase). Section keys follow the parser: NVIDIA's Item 8 is a one-line cross-reference, so its financial statement notes sit under ITEM_15; the AAPL 8-K Item 2.02 is stored as ITEM_2 and the MSFT 8-K Item 7.01 as ITEM_7_01.
        * Nothing in the set reaches a model prompt; an evaluation run embeds each question once (the only external call) and never calls a chat model.
    * Adding a question
        * Find the chunk that answers it: `SELECT c.id, c.section_key, c.content FROM sec_filing_chunks c JOIN sec_filings f ON f.id = c.filing_id WHERE f.accession_no = '<accession>' AND c.content ILIKE '%<distinctive words>%'`, then copy a 12 to 200 character excerpt exactly as stored (the loader does not fix quotes or spaces).
        * Add the question with the next id for its ticker (`nvda-11`), the accession, the section key as stored, and the phrase; add a second expected passage when the same fact is stated in another section or chunk, so a correct retrieval of either counts.
        * Keep the set invariants: v1 30 questions and v2 40 to 44, at least 8 per ticker, at least 6 FIGURE questions (v2: at least 10 questions whose text carries a figure, 3 per ticker), no phrase used twice, sections ITEM_1A, ITEM_7, ITEM_8, ITEM_1, and ITEM_7_01 all covered. For a figure question, state the figure in the question and avoid numbers the chunk does not contain (a year or a "31" from a date rides along in the figure leg's AND); v1 is frozen, so add questions to v2 or a new version.
        * Run `RetrievalEvaluationSetTests` (proves the phrase is stored) and then a live evaluation; when the set changes in a way that moves the metrics, bump `version`, record a new baseline below, and re-derive the floor. Stored snapshots carry `setVersion`, so old ones stay comparable among themselves.
    * Metrics (`RetrievalEvaluationService`)
        * Each question is retrieved once with its ticker, the question text as the query, `latestFilingsOnly` true, and `topK` = window; no section, filing type, or date filter, so the section filter never helps the evaluation.
        * Rank: the 1-based position of the first returned chunk whose accession and section equal an expected passage's and whose content contains the phrase (case-insensitive, whitespace collapsed); null when no chunk in the window matches.
        * hit@k: the fraction of questions with rank at most k; reported at k = 1, 3, 5.
        * MRR: the mean over all questions of 1/rank, counting a null rank as 0; a question found at rank 10 contributes 0.1, so MRR rewards moving a passage up even when hit@5 does not change.
        * Per-ticker hit@5: hit@5 over each ticker's questions, the first place to look when a change helps one filer and hurts another.
        * Window: `rag.evaluation.window` (default 10, 5 to 20); a passage beyond it is a miss, so the window bounds MRR's tail and the cost of a run (one embedding per question regardless of window).
        * A retrieval exception for one question is recorded as a miss with the error string; the other questions still count, so one embedding failure never voids a run.
        * Slices (since 2026-09-13): each question is also counted in exactly one slice by its text, with the rule that decides whether retrieval's figure leg runs: `figure` when `FilingRetrievalRepository.figureTerms(question)` is non-empty (a numeric token that is not a lone year, such as "$64,377 million" or "40%"), `nonFigure` otherwise ("revenue in fiscal 2025" is non-figure). A numeric token is a standalone run of digits (inner commas or periods allowed); digits inside a word are not one, so nvda-10's "H200" makes it a non-figure question. Reading "H200" as a figure would give a 13 / 29 split instead of the stored 12 / 30. Each slice records `questionCount`, `hitAt1`, `hitAt3`, `hitAt5`, `mrr` (same definitions and scale as the aggregate, computed by the same code over the slice's results) and `missIds` (the slice's questions with no match in the window, a retrieval error included, in set order; a question ranked 6 to 10 is not a miss but is not a hit@5 either) and `notInTop5` (every question of the slice that does not count toward its hit@5, rank null or greater than 5, in set order, each as `{id, rank}` with rank null for no match in the window or an error; so the ranked 6 to 10 questions are listed with their ranks). Snapshots stored before `notInTop5` was added (snapshot 91 and earlier) read back with it null. An empty slice has `questionCount` 0 and null metrics. The rule, not the set version, defines the slices, so they carry to any future set; on v2 they are exactly the 12 questions added in v2 and the 30 carried from v1.
    * RetrievalEvaluationProperties
        * Configuration prefix: rag.evaluation.

| Property | Default | Meaning |
|---|---|---|
| rag.evaluation.set | evaluation/retrieval-set-v2.json | Classpath resource of the set every run evaluates, recorded as `properties.set`; `evaluation/retrieval-set-v1.json` for comparison runs; must not be blank |
| rag.evaluation.window | 10 | Chunks retrieved per question, 5 to 20; a passage beyond it is a miss |
| rag.evaluation.min-hit-at-5 | 0.65 | Floor asserted by the opt-in `RetrievalEvaluationLiveTests`; derived on set v2 (snapshot 69), superseding the set v1 floor of 0.50 |
| rag.evaluation.min-non-figure-hit-at-5 | 0.60 | Second floor, on the `nonFigure` slice's hit@5, asserted by the same test after the aggregate floor; derived on set v2 (snapshot 91, 21 of 30); 0 to 1; needs 18 of 30 |

    * Endpoints (integration token required, `Authorization: Bearer <INTEGRATION_ACCESS_TOKEN>`)
        * `POST /api/rag/evaluate` runs every question through retrieval and stores a snapshot in `retrieval_evaluations` (migration V8); `GET /api/rag/evaluate` returns the newest snapshot (404 before the first); `GET /api/rag/evaluate/{id}` returns one by id.
        * `POST /api/rag/evaluate?hybrid=true|false` passes that value as the `hybrid` field of every retrieval request (forcing keyword plus vector fusion on or off for the whole run without a restart) and records it as `properties.hybrid`; without the parameter every request carries null (each follows `rag.retrieval.hybrid-enabled`) and `properties.hybrid` is null.
        * `POST /api/rag/evaluate?rerank=true|false` does the same for the `rerank` field (null when absent, following `rag.retrieval.reranking-enabled`) and records `properties.rerank`; `?rerank=true` with no FilingReranker configured returns HTTP 400 before any question runs and stores no snapshot.
        * A snapshot carries `hitAt1`, `hitAt3`, `hitAt5`, `mrr`, `tickerHitAt5`, `window`, `retrievalStrategy`, the run `properties` (window, latestFilingsOnly, set, setCreatedOn, candidateCount, rerankingEnabled, hybridEnabled, keywordCandidateCount, rrfK, rrfVectorWeight, rrfKeywordWeight, rrfFigureWeight, hybrid, and since the reranker plumbing rerank, rerankCandidates, and reranker, the simple name of the reranker's user class or null when none is configured, and since the cross-encoder rerankerVersion, rerankedQuestions, and rerankFallbackQuestions, and since windowed scoring rerankerScoring (Cross-encoder reranker, Evaluation fields); snapshots stored earlier keep their properties as stored), per-question `results` (rank, matched chunk id, null on a miss, and the question's `retrievalStrategy`, null on a retrieval error or on earlier snapshots), `misses` with the top three returned chunks (chunk id, accession, section, similarity) or the retrieval error, and `slices` (`figure` and `nonFigure`, see Metrics). `slices` is stored inside the `results` JSONB document (no migration); snapshots stored before 2026-09-13 (id 75 and earlier, including 69) have no slices key and return `slices: null`; nothing is backfilled.
    * Regression floor
        * `RetrievalEvaluationLiveTests` (opt-in, `@EnabledIfSystemProperty(named = "rag.evaluation.live", matches = "true")`) runs the real evaluation against the local store and asserts hit@5 at or above `rag.evaluation.min-hit-at-5` (default 0.65 since 2026-09-13: the current baseline's hit@5 minus 0.1, rounded down to a multiple of 0.05; on set v2 the baseline 0.785714 gives 0.65. Under set v1 it was 0.50: vector-only 0.6 gave 0.50 and the hybrid baseline 0.633333 gave 0.533 rounded down to 0.50; that floor is superseded, see Set v2 baseline and figure-leg measurement). It prints the metrics, both slices, a `RETRIEVAL_EVAL notInTop5` line for the aggregate and for each slice (hits out of the question count, every question not in the top 5 with its rank, and for the aggregate and the `nonFigure` slice `minHits` = ceiling(floor x question count) and `margin` = hits minus `minHits`, so a passing run shows how many hits it can still lose), per-ticker hit@5, and every miss with its top chunks, and it runs inside a rolled-back transaction so no snapshot is stored (the id sequence still advances).
        * Second floor (since 2026-09-13, Follow_Ups RAG-13): after the aggregate assertion the same test asserts the `nonFigure` slice's hit@5 at or above `rag.evaluation.min-non-figure-hit-at-5` (default 0.60, derived by the same rule from snapshot 91's non-figure hit@5 0.700000). The aggregate is asserted first, so a run that fails only on the second floor shows the aggregate passed. A snapshot with no non-figure questions (every question carries a figure) fails with a message naming the set instead of passing vacuously. Each floor's failure message gives the hit@5 value, the hit count out of the question count, the floor and its property, and names every question not in the top 5 with its rank ("rank 6" to "rank 10", or "no match in window"): the aggregate message lists them across the set (from `results`), the non-figure message lists the slice's `notInTop5`. A breach caused by questions sliding from ranks 1 to 5 down to 6 to 10 therefore names those questions, not only the long-standing misses. Both assertions live in the test-scope `RetrievalEvaluationFloors` so `RetrievalEvaluationServiceTests` exercises them, the empty slice included, without a live run.
        * What each floor protects, in hits against snapshot 91 (the same ranks as 69): the aggregate 0.65 needs 28 of 42 (today 33, so 5 may be lost); the non-figure 0.60 needs 18 of 30 (today 21, so 3 may be lost; 17 of 30 is 0.566667 and fails). Before the second floor, all 5 aggregate losses could fall on the 30 non-figure questions (16 of 30, about 0.53); now at most 3 of them can. The second floor changes no retrieval and no metric; it only narrows which regressions the test lets through, and only on that slice.
        * No figure-slice floor: the slice has 12 questions, so one miss moves its hit@5 by 0.083 and a floor there would mostly measure noise; every figure question states its exact figure, which the figure leg matches with an AND, so the slice is structurally easy (12 of 12 at 5 today); and the figure leg's contribution is recorded by the snapshot 69 against 71 comparison (Set v2 baseline and figure-leg measurement). The figure slice is guarded only by the aggregate floor: if the non-figure slice keeps its 21 hits, up to 5 of the 12 figure hits could be lost with both floors passing.
        * Run: `set -a && source .env && set +a && ./mvnw -q -o test -Dtest=RetrievalEvaluationLiveTests -Drag.evaluation.live=true`; override the floors with `-Drag.evaluation.min-hit-at-5=<fraction>` and `-Drag.evaluation.min-non-figure-hit-at-5=<fraction>`. Without the system property the test is skipped, so `./mvnw -q verify` never embeds anything.
        * Verified 2026-09-12: the default floor passes (exit 0, hit@5 0.600000); a floor of 1.01 fails with an assertion naming hit@5 0.600000 and the nine miss ids (exit 1). Evidence: `documentation/live-runs/2026-09-12-retrieval-eval/`.
        * Verified 2026-09-13 (both floors, set v2, no overrides): exit 0 with `hitAt5=0.785714 floor=0.65` and `slice=nonFigure questions=30 hitAt5=0.700000`; with `-Drag.evaluation.min-non-figure-hit-at-5=0.95` exit 1 on the non-figure assertion, message "non-figure hit@5 0.700000 (21 of 30) is below the floor 0.95 (rag.evaluation.min-non-figure-hit-at-5); not in top 5: aapl-09 (rank 8), msft-01 (rank 6), msft-07 (rank 7), msft-08 (no match in window), nvda-02 (no match in window), nvda-04 (no match in window), nvda-05 (rank 10), nvda-07 (no match in window), nvda-09 (no match in window)" (all nine non-figure questions not in the top 5, ranks as in snapshot 91); the passing run prints `notInTop5 aggregate hits=33/42 minHits=28 margin=5` and `notInTop5 slice=nonFigure hits=21/30 minHits=18 margin=3`; `retrieval_evaluations` 22 rows, max id 91, before and after both runs. Evidence: `documentation/live-runs/2026-09-13-non-figure-floor/`.
        * Raise a floor after a retrieval improvement lands and its new baseline is recorded here; never lower it to make a change pass. The one exception is a new set version: both floors are then re-derived from that set's baseline (the non-figure floor from the baseline's `nonFigure` slice) and may move either way, because numbers from different sets are not comparable (2026-09-13, v1 0.50 to v2 0.65).
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
| nvda-02 (FIGURE, Data Center growth) | 10-K ITEM_7 | 10-K ITEM_7 (0.68), ITEM_15 (0.67), ITEM_7 (0.66) | Right section, neighbouring chunks; a reranker over the 40 candidates (RAG-1) or a keyword boost on "Data Center" and "68%" (RAG-2). Note 2026-09-13 (Reranker measurement): reranking at 10, 20, and 40 candidates left it no match (snapshots 248 to 250); the answering chunk 802 is not among the first 20 fused candidates. Windowed scoring (snapshots 296 to 299) left it no match too |
| nvda-03 (FIGURE, share repurchases) | 10-K ITEM_7 | 10-K ITEM_5 (chunk 797, 0.69), ITEM_15, ITEM_5 | Item 5 states the identical sentence ("we repurchased 282 million shares ... $40.4 billion") and was rank 1; the expectation's section is too narrow (RAG-11) |
| nvda-04 (FIGURE, employees and R&D headcount) | 10-K ITEM_1 | 10-K ITEM_7 (0.62), ITEM_15 (0.62), ITEM_7 (0.62) | Low, flat similarities; the headcount sentence sits in a 15-chunk Item 1 that the query does not pull ahead of MD&A. Keyword ("employees") would help (RAG-2) |
| nvda-05 (NARRATIVE, fabless manufacturing) | 10-K ITEM_1 | 10-K ITEM_1 (chunks 742, 744, 743; 0.57 to 0.54) | Right section, the three chunks before the passage (749); Item 1's opening business overview outscores the manufacturing paragraph. A reranker (RAG-1) is the fix; the adjacent-chunk pattern also argues for RAG-5. Note 2026-09-13 (Reranker measurement): reranking did not fix it; rank 10 (chunk 749) under reference 247 and at 10 candidates, no match at 20 and 40 candidates (snapshots 248 to 250); the same under windowed scoring (rank 10 at 10 candidates, no match at 20 and 40; snapshots 296 to 299) |
| nvda-07 (NARRATIVE, manufacturing concentration and geopolitics) | 10-K ITEM_1A | 10-Q ITEM_1A (0.59), 10-K ITEM_1A (0.59), 10-K ITEM_7 | Right sections in both filings, wrong chunks among 35 risk-factor chunks; the country list is in chunk 770. Reranking (RAG-1); the 10-Q may restate the risk, worth a second expectation (RAG-11). Note 2026-09-13: plan Amendment 1 rejected a second expectation, because the 10-K and 10-Q passages naming Taiwan and South Korea answer only part of the question; the concentration statement is only in chunk 770, so nvda-07 stays a genuine miss. Note 2026-09-13 (Reranker measurement): reranking moved it from no match to rank 6 (20 candidates) and 7 (40), chunk 770, still outside the top 5, and left it no match at 10 (snapshots 248 to 250); windowed scoring gives 8, 9, and 8 at 20, 40, and 20 candidates with overlap 224 (snapshots 297 to 299), still outside the top 5 |
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
    * Set v2 baseline and figure-leg measurement (Follow_Ups RAG-11 and RAG-12; plan `plans/2026-09-13-evaluation-set-v2.md`, Milestone 2; measured 2026-09-13)
        * Read this first: the v2 headline number is not a retrieval improvement over v1's 0.633333 and must not be read as one. The baseline's 33 hits out of 42 (hit@5 0.785714, snapshot 69) are 21 of the 30 questions carried from v1 (0.70) plus 12 of the 12 new FIGURE questions (1.0), so about 36% of the hits come from the new questions; with the figure leg off (snapshot 71, 31 of 42) they are 21 carried plus 10 of 12 new. Each new question carries its exact figure in its own text ("$109,158 million", "$182.9 billion"), a strong lexical signal that the figure leg matches by AND-ing the question's numbers: for a multi-digit or decimal figure only one to four chunks qualify, while a percentage alone is less selective (the figure leg returned 27 candidates for msft-12's "29%", 19 for msft-14's "40%", and 9 for nvda-12's "70%", per run.log, and nvda-12 hits only at rank 5); that makes those questions structurally easier than the carried ones, none of which carries a figure token. Split by slice, MRR under 69 is 0.560595 on the 30 carried questions and 0.891667 on the 12 new ones (71: 0.560595 and 0.465278). Computed from the stored per-question ranks of 69 and 71 (`retrieval_evaluations.results->'questions'`, joined to the set's `kind` and the v1 id list).
        * v1 and v2 numbers are not comparable. Set v2 adds 12 questions that all carry a figure and counts alternative expectations that v1 does not, so the same retrieval scores differently: under identical code, store, and defaults, snapshot 70 (v1) has hit@5 0.633333 and snapshot 69 (v2) 0.785714, and even the 30 carried questions score 21 of 30 (0.7) under v2 against 19 of 30 under v1 with every returned ranking unchanged, because nvda-01 and nvda-03 now match an alternative and aapl-02, aapl-06, msft-05, and nvda-06 match one at a better rank. Compare a v2 snapshot only with other v2 snapshots; the v1 tables above stay comparable only with each other.
        * Method: as in Fusion tuning, one application start per configuration with the weights overridden through `RAG_RETRIEVAL_RRF_KEYWORD_WEIGHT` and `RAG_RETRIEVAL_RRF_FIGURE_WEIGHT` (and `RAG_EVALUATION_SET` for the v1 check), one `POST /api/rag/evaluate` without `hybrid` (`properties.hybrid` null, `hybridEnabled` true, strategy HYBRID_RRF), `rrf-k` 60 and `rrf-vector-weight` 1.0 throughout, 42 embeddings per v2 run, no chat model. Each snapshot's `properties` carry the `set` and the weights it claims (`GET /api/rag/evaluate/{id}`; psql check in `run.log`). The figure leg now runs: each v2 run with a non-zero figure weight logs `Figure search completed` for exactly the 12 new questions and skips the other 30 (`reason=noFigureTerms`).
        * v1 check: snapshot 70 (current defaults, `rag.evaluation.set=evaluation/retrieval-set-v1.json`) reproduces snapshot 51 exactly: hit@1 0.333333, hit@3 0.533333, hit@5 0.633333, MRR 0.463373, AAPL 0.9 / MSFT 0.7 / NVDA 0.3, and every question's rank and matched chunk identical, so nothing in the store or the retrieval drifted between the fusion tuning and this measurement.
    * Baseline and grid (set v2, 42 questions, 14 per ticker, 28 FIGURE; window 10, candidateCount 40, keywordCandidateCount 40, reranking off, latest filings only)

| Configuration (k / vector / keyword / figure) | Snapshot | Set | hit@1 | hit@3 | hit@5 | MRR | Per-ticker hit@5 (AAPL / MSFT / NVDA) | FIGURE questions in the top 5 (of 28) |
|---|---|---|---|---|---|---|---|---|
| 60 / 1.0 / 0.5 / 1.0 (v2 baseline, current defaults) | 69 | evaluation/retrieval-set-v2.json | 0.547619 | 0.714286 | 0.785714 | 0.655187 | 0.928571 / 0.785714 / 0.642857 | 22 |
| 60 / 1.0 / 0.5 / 0.0 (figure leg off) | 71 | evaluation/retrieval-set-v2.json | 0.380952 | 0.619048 | 0.738095 | 0.533362 | 0.928571 / 0.714286 / 0.571429 | 20 (without msft-13, nvda-12) |
| 60 / 1.0 / 0.5 / 0.5 | 72 | evaluation/retrieval-set-v2.json | 0.547619 | 0.714286 | 0.761905 | 0.650425 | 0.928571 / 0.785714 / 0.571429 | 21 (without nvda-12) |
| 60 / 1.0 / 0.5 / 1.5 (added: 69 and 73 tied on hit@5, so the one weight between them could still win on MRR) | 75 | evaluation/retrieval-set-v2.json | 0.523810 | 0.714286 | 0.785714 | 0.644473 | 0.928571 / 0.785714 / 0.642857 | 22 |
| 60 / 1.0 / 0.5 / 2.0 | 73 | evaluation/retrieval-set-v2.json | 0.523810 | 0.714286 | 0.785714 | 0.644473 | 0.928571 / 0.785714 / 0.642857 | 22 |
| 60 / 1.0 / 1.0 / 1.0 (keyword cross-check) | 74 | evaluation/retrieval-set-v2.json | 0.500000 | 0.690476 | 0.785714 | 0.619444 | 0.928571 / 0.785714 / 0.642857 | 21 (without msft-04) |
| reference: 60 / 1.0 / 0.5 / 1.0 on set v1 (v1 check, not comparable with the rows above) | 70 | evaluation/retrieval-set-v1.json | 0.333333 | 0.533333 | 0.633333 | 0.463373 | 0.9 / 0.7 / 0.3 | 8 (of 16) |

        * Questions outside the top 5 under 69: aapl-09 (8), msft-01 (6), msft-07 (7), nvda-05 (10), and msft-08, nvda-02, nvda-04, nvda-07, nvda-09 not in the window. 71 and 72 add msft-13 and nvda-12 (71) or nvda-12 (72) as not in the window; 73 and 75 have 69's misses with the same ranks; 74 moves msft-04 to 8, msft-07 to 4, msft-01 and aapl-09 to 10, nvda-09 to 8, and drops nvda-05 from the window.
    * Rule (restated for v2): among the v2 configurations, the highest hit@5 such that, compared with the v2 baseline 69, (a) no ticker's hit@5 decreases and (b) no FIGURE question in the top 5 under 69 (22 questions: aapl-01, aapl-02, aapl-06, aapl-07, aapl-11 to aapl-14, msft-02, msft-04, msft-10, msft-11 to msft-14, nvda-01, nvda-03, nvda-08, nvda-11 to nvda-14) leaves the top 5; ties on hit@5 break by MRR, then by the smaller change from the current defaults (sum of absolute weight differences). The baseline always qualifies.
    * Rule applied row by row

| Snapshot | (a) tickers vs 69 | (b) protected FIGURE questions kept | hit@5 / MRR / change | Result |
|---|---|---|---|---|
| 69 (60 / 1.0 / 0.5 / 1.0) | baseline | baseline | 0.785714 / 0.655187 / 0.0 | qualifies |
| 71 (60 / 1.0 / 0.5 / 0.0) | fails: MSFT 0.714286, NVDA 0.571429 | fails: msft-13 rank 1 to not in the window, nvda-12 rank 5 to not in the window | 0.738095 / 0.533362 / 1.0 | out |
| 72 (60 / 1.0 / 0.5 / 0.5) | fails: NVDA 0.571429 | fails: nvda-12 rank 5 to not in the window | 0.761905 / 0.650425 / 0.5 | out |
| 75 (60 / 1.0 / 0.5 / 1.5) | holds | holds (every rank as 73) | 0.785714 / 0.644473 / 0.5 | qualifies; ties 69 on hit@5, lower MRR |
| 73 (60 / 1.0 / 0.5 / 2.0) | holds | holds | 0.785714 / 0.644473 / 1.0 | qualifies; ties 69 on hit@5, lower MRR |
| 74 (60 / 1.0 / 1.0 / 1.0) | holds (MSFT stays 0.785714: msft-07 enters at 4 as msft-04 leaves) | fails: msft-04 rank 4 to 8 | 0.785714 / 0.619444 / 0.5 | out |

        * Chosen: snapshot 69, the current defaults (`rrf-k` 60, `rrf-vector-weight` 1.0, `rrf-keyword-weight` 0.5, `rrf-figure-weight` 1.0). Order of the qualifying rows: 69 (0.785714, MRR 0.655187) ahead of 75 and 73 (0.785714, MRR 0.644473). No default changes; application.yaml and `FilingRetrievalProperties` keep these values, with comments now citing this measurement.
        * The figure weight survived measurement. With the leg off (71) hit@5 falls 0.785714 to 0.738095, hit@1 0.547619 to 0.380952, and MRR 0.655187 to 0.533362; at 0.5 nvda-12 leaves the window; at 1.5 and 2.0 the only changes against 69 are nvda-11 rank 1 to 2 and nvda-12 rank 5 to 4, so hit@1 drops to 0.523810 and MRR to 0.644473. 1.0 is the best measured point by the rule, not merely a tie-break.
    * Former narrow misses under v2 (the two questions that gained genuine alternatives; nvda-07 kept its single expectation under plan Amendment 1 and stays a miss, not in the window under 51 or 69)

| Question | Rank in v1 snapshot 51 | Rank in v2 baseline 69 | Matched passage under 69 |
|---|---|---|---|
| nvda-01 (fiscal 2026 revenue and growth) | not in the window | 3 | chunk 805, 10-K ITEM_7 segment table "Total $ 215,938 $ 130,497 $ 85,441 65 %" (the alternative; retrieval is unchanged, chunk 805 was rank 3 under 51 too) |
| nvda-03 (fiscal 2026 share repurchases) | not in the window | 1 | chunk 797, 10-K ITEM_5 "we repurchased 282 million shares of our common stock for $40.4 billion" (the alternative; also rank 1 under 51) |

    * The 12 new FIGURE questions, figure leg on (69, weight 1.0) against off (71, weight 0.0); rank and matched chunk

| Question | Figure in the question | Leg on (69) | Leg off (71) | Moved |
|---|---|---|---|---|
| aapl-11 | 14%, $109,158 million | 1 (225) | 1 (225) | no |
| aapl-12 | 75.4%, 73.9% | 1 (226) | 2 (226) | yes, up one with the leg |
| aapl-13 | $54,252 million | 1 (278) | 1 (278) | no |
| aapl-14 | 32%, $11,729 million | 1 (279) | 1 (279) | no |
| msft-11 | $31.0 billion, 31% | 1 (519) | 4 (519) | yes |
| msft-12 | 29% | 2 (519) | 4 (519) | yes |
| msft-13 | $182.9 billion, $46.8 billion | 1 (523) | not in the window | yes, into the top 5 only with the leg |
| msft-14 | 40% | 1 (644) | 2 (644) | yes |
| nvda-11 | $193,479 million | 1 (805) | 2 (805) | yes |
| nvda-12 | 70% | 5 (802) | not in the window | yes, into the top 5 only with the leg |
| nvda-13 | $7.2 billion | 1 (880) | 4 (880) | yes |
| nvda-14 | $96,221 million | 1 (872) | 3 (872) | yes |

        * Nine of the 12 moved, every one of them up with the leg on; the three AAPL questions that did not move were already rank 1 without it. None of the 30 carried questions changed rank between 69 and 71, as expected: none carries a figure token, so the leg never ran for them.
    * Floor decision
        * Rule: the v2 winner's hit@5 minus 0.1, rounded down to a multiple of 0.05. 0.785714 minus 0.1 = 0.685714, rounded down to 0.65, so `rag.evaluation.min-hit-at-5` is 0.65 since 2026-09-13 (application.yaml and the `RetrievalEvaluationProperties` default; `RetrievalEvaluationSetTests` and `RetrievalEvaluationSetLoaderTests` assert it). The set v1 floor of 0.50 (derived from snapshots 13 and 34) is superseded: a floor is only meaningful against the set it was derived from, which is why this re-derivation may move it in either direction; here it rises.
        * What 0.65 protects: 0.65 of 42 questions is 27.3, so the test needs 28 hits (27 of 42 is 0.642857 and fails). If the 12 figure questions keep hitting, only 16 of the 30 carried questions must hit, about 0.53 on that slice, so 5 of today's 21 carried hits could be lost and the floor still passes. That is roughly the protection v1's 0.50 floor gave (15 of 30). The rise from 0.50 to 0.65 does not tighten protection for the carried questions; it reflects the 12 structurally easy figure questions inflating the aggregate. A separate floor over the non-figure questions was Follow_Ups RAG-13, done 2026-09-13 (Non-figure floor below).
        * Verified 2026-09-13 with the final defaults and set v2: `./mvnw -q -o test -Dtest=RetrievalEvaluationLiveTests -Drag.evaluation.live=true` exit 0, `set=v2 questions=42 strategy=HYBRID_RRF hitAt5=0.785714 mrr=0.655187 floor=0.65` (identical to 69); `retrieval_evaluations` held 21 rows (max id 75) before and after, id 76 consumed by the rolled-back transaction.
    * Known v2 limitations
        * msft-14's phrase ("Azure and other cloud services revenue grew 40% driven by demand for services across the platform") also occurs in the 10-Q's nine-month discussion (chunk 646, revenue up $22.1 billion) as well as the third-quarter one (chunk 644, up $7.9 billion), so retrieving the nine-month chunk would count as a hit for a third-quarter question. Every run here matched chunk 644.
        * The phrase-echo test in `RetrievalEvaluationSetLoaderTests` (no run of five or more words shared between question and phrase) covers only the four questions reworded under Amendment 1 (msft-11, msft-12, msft-14, nvda-13), not the other eight new questions.
    * Evidence
        * `documentation/live-runs/2026-09-13-evaluation-set-v2/`: `snapshot-<id>-<set>-<configuration>.json` for 69 to 75 (row_to_json of the stored rows), `rule-table.txt` (metrics, the rule per row, the narrow-miss and figure-question tables, and every question's rank under 69, 71, 72, 73, 75, 74 and under 51 and 70), `run.log` (each start's overrides, the snapshot ids, the psql configuration check, the figure-search log counts, the rule, the decision), `live-test.log` (the floor test with row counts).
    * Non-figure floor (2026-09-13, Follow_Ups RAG-13)
        * Measurement: one `POST /api/rag/evaluate` with the current defaults and no overrides stored snapshot 91, the first carrying slices. It reproduces snapshot 69 question by question (rank, matched chunk, error, and order: a psql full join over `results->'questions'` returned no differing row) and its aggregate equals 69's (hit@1 0.547619, hit@3 0.714286, hit@5 0.785714, MRR 0.655187).
        * Slices of snapshot 91: `figure` 12 questions, hit@1 0.833333, hit@3 0.916667, hit@5 1.000000 (12 of 12), MRR 0.891667, no window misses; `nonFigure` 30 questions, hit@1 0.433333, hit@3 0.633333, hit@5 0.700000 (21 of 30), MRR 0.560595, window misses msft-08, nvda-02, nvda-04, nvda-07, nvda-09. The other four non-figure questions outside the top 5 are aapl-09 (8), msft-01 (6), msft-07 (7), nvda-05 (10).
        * Rule, with decimal arithmetic: 0.700000 minus 0.1 = 0.600000, rounded down to a multiple of 0.05 = 0.60, so `rag.evaluation.min-non-figure-hit-at-5` is 0.60 (application.yaml and the `RetrievalEvaluationProperties` default; `RetrievalEvaluationSetTests` and `RetrievalEvaluationSetLoaderTests` assert it; validation rejects values outside 0 to 1). What 0.60 protects: 18 of the 30 non-figure hits, so 3 of today's 21 may be lost; it is a tighter guard than the aggregate alone gave that slice (5 of 21), not a claim that retrieval on those questions improved. The aggregate floor stays 0.65. Why there is no figure-slice floor: see Regression floor.
        * Evidence: `documentation/live-runs/2026-09-13-non-figure-floor/`: `snapshot-91.json` (row_to_json of the stored row), `run.log` (harness, the reproduction query, the slice split recomputed from the question texts, the derivation, the live-test summary), `live-test-pass.log` (default floors, exit 0, row counts), `live-test-non-figure-floor-0.95.log` (forced non-figure floor, exit 1, row counts).

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

* Change log — 2026-09-13: evaluation set v2 re-baseline and figure-leg measurement (RAG-11, RAG-12)
    * Measured the current defaults on set v2 (Set v2 baseline and figure-leg measurement above): baseline snapshot 69 hit@1 0.547619, hit@3 0.714286, hit@5 0.785714, MRR 0.655187, AAPL 0.928571 / MSFT 0.785714 / NVDA 0.642857, 22 of 28 FIGURE questions in the top 5; the v1 check (snapshot 70) reproduces snapshot 51 exactly. v1 and v2 numbers are not comparable.
    * Figure-weight grid on v2 (snapshots 71 to 75, plus a keyword cross-check): the leg ran for the 12 new figure questions; weight 0.0 drops hit@5 to 0.738095 and MRR to 0.533362, 0.5 loses nvda-12, 1.5 and 2.0 tie hit@5 with lower MRR, keyword 1.0 loses msft-04. The rule keeps the current defaults (k 60, 1.0 / 0.5 / 1.0); no retrieval code or weight changed.
    * Floor re-derived on v2: 0.785714 minus 0.1 rounded down to 0.05 gives `rag.evaluation.min-hit-at-5` 0.65, superseding the v1 floor of 0.50.
    * Live verification — 2026-09-13: `RetrievalEvaluationLiveTests` with the final defaults and set v2, exit 0, HYBRID_RRF, hit@5 0.785714, floor 0.65, rolled back. Evidence: [baseline snapshot 69](live-runs/2026-09-13-evaluation-set-v2/snapshot-69-v2-k60-kw0.5-fig1.0-baseline.json), [v1 check snapshot 70](live-runs/2026-09-13-evaluation-set-v2/snapshot-70-v1-k60-kw0.5-fig1.0-check.json), [rule table](live-runs/2026-09-13-evaluation-set-v2/rule-table.txt), [floor test](live-runs/2026-09-13-evaluation-set-v2/live-test.log), [run log](live-runs/2026-09-13-evaluation-set-v2/run.log).

* Change log — 2026-09-13: a second regression floor over questions without a figure (RAG-13)
    * `RetrievalEvaluation` gains `slices` (`figure` and `nonFigure`, each `SliceMetrics`: questionCount, hitAt1, hitAt3, hitAt5, mrr, missIds, notInTop5 with ranks), computed by `RetrievalEvaluationService` with `FilingRetrievalRepository.figureTerms` over the question text and stored inside the `results` JSONB; older snapshots read back with `slices` null. The aggregate metrics are computed exactly as before.
    * `rag.evaluation.min-non-figure-hit-at-5` (0.60, 0 to 1) is asserted by `RetrievalEvaluationLiveTests` after the aggregate floor; an empty non-figure slice fails naming the set. Derived from snapshot 91 (reproduces 69; non-figure hit@5 21 of 30 = 0.70). It needs 18 of 30 non-figure hits (3 of today's 21 may be lost, against 5 under the aggregate floor alone); the aggregate floor stays 0.65 (28 of 42). No figure-slice floor (12 questions). No retrieval, set, or weight change.
    * Live verification — 2026-09-13: `RetrievalEvaluationLiveTests` exit 0 with the defaults; exit 1 with `-Drag.evaluation.min-non-figure-hit-at-5=0.95` on the non-figure assertion naming 0.700000, 21 of 30, 0.95, and all nine non-figure questions not in the top 5 with their ranks (four ranked 6 to 10, five with no match in the window); 22 rows (max id 91) before and after both. Evidence: [snapshot 91](live-runs/2026-09-13-non-figure-floor/snapshot-91.json), [floor test](live-runs/2026-09-13-non-figure-floor/live-test-pass.log), [forced non-figure failure](live-runs/2026-09-13-non-figure-floor/live-test-non-figure-floor-0.95.log), [run log](live-runs/2026-09-13-non-figure-floor/run.log).

* Change log — 2026-09-13: cross-encoder reranker (RAG-1, plan Milestone 2 with Amendment 1)
    * Added PairScorer, OnnxCrossEncoderScorer, CrossEncoderReranker, CrossEncoderModelFiles, CrossEncoderProperties, and CrossEncoderConfiguration (Cross-encoder reranker above), with the Maven dependencies `com.microsoft.onnxruntime:onnxruntime` 1.29.0 and `ai.djl.huggingface:tokenizers` 0.38.0. `rag.retrieval.cross-encoder.enabled` (default false) creates the bean; `rag.retrieval.reranking-enabled` stays false; no default changed.
    * Amendment 1: reranker input max(`rerank-candidates`, topK); per-question `retrievalStrategy` and snapshot `rerankedQuestions` / `rerankFallbackQuestions`; `rerankerVersion` in snapshot properties; reranker name from the user class; the Javadoc and this document state that an `Error` from a reranker propagates.
    * Live verification — 2026-09-13: `CrossEncoderRerankerLiveTests` with the real model, exit 0: the answering passage ranked first for all three handwritten queries (logits 11.37 vs -11.24 / -11.29; 5.75 vs -11.12 / -10.97; 5.51 vs -4.43 / -11.31), identical scores across two calls, 20 passages of about 2,000 characters in 328 to 354 ms after warm-up, 1 ms timeout fell back to FILTERED_VECTOR and 30 s reranked; a second run with HTTP(S) proxies pointed at a closed port also passed, which shows only that no proxied HTTP(S) download happened (corrected in the remediation below: DJL's metadata call bypasses proxies). No evaluation was run and no retrieval-quality number is claimed. Evidence: [downloads](live-runs/2026-09-13-reranker/downloads.md), [model metadata](live-runs/2026-09-13-reranker/onnx-metadata.txt), [live test](live-runs/2026-09-13-reranker/live-test.log), [network-blocked run](live-runs/2026-09-13-reranker/live-test-network-blocked.log).
* Change log — 2026-09-13: cross-encoder input bounds, telemetry opt-out, shutdown edge case (RAG-1, Milestone 2 remediation)
    * Fixed a JVM abort: a query of 509 or more wordpieces with `only_second` truncation made the native tokenizer panic (exit 134) instead of falling back. OnnxCrossEncoderScorer now uses `longest_first` truncation and bounds every query and chunk in Java to 20,000 characters with unpaired surrogates replaced; a cut query is logged at INFO with lengths only (Cross-encoder reranker, Input bounds).
    * Disabled DJL's `Ec2Utils.callHome` (EC2 metadata and telemetry requests that bypass proxies) by setting `OPT_OUT_TRACKING=true` and `ai.djl.offline=true` when absent, before any DJL class is initialised (Network). Corrected what Milestone 2's proxy-blocked run proved, here and in `downloads.md`.
    * Documented the shutdown case where an inference outlasts close()'s 30 s wait (Shutdown).
    * Live verification — 2026-09-13: `CrossEncoderForkedLiveTests` exit 0: every boundary case and the full sweep scored in the child JVM (for example words509 -6.8142157, chars20000 -3.4244962, singleWord5000 -9.817628, unicodeEmoji -4.285012), cut queries kept 254 or 255 tokens, worst 20-pair batch 698 / 542 / 545 ms (882 / 572 / 581 on a confirming rerun, also exit 0), lsof 368 samples (371 on the rerun) with only the control connection; with `only_second` restored temporarily the test failed with child exit code 134 (`SequenceTooShort`). `CrossEncoderRerankerLiveTests` exit 0 with logits identical to Milestone 2 (11.371214 vs -11.240531 / -11.289034, and so on), 20 passages in 299 to 314 ms (328 to 354 on a confirming rerun). No evaluation was run. Evidence: [forked lengths](live-runs/2026-09-13-reranker/live-test-forked-lengths.log), [only_second abort](live-runs/2026-09-13-reranker/live-test-only-second-abort.log), [live test](live-runs/2026-09-13-reranker/live-test-remediation.log).
* Change log — 2026-09-13: cross-encoder separate tokenization and bounded ONNX Runtime calls (RAG-1, Milestone 2 remediation 2)
    * Fixed native memory blow-up and OS memory kills: native pair truncation built every overflow-piece combination (41 GB, 66 GB and exit 137 on inputs the API accepts), and the remediation 1 Shutdown and Latency work bound was false. OnnxCrossEncoderScorer now tokenizes the query and each chunk alone with truncation, padding and special tokens off, and CrossEncoderPairAssembler cuts longest first, adds the special tokens and pad id read from `tokenizer.json`, and pads in Java (Cross-encoder reranker, Input bounds). ONNX Runtime calls are limited to eight 512-token pairs' attention size, because 40 rows of 512 tokens in one call reached 3.9 GB (Latency). `batch-size` now groups chunks rather than fixing the rows of a call.
    * Parity: 320 non-truncating generated pairs identical to the native pair encoding (zero mismatches); truncating pairs at 512 identical in 60 of 60 (both sides 300 to 1,000 tokens, logit difference 0.0) and 100 of 100 (one short side).
    * Live verification — 2026-09-13: the 16-case resource grid (max-length 16 and 512, batch-size 20 and 64, 4,000 and 20,000 characters, `"a "` and CJK, a query and 40 chunks) exited 0 in every child JVM, slowest call 2,105 ms, largest peak footprint 987 MB (1,433 MB for two simultaneous heaviest calls); defaults with 20 chunks of 2,000 characters 282 to 303 ms, 880 MB. `CrossEncoderSeparateTokenizationLiveTests` exit 0 (heaviest cases 962 MB and 973 MB, 2,023 to 2,111 ms and 1,468 to 1,513 ms per call). `CrossEncoderRerankerLiveTests` exit 0 with the three handwritten logits identical (11.371214, 5.7503047, 5.5065556) and 20 passages in 334 to 350 ms; `CrossEncoderForkedLiveTests` exit 0 with every boundary and sweep score identical to remediation 1 and only the lsof control connection. No evaluation was run and no chat model was called. The Scrutiny probe program rerun through this tree (content, heavy, 812-case and 342-case length grids, concurrency with close) exited 0 in every group, at most 1,020 MB for single-threaded groups. Evidence: [resource grid and probe rerun](live-runs/2026-09-13-reranker/live-test-resource-grid.log), [live tests](live-runs/2026-09-13-reranker/live-test-remediation-2.log).
* Change log — 2026-09-13: reranker measurement, not enabled (RAG-1, plan Milestone 3 with Amendment 2)
    * Measured the cross-encoder on set v2 at 10, 20, and 40 candidates (snapshots 248, 249, 250) against a reranking-off reference (snapshot 247, which reproduces 91 question by question); no fallbacks. No row met the selection rule: aggregate hit@5 fell (0.785714 to 0.761905, 0.738095, 0.761905), NVDA hit@5 fell from 0.642857 to 0.428571 in every row, kind-FIGURE questions left the top 5 (nvda-01, nvda-11, and nvda-14 in every row, plus msft-04, msft-12, or both), and the figure slice in the top 5 fell from 12/12 to 10/12, 9/12, and 9/12; the non-figure slice rose by a net one or two questions from a single run each (21/30 to 22/30, 22/30, and 23/30, with questions moving both into and out of the top 5) and AAPL rose from 13/14 to 14/14 (Cross-encoder reranker, Reranker measurement).
    * No default changed: `reranking-enabled` and `cross-encoder.enabled` stay false, `rerank-candidates` stays 20, both floors unchanged. No code change.
    * Live verification — 2026-09-13: `RetrievalEvaluationLiveTests` with the unchanged defaults, exit 0, HYBRID_RRF, hit@5 0.785714, non-figure 21 of 30, rolled back (26 rows, max id 250, before and after). Evidence: [reference snapshot 247](live-runs/2026-09-13-reranker/measurement/snapshot-247-reference-rerank-off.json), [10 candidates, 248](live-runs/2026-09-13-reranker/measurement/snapshot-248-rerank-candidates-10.json), [20 candidates, 249](live-runs/2026-09-13-reranker/measurement/snapshot-249-rerank-candidates-20.json), [40 candidates, 250](live-runs/2026-09-13-reranker/measurement/snapshot-250-rerank-candidates-40.json), [rule table](live-runs/2026-09-13-reranker/measurement/rule-table.txt), per-call scoring times [247](live-runs/2026-09-13-reranker/measurement/latency-247.txt), [248](live-runs/2026-09-13-reranker/measurement/latency-248.txt), [249](live-runs/2026-09-13-reranker/measurement/latency-249.txt), [250](live-runs/2026-09-13-reranker/measurement/latency-250.txt), [floor test](live-runs/2026-09-13-reranker/measurement/live-test.log), [run log](live-runs/2026-09-13-reranker/measurement/run.log).
* Change log — 2026-09-13: windowed passage scoring for the cross-encoder (RAG-1 continuation, plan `plans/2026-09-13-reranker-windows.md`, Milestone 1)
    * Cause of the failed measurement named: 65% of stored chunks are longer than the model window's passage budget and the head cut never saw an answer in the tail (Cross-encoder reranker, Windows; evidence `live-runs/2026-09-13-reranker-windows/truncation-probe-512.txt`).
    * Added `PassageScoring` and windows in CrossEncoderPairAssembler (`windowLength`, `windowStarts`, `windows`, one row per window, the query cut exactly as before), the maximum-over-windows reduction and `scoreWithWindows` in OnnxCrossEncoderScorer, `PairScorer.scoring()`, `FilingReranker.scoring()` read through `FilingRetrievalService.rerankerScoring()` into snapshot `properties.rerankerScoring`, the log field `windows=` on `Cross-encoder scoring completed`, and the properties `rag.retrieval.cross-encoder.passage-scoring` (`max-window`, default; `head` is the previous behaviour; `RAG_CROSS_ENCODER_PASSAGE_SCORING`), `window-overlap-tokens` (64) and `max-windows` (4). Every window is a row under the unchanged per-call attention cap. `reranking-enabled` and `cross-encoder.enabled` stay false; no measurement on the evaluation set (Milestone 2).
    * Live verification — 2026-09-13: `./mvnw -q -o test -Dtest='CrossEncoder*' -Drag.rerank.live=true` exit 0 (`CrossEncoderWindowedScoringLiveTests` plus the existing classes under `head`, whose handwritten logits 11.371214, 5.7503047 and 5.5065556 are bit-identical to the remediation 2 run): a 894-token chunk with the answer at token 622 scored -10.09 under `max-window` against -11.26 under `head` and ranked first of three (`head` tied it with the long distractor); an 886-token chunk with the answer at token 114 scored 3.92034 under both; two calls over 20 chunks of 1,009 tokens gave identical scores and order with `windows=60` logged (`windows=20` under `head`); latency 20 chunks 1,522 to 1,692 ms (head 536 to 543), 40 chunks 3,066 to 3,110 ms (head 1,049 to 1,077) (Latency); the answer-position sweep shows the same sentence scoring +10.3 at the start of a window and -11.1 at token 213 of it, and `max-window` lifting `head`'s -11.26 to +3.45 and +2.25 for answers at tokens 578 and 642 (Windows). `./mvnw -q -o verify` exit 0. Evidence: `live-runs/2026-09-13-reranker-windows/live-test-windows.log`.
* Change log — 2026-09-13: reranker measurement with windowed scoring, not enabled (RAG-1 continuation, plan `plans/2026-09-13-reranker-windows.md` Milestone 2 with Amendment 1)
    * Cause of the head measurement's losses named and tested: with `max-window` scoring on set v2 at 10, 20, and 40 candidates (snapshots 296, 297, 298; 298 with `rerank-timeout-ms` 4,000, 27 of its 42 evaluation calls and its warm-up call over 2,000 ms) and at 20 candidates with `window-overlap-tokens` 224 (299), against a reranking-off reference (295, which reproduces 247 and 91 question by question); no fallbacks. The figure slice is 12/12 in every row (head rows 10/12, 9/12, 9/12), nvda-11 back at rank 5, nvda-14 at 1 or 2, msft-12 at 2, NVDA 8/14 (9/14 with overlap 224) against 6/14 under head, aggregate hit@5 0.761905, 0.785714, 0.809524, 0.809524 against the reference 0.785714. No row met the selection rule: nvda-01, a kind-FIGURE question in the top 5 under the reference, leaves it in every row and msft-04 in every row but 298, NVDA is below 9/14 in every row but 299, and 296 also lowers aggregate and non-figure hit@5; hit@1 fell from 23/42 to 20 to 22. nvda-01's phrase is inside a scored window (not truncation), and its loss within that window is inferred from the ranks, not measured; msft-04's answer sits 393 tokens into its second window under both overlaps and its short neighbour chunk 467 enters only at 40 candidates; nvda-11 recovers to 5, not 1, with its answer 219 tokens into a window (the position-sweep trough). Scoring time 2.1 to 2.5 times the head rows (median 539.5, 1,122, 2,139, 1,323 ms) (Cross-encoder reranker, Reranker measurement, Windowed rows).
    * No default changed: `reranking-enabled` and `cross-encoder.enabled` stay false, `rerank-candidates` 20, `passage-scoring` max-window, `window-overlap-tokens` 64, `max-windows` 4; both floors unchanged. No code change. Documentation corrections from Milestone 1 Scrutiny applied (Windows: Position sensitivity quotes the sweep as logged, Modes names the classes that construct the scorer with `HEAD`; Latency cites only the committed run).
    * Live verification — 2026-09-13: `RetrievalEvaluationLiveTests` with the unchanged defaults, exit 0, HYBRID_RRF, hit@5 0.785714, non-figure 21 of 30, rolled back (31 rows, max id 299, before and after); `./mvnw -q -o verify` exit 0. Evidence: [reference snapshot 295](live-runs/2026-09-13-reranker-windows/measurement/snapshot-295-reference-rerank-off.json), [10 candidates, 296](live-runs/2026-09-13-reranker-windows/measurement/snapshot-296-rerank-candidates-10.json), [20 candidates, 297](live-runs/2026-09-13-reranker-windows/measurement/snapshot-297-rerank-candidates-20.json), [40 candidates, timeout 4,000, 298](live-runs/2026-09-13-reranker-windows/measurement/snapshot-298-rerank-candidates-40-timeout-4000.json), [20 candidates, overlap 224, 299](live-runs/2026-09-13-reranker-windows/measurement/snapshot-299-rerank-candidates-20-overlap-224.json), [rule table](live-runs/2026-09-13-reranker-windows/measurement/rule-table.txt), per-call scoring times [295](live-runs/2026-09-13-reranker-windows/measurement/latency-295.txt), [296](live-runs/2026-09-13-reranker-windows/measurement/latency-296.txt), [297](live-runs/2026-09-13-reranker-windows/measurement/latency-297.txt), [298](live-runs/2026-09-13-reranker-windows/measurement/latency-298.txt), [299](live-runs/2026-09-13-reranker-windows/measurement/latency-299.txt), [retrieval check](live-runs/2026-09-13-reranker-windows/measurement/diagnostic-defaults.txt), [floor test](live-runs/2026-09-13-reranker-windows/measurement/live-test.log), [run log](live-runs/2026-09-13-reranker-windows/measurement/run.log).
    * Documentation corrected — 2026-09-13, after Milestone 2 Scrutiny (no new run, snapshot, or code change; every figure rechecked with a script against the committed snapshots 295 to 299, `latency-295.txt` to `latency-299.txt`, the truncation probe and its source, the database, and token counts from the real tokenizer): 298 had 27 of 42 evaluation calls over 2,000 ms, not 28 (the 28th was the warm-up call), in Latency, What was run, What enabling would take, and the bullet above; 17 questions keep rank and chunk in every windowed row, not 14; NVDA's top-5 loss falls by two thirds, not half; nvda-01's 5.03 is the probe's placement 200 characters before the phrase, not the production window, and its loss within its best window is marked as inferred from the ranks; the rule failures of each row are named where no row qualifying had been put down to msft-04 and nvda-01 alone (Cross-encoder reranker, Enabling; Retrieval Methods, How it is measured); the hit@1 changes are listed in full (nvda-11 had been omitted); chunk 467 is 282 WordPiece tokens (391 is the characters / 4 estimate) with the sentence from character 96; row 299's complete top-5 changes are listed; the 2,000 ms margin is qualified as measured on sequential calls only. In Windows (Why, text from Milestone 1): exact WordPiece lengths are lower than the stored estimate, not higher; nvda-11's chunk is 854 tokens, not 820; the 65% is against 469 tokens; msft-05 is the one head top-5 loss that was not a truncation case (also in the Windowed rows cause bullet, which had omitted msft-04 as well). `run.log` corrected to match, with a dated note at its end.
