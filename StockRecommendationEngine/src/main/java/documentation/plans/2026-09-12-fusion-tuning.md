# Plan: fusion tuning for figure-like queries (Follow_Ups RAG-12)

Orchestrated build per CLAUDE.md. Two serial milestones with contracts written before code. Branch:
`fusion-tuning` from `hybrid-retrieval` (b24b582 plus the CLAUDE.md history commit); PR #12 must merge before
this one, or this PR is rebased onto main after it. Build: `set -a; source .env; set +a; ./mvnw -q verify`; never
export the application enable flags in the build shell. Token cost: keyword search costs nothing; each
evaluation run embeds 30 questions with the small embedding model; the grid in Milestone 2 needs about six
runs (roughly 180 embeddings). No chat model is called.

## The problem being fixed

Hybrid retrieval (snapshot 34) lifted hit@5 to 0.633 but two MSFT figure questions regressed (msft-04 rank 1 → 8,
msft-01 6 → 10), and the exact NVDA figure "215,938" lands at rank 3 behind chunks that only share common
words. Two causes: (1) the keyword leg is an OR query ranked by `ts_rank_cd`, so a chunk matching many common
query words ("fiscal", "revenue", "2026") outranks the one chunk that matches the rare number; (2) the two legs
carry equal weight with k = 60, so a keyword rank-1 adds only 1/61 and cannot overcome two vector neighbours.

## Design

- **Figure leg**: a third ranking, built only when the query contains numeric tokens (digits, optionally with
  inner commas or periods, e.g. `215,938`, `40.4`, `2025`): an AND tsquery of those numeric tokens, same
  eligibility pool, same `ts_rank_cd` ordering, same cosine similarity per row. A chunk must contain every
  numeric token to appear in it. Years alone (a four-digit token between 1900 and 2100 with no other numeric
  token) do not trigger the leg, since a year matches most chunks.
- **Weighted RRF**: fused score = Σ weight_leg / (k + rank_leg) over the legs containing the chunk, with
  properties `rrf-vector-weight` (default 1.0), `rrf-keyword-weight` (default 1.0), `rrf-figure-weight`
  (default 0.0 = leg off), `rrf-k` (existing, 60). Ties break as today (vector similarity, chunk id). With the
  defaults the fused ranking is identical to snapshot 34's, which Milestone 1 proves.
- **Selection rule for Milestone 2** (extends the flip rule used for RAG-2): among the measured configurations
  choose the one with the highest hit@5 such that, compared with snapshot 35 (vector-only) and snapshot 34
  (current hybrid): no ticker's hit@5 decreases against either; no FIGURE question that was in the top 5 under
  either snapshot leaves the top 5; hit@5 is at least 0.633333. If none qualifies, keep the current defaults and
  record why, with the best runner-up. The floor rule is unchanged (winner's hit@5 − 0.1, rounded down to 0.05,
  never lower than the current 0.50).
- Everything else (diversify, reranker, topK, strategy names, similarity meaning, fallback) is untouched;
  `HYBRID_RRF` stays the strategy name whether or not the figure leg ran; the snapshot `properties` record the
  three weights and whether the figure leg was active for that run.

## Milestone 1: figure leg and weighted fusion, behaviour-preserving by default

Scope: `FilingRetrievalRepository.figureTerms(String query)` (public static, returns the AND tsquery string of
numeric tokens or empty per the year rule) and `findFigureChunks(query, queryEmbedding, filter, candidateCount)`
(reuses the eligibility CTE and `to_tsquery('english', :terms)` with the AND string; parameterised); properties
`rrfVectorWeight`, `rrfKeywordWeight` (0..10, default 1.0), `rrfFigureWeight` (0..10, default 0.0);
`FilingRetrievalService` runs the figure leg when hybrid is on, the figure weight is positive, and `figureTerms`
is non-empty; `fuse` takes the legs with weights; `reciprocalRankScores` takes a weight per ranking;
evaluation snapshot `properties` gain `rrfVectorWeight`, `rrfKeywordWeight`, `rrfFigureWeight`. A figure-leg
failure falls back to the two-leg fusion with a WARN (never to vector-only unless the keyword leg failed too).

Correctness contract:
- C1. `figureTerms`: "fiscal 2026 revenue 215,938 up 65%" → `'2026' & '215,938' & '65'` in query order (a
  year is excluded only when it is the sole numeric token); "risks in fiscal 2025" → empty (year only);
  "Greater China net sales 64,377" → `'64,377'`; "no numbers here" → empty; tokens quoted exactly as
  `keywordTerms` quotes them.
- C2. Weighted fusion with a mocked repository: vector [A, B, C], keyword [C, D, A], figure [B], k = 60,
  weights 1.0 / 1.0 / 2.0: A = 1/61 + 1/63, C = 1/63 + 1/61, B = 1/62 + 2/61, D = 1/62 → order B, then A/C by
  similarity then id, then D; with figure weight 0.0 the figure leg is not queried and the order equals the
  unweighted result [A/C, B/D order as before]; weights 0.5 / 1.0 / 0.0 scale the vector contributions.
- C3. DB test: with chunks inserted for a unique ticker, `findFigureChunks` for a query with two numeric
  tokens returns only chunks containing both numbers, ranked by `ts_rank_cd`, each with cosine similarity
  within 1e-6; a query whose numeric tokens appear in no chunk returns empty; eligibility (EMBEDDED, latest,
  section) matches the other paths.
- C4. Behaviour preservation: with the default properties the service's fused order for the mocked legs equals
  the Milestone 2 (RAG-2) expectations, all existing `FilingRetrievalServiceTests` pass unchanged except for
  constructor or property additions, and the opt-in live evaluation with defaults reproduces snapshot 34's
  hit@5 0.633333 and MRR 0.436667 (run once; it rolls back).
- C5. Snapshot properties carry the three weights; `POST /api/rag/evaluate` unchanged otherwise.
- Commands: `set -a && source .env && set +a && ./mvnw -q -o verify` exit 0;
  `./mvnw -q -o test -Dtest=FilingRetrievalServiceTests,FilingRetrievalRepositoryTests,FilingRetrievalRepositoryKeywordTermsTests,RetrievalEvaluationServiceTests`;
  the live test once with `-Drag.evaluation.live=true`.
- Out of scope: choosing weights, docs beyond property rows.
- User-facing flow (UT Validator): app up; `POST /api/rag/retrieve` `{"ticker":"NVDA","query":"fiscal 2026
  revenue 215,938 up 65%","topK":5}` returns HYBRID_RRF with the same five chunk ids and order as under
  b24b582 (806, 880, 802, 807, 882); `GET /api/rag/evaluate/34` still returns the stored figures; a stopword
  query still returns FILTERED_VECTOR.

## Milestone 2: measure the grid, choose by rule, document

Scope: run `POST /api/rag/evaluate` for each configuration by restarting the app with property overrides
(environment variables `RAG_RETRIEVAL_RRF_K`, `RAG_RETRIEVAL_RRF_KEYWORD_WEIGHT`, `RAG_RETRIEVAL_RRF_FIGURE_WEIGHT`
through Spring's relaxed binding), at least these six: (k 60, kw 1.0, fig 0.0) = current; (60, 1.0, 1.0);
(60, 1.0, 2.0); (60, 0.5, 1.0); (30, 1.0, 1.0); (60, 0.5, 2.0). Record every snapshot id. Apply the selection
rule; set the winning defaults in `application.yaml` and the properties class; apply the floor rule.
Documentation: RAG.md "Hybrid Retrieval" gains a "Fusion tuning" subsection (grid table with snapshot ids,
hit@1/3/5, MRR, per-ticker hit@5, FIGURE-question top-5 count; the rule and the chosen configuration; per-question
rank changes winner vs 35 and vs 34; the msft-04 / msft-01 / nvda-01 outcomes named); Follow_Ups RAG-12 DONE
(or narrowed with the measured result if no configuration qualifies); the flip rule in the RAG-2 plan is
superseded by this plan's rule, say so in RAG.md; PRD phase 3 row hit@5 updated; evidence under
`documentation/live-runs/2026-09-12-fusion-tuning/` (all snapshot JSONs, run.log with commands and the
decision). If the default weights change, one sentence in Agent_Harness.md's searchFilings row.

Correctness contract:
- C1. RAG.md's grid table matches the stored snapshots by id (psql), each snapshot's `properties` carrying the
  configuration it claims (k, weights, hybrid).
- C2. The defaults in application.yaml and the properties class equal the configuration the rule selects from
  that grid, and the doc shows the rule applied row by row (which rows fail which criterion).
- C3. `./mvnw -q -o verify` exit 0; the opt-in live floor test passes with the final defaults.
- C4. Follow_Ups, PRD, evidence, and (if applicable) Agent_Harness updated as described.
- Out of scope: new fusion methods beyond weights and the figure leg, set changes, reranking.
- User-facing flow (UT Validator): `GET /api/rag/evaluate/{id}` for the winning and the two reference snapshots
  match the table; `POST /api/rag/retrieve` for the NVDA figure query without `hybrid` returns HYBRID_RRF with
  the "215,938" chunk at the rank the doc states for the winner; the MSFT employee-count question from the set
  (msft-04's text, copied from the JSON) returns its expected chunk within the top 5 if the winner's table says so.

## Gate rules

Both validators must pass before the next milestone; findings go to a fresh Worker; at most two remediation
rounds per milestone, then escalate. Validators never see the Worker's report or each other's output. Scrutiny
and UT run one after the other (shared Maven target directory).
