# Plan: hybrid keyword plus vector retrieval (Follow_Ups RAG-2)

Orchestrated build per CLAUDE.md. Three serial milestones with contracts written before code. Branch:
`hybrid-retrieval` from `main` (cc8e85c). Workers commit with specific `git add` paths, never push. Build:
`set -a; source .env; set +a; ./mvnw -q verify`; never export the application enable flags in the build shell.
Token cost: keyword search costs no model tokens; each evaluation run still embeds 30 questions with the small
embedding model. No chat model is called anywhere in this plan.

## Why

Baseline snapshot 13 (RAG.md, Retrieval Evaluation) has hit@5 0.60 with the misses concentrated on figure
questions whose answers are table rows ("Revenue $ 215,938 $ 130,497 Up 65%", "Greater China 64,377 (4) %")
and on exact terms ("OpenAI", "Data Center") that an embedding blurs. PostgreSQL full-text search finds
those tokens exactly. Fusing both rankings should lift the figure questions without hurting the narrative ones;
the evaluation set decides.

## Design constraints (apply to every milestone)

- `RetrievedFilingChunk.similarityScore` keeps meaning the cosine similarity between the query embedding and
  the chunk, for every returned chunk, whatever path found it: the keyword query computes it too. The
  recommendation loop's confidence uses the mean similarity of cited passages and must not change meaning.
- Fusion is Reciprocal Rank Fusion: fused score = sum over the lists in which the chunk appears of
  1 / (k + rank), rank 1-based within that list, k configurable (default 60). Ties break by vector similarity
  descending, then chunk id ascending. The existing diversify step and topK cut apply after fusion.
- Eligibility is identical on both paths: same ticker, EMBEDDED filings, latest-filing-per-type policy, type,
  date, and section filters; the keyword path reuses the same CTE so the two candidate sets are drawn from the
  same pool.
- The keyword query is built from the user text by the repository, never by string concatenation into SQL:
  parameterised `to_tsquery('english', :terms)` where `:terms` is the OR-join of the distinct alphanumeric
  tokens of length 2 or more (letters, digits, commas and periods inside numbers kept, everything else split),
  each token quoted for tsquery; stopwords fall out through the english configuration. An empty term list
  means "no keyword search" (vector only). A keyword-path SQL failure is logged and falls back to vector-only
  with the FILTERED_VECTOR strategy; retrieval never fails because of the keyword path.
- Disabled by default until Milestone 3 measures it. The retrieval request and the evaluation endpoint accept an
  optional override so the two strategies can be compared without a restart.

## Milestone 1: keyword search foundation

Scope: migration V9 adds a stored generated column `content_tsv tsvector GENERATED ALWAYS AS
(to_tsvector('english', content)) STORED` on `sec_filing_chunks` with a GIN index; a repository method
`findKeywordChunks(String query, float[] queryEmbedding, FilingRetrievalFilter filter, int candidateCount)`
returning `RetrievedFilingChunk` rows ordered by `ts_rank_cd(content_tsv, tsquery)` descending, then vector
similarity descending, then chunk id, each row carrying the cosine similarity as `similarityScore`; a
package-private static `keywordTerms(String query)` producing the tsquery term string (or empty).

Correctness contract:
- C1. `keywordTerms`: "What were Apple's Greater China net sales in fiscal 2025, and how did they compare?"
  yields distinct tokens including `greater`, `china`, `net`, `sales`, `fiscal`, `2025` (case-folded), joined
  with ` | `, each token quoted so `apple's` cannot break the query; a query of only stopwords or punctuation
  yields an empty string; a token such as `64,377` or `$40.4` keeps its digits (the exact form is the
  Worker's choice, but the DB test in C3 must find the row).
- C2. Against PostgreSQL: with chunks inserted for a unique test ticker, a query containing a figure that
  appears verbatim in exactly one chunk ranks that chunk first; a query whose terms appear in no chunk returns
  an empty list; the latest-filing-only policy, section filter, and EMBEDDED status filter exclude rows exactly
  as `findSimilarChunks` does (reuse the existing FilingRetrievalRepositoryTests fixtures and insert helper);
  every returned row's `similarityScore` equals the cosine similarity to the supplied embedding within 1e-6.
- C3. Migration V9 applies on the existing database (Flyway, `./mvnw -q verify` runs it) and the index exists:
  the DB test asserts `to_regclass('idx_sec_filing_chunks_content_tsv')` (or the chosen name) is not null.
- C4. Existing `findSimilarChunks` behaviour and tests unchanged; the generated column is populated for all
  existing rows (assert `count(*) where content_tsv is null` is 0 for the test ticker's rows and for the store).
- Commands: `./mvnw -q verify` exit 0 with .env exported; `./mvnw -q test -Dtest=FilingRetrievalRepositoryTests`.
- Out of scope: fusion, service changes, properties, endpoints, docs beyond a migration note in RAG.md.
- User-facing flow: none (Scrutiny Validator only).

## Milestone 2: fusion in the retrieval service, optional override

Scope: `FilingRetrievalProperties` gains `hybridEnabled` (default false), `keywordCandidateCount` (default 40,
20..200), `rrfK` (default 60, 1..1000); `RetrievalRequest` gains optional `Boolean hybrid` (null = property);
`FilingRetrievalService.retrieve` runs the keyword path when hybrid resolves true and the term string is
non-empty, fuses by RRF, then diversifies and cuts to topK as today; `retrievalStrategy` is `HYBRID_RRF`
(`HYBRID_RRF_RERANKED` with a reranker) when the keyword path contributed, else `FILTERED_VECTOR` (also on
keyword-path failure, with a WARN log); `candidatesRetrieved` reports the fused set size.
`POST /api/rag/evaluate` accepts optional `?hybrid=true|false`, passes it on every request, and records
`hybrid` in the snapshot's `properties`. The RAG specialist's searchFilings tool inherits the property default
(no change to RecommendationTools).

Correctness contract:
- C1. Fusion arithmetic with a mocked repository: vector list [A, B, C], keyword list [C, D, A], k = 60:
  A = 1/61 + 1/63, C = 1/63 + 1/61, B = 1/62, D = 1/62; A and C tie and are ordered by higher vector
  similarity then lower chunk id; B and D tie likewise; a chunk present only in the keyword list is retained
  with its vector similarity; the final list respects topK after diversify.
- C2. `hybrid` resolution: request true forces hybrid even when the property is false; request false forces
  vector-only even when the property is true; null follows the property; with hybrid on and an empty term
  string (stopword-only query) the strategy is FILTERED_VECTOR and the keyword repository is not called.
- C3. A `DataAccessException` from the keyword path yields the vector-only result with strategy
  FILTERED_VECTOR and a WARN log; the exception does not propagate.
- C4. Evaluation: `POST /api/rag/evaluate?hybrid=true` stores `properties.hybrid` = true and every retrieval
  request carried `hybrid` true (service test with a captor); without the parameter `properties.hybrid` is
  null and requests carry null.
- C5. Reranker validation still holds on the fused candidate list (existing `validateRerankedEvidence` sees
  the fused, diversified candidates).
- Commands: `./mvnw -q verify` exit 0; `./mvnw -q test
  -Dtest=FilingRetrievalServiceTests,RetrievalEvaluationServiceTests,FilingRetrievalRepositoryTests`.
- Out of scope: measuring, changing the default, docs beyond property and parameter mentions.
- User-facing flow (UT Validator): app up with .env, `INTEGRATION_ACCESS_TOKEN` (32+ chars), `SERVER_PORT=8081`;
  `POST /api/rag/retrieve` with `{"ticker":"NVDA","query":"fiscal 2026 revenue 215,938 up 65%","topK":5,"hybrid":true}`
  returns 200, `retrievalStrategy` HYBRID_RRF, and a result whose content contains "215,938"; the same body
  with `"hybrid":false` returns FILTERED_VECTOR; the same body without `hybrid` returns FILTERED_VECTOR (property
  default off); `POST /api/rag/evaluate?hybrid=true` (token) returns 200 with `properties.hybrid` true and
  `retrievalStrategy` HYBRID_RRF; `POST /api/rag/evaluate` without the parameter returns FILTERED_VECTOR;
  a stopword-only query (`"query":"the and of"`) with hybrid true returns 200 with FILTERED_VECTOR. At most two
  evaluation POSTs.

## Milestone 3: measure, decide the default, document

Scope: run the evaluation with hybrid off and on (two snapshots, cite ids), record a comparison table in RAG.md
(hit@1/3/5, MRR, per-ticker hit@5, misses that changed either way, with the rank each miss moved from and to),
then decide the default by rule: enable `rag.retrieval.hybrid-enabled` by default only if hit@5 improves and no
ticker's hit@5 decreases; otherwise keep it off and say why. If enabled, raise `rag.evaluation.min-hit-at-5` to
the new hit@5 minus 0.1 rounded down to 0.05 (never lower it). Note the token effect: none for keyword search.
Update Follow_Ups RAG-2 (DONE or narrowed with the measured result), RAG-1/RAG-11 text where the comparison
changes their rationale, the PRD phase 3 or 9 row, and evidence under
`documentation/live-runs/2026-09-12-hybrid-retrieval/` (both snapshots' JSON, run log).

Correctness contract:
- C1. RAG.md's comparison table matches two stored snapshots by id (psql), one with `properties.hybrid` false
  or null and one true, both `setVersion` v1 and `questionCount` 30.
- C2. The default in application.yaml and `FilingRetrievalProperties` matches the rule applied to those two
  snapshots, and the doc states the rule and the numbers it used.
- C3. `./mvnw -q verify` exit 0; the opt-in live floor test passes with the final default
  (`-Drag.evaluation.live=true`).
- C4. Follow_Ups, PRD, and evidence updated as described; the harness's confidence note in Agent_Harness.md
  gains one sentence that hybrid-found passages can carry lower vector similarity, so raw confidence may dip.
- Out of scope: set v2 (RAG-11), reranking (RAG-1), parser fixes (RAG-7..RAG-9).
- User-facing flow (UT Validator): `GET /api/rag/evaluate/{id}` for both cited ids matches the RAG.md table;
  `POST /api/rag/retrieve` for the NVDA query above without `hybrid` returns the strategy the documented
  default implies.

## Gate rules

Both validators must pass before the next milestone; findings go to a fresh Worker; at most two remediation
rounds per milestone, then escalate. Validators never see the Worker's report or each other's output. Scrutiny
and UT run one after the other (shared Maven target directory).
