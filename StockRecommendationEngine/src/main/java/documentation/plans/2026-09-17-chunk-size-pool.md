# Plan: 250-word chunks with a tuned candidate pool, reranker off and on (follow-up to the chunk-size plan)

Orchestrated build per CLAUDE.md, on branch `chunk-size` (worktree `java-projects-chunk-size`), stacked on plan
`2026-09-17-chunk-size.md`. Build: `set -a && source .env && set +a && ./mvnw -q -o verify`; never export the
application enable flags or `RAG_*` overrides in a shell that runs tests. Validators run one at a time. No chat model
call; rebuilds and evaluation runs make embedding calls only.

## Why

- The first measurement rebuilt the store at 1,000 / 125 characters and kept `candidate-count` and
  `keyword-candidate-count` at 40 while the store grew from 569 to 1,917 chunks, so the pools covered a smaller share of
  the store; aggregate hit@5 and MRR were below the baselines and nvda-04's piece stayed at fused position 47, outside
  the pool (RAG.md, Chunk size measurement). On the 4,000-character store, `candidate-count` 200 with 40 rerank inputs
  recorded the highest hit@5 so far (snapshot 947), and reranking 20 short chunks took a median 266 ms against 1,175.5
  ms (latency of 1614 and 1612). Whether a larger pool changes the small-chunk result is unmeasured.
- Jay (2026-09-17): test chunks of 250 words with the candidate counts adjusted for the best result, reranker off and
  on, 4,000 ms rerank timeout, latency checked, every event logged.

## Assumptions the Orchestrator made from Jay's spoken instruction (stated so they can be corrected)

- "250 words" is applied through the character-based chunker: the store holds 1,486,993 characters in 225,033
  whitespace-separated words (6.608 characters per word, measured 2026-09-17), so 250 words is about 1,652 characters;
  the size is `chunk-max-chars` 1,650. The chunker's rule is unchanged (a word-based rule would be a second factor).
- Overlap 250 characters: at least the longest accepted phrase (181 characters), so a phrase that crosses a chunk end
  lies whole in the next chunk. The first plan's 125 split aapl-13.
- "Candidates adjusted dynamically" is a frozen grid chosen on tuning questions and tested once on held-out questions,
  as in plan `2026-09-14-rerank-blend.md`, so the choice is not fitted to the questions that judge it.
- The evaluation set is not changed. aapl-08's accepted phrase is held by no chunk of a store parsed by the current
  parser because of its section key (Follow_Ups RAG-29); it is a miss in every run of this plan on either store, and
  the phrase gate below exempts exactly that phrase.

## Frozen design (written before any run of this plan)

- Stores: A = 4,000 / 500 (the current store, `sections-v2-context-v2-chunk4000-500`); B = 1,650 / 250, all 13 filings
  rebuilt in one pass. Set v2, window 10, hybrid on, fusion defaults, trace on, one session.
- Grid, on each store: pools `candidate-count` = `keyword-candidate-count` in {40, 100, 200, 250}; reranking off, and
  reranking on with `rerank-candidates` 40 and `rerank-timeout-ms` 4,000 (cross-encoder loaded in every run, as in the
  earlier paired runs). Sixteen grid runs. Plus one latency reference on store A: the default reranked configuration
  (pool 40, `rerank-candidates` 20, timeout 2,000). The default reference is grid run A / 40 / off.
- Reproduction first: A / 40 / off must equal snapshot 1615 per question (rank and matched chunk id; the store has not
  changed since); otherwise stop before anything else.
- Phrase gate on store B: every accepted phrase of set v2 except aapl-08's is held by a stored chunk (56 of 57, the
  one not held being aapl-08/0). Otherwise no grid run on B; roll back and report.
- Split: the held-out 14 of plan `2026-09-14-rerank-blend.md` (aapl-04, aapl-05, aapl-11, aapl-12, aapl-14, msft-02,
  msft-03, msft-04, msft-07, msft-10, nvda-04, nvda-08, nvda-10, nvda-11); the other 28 are tuning questions. These 14
  were used once before, for the blend test; no pool or size was ever chosen on them.
- Choice, on the 28 tuning questions only, separately for reranking off and on, and separately per store: the pool
  with the highest tuning hit@5; ties by higher tuning MRR, then the smaller pool. A reranked run with any fallback is
  labelled, repeated once, and excluded from the choice if the repeat also records a fallback.
- Held-out test, once per chosen store-B point (one off, one on), on the 14 held-out questions: PASS if held-out hit@5
  is at least that of the default reference A / 40 / off and at least that of store A's chosen point of the same
  reranker state. A FAIL is the result; no second grid, size, overlap, or split follows in this plan.
- Reported for all 42 questions, for the default reference, the four chosen points, and the latency reference: hit@1,
  hit@3, hit@5, MRR, slices, per-ticker hit@5; the per-question rank table with matched chunk and a rank histogram;
  every question not at rank 1 with its matched chunk and the chunks ranked above it (ids, section, first 80
  characters, from the chunk export of the store the run used); questions entering and leaving the top 5 for B's
  chosen points against the default reference and against A's chosen point of the same state; nvda-02 and nvda-04's
  rank, matched chunk, fused position, and rerank input membership in every grid run.
- Latency, from the application logs of the same runs: per question retrieval elapsed and rerank elapsed (median, p95,
  maximum), whole-run wall time, fallbacks, for all seventeen runs, as observed values with no cause stated. The
  chosen reranked point on store B is set against the latency reference.
- Storage at three points (before, rebuilt, after the rollback) with the first plan's `storage.sql`.
- Events: `run.log` records, with wall-clock times, every application start and stop with its settings, every
  rebuild response, every evaluation call with its snapshot id, every fallback, every stop condition checked, and
  anything unexpected.
- Aftermath: the store is always rebuilt back to 4,000 / 500 at the end, and a post-rollback run at A / 40 / off must
  equal this plan's A / 40 / off per question in rank and matched chunk content. Defaults stay unchanged. Whether to
  adopt a size and pool is a DECISION item for Jay (Follow_Ups RAG-30) with these numbers.
- Evidence rule RAG-14: claims file ids C-1601 to C-1999; one factor per experiment claim (store within a pair of
  equal settings; pool within a store); no sentence states why a rank or a latency changed.

## Milestone 1: pool bounds, grid on both stores, choice, held-out test, rollback

Scope: raise the validation maximum of `rag.retrieval.candidate-count` and `keyword-candidate-count` from 200 to 400
(properties, `application.yaml` comments, tests, RAG.md), committed and built before any run; then the frozen design
above, end to end; evidence under `documentation/live-runs/2026-09-17-chunk-size-pool/`; scripts for the grid table,
choice, held-out test, rank tables, latency, and storage; `claims.json`; a generated RAG.md section ("Chunk size and
candidate pool"); Follow_Ups RAG-27 updated, RAG-30 added; change-log bullet; CLAUDE.md loop history; PRD row.

Correctness contract:
- G1. With no overrides the application behaves as before; 250 binds for both pools and 401 is refused at startup.
- G2. A / 40 / off reproduces 1615 per question, or the milestone stops.
- G3. Every grid run's recorded `properties` differ from its pair's only in the factor the design varies (a committed
  diff); `rerank-timeout-ms`, which snapshots do not record, is taken from the start command in `run.log`.
- G4. The phrase gate is evaluated from the application's evidence against store B and by a direct query; its
  outcome decides whether B's grid runs exist.
- G5. The split equals the frozen list; the choice uses tuning questions only; held-out metrics are computed only
  for the chosen points and the references the test names; the outcome is PASS or FAIL by the frozen rule, naming
  every held-out question that decides it.
- G6. The rank table, histogram, not-at-rank-1 listing, entering and leaving lists, and nvda-02 / nvda-04 rows are
  computed from committed snapshots and traces by a committed script; a reviewer's recomputation matches.
- G7. Latency and storage are computed from committed log windows and SQL output by committed scripts; every run with
  a fallback is labelled.
- G8. After the rollback the store is uniform at `sections-v2-context-v2-chunk4000-500` with 569 chunks, and the
  post-rollback run equals this plan's A / 40 / off per question in rank and matched chunk content.
- G9. `run.log` holds the events the design lists, in time order.
- G10. No sentence states a cause; rule and test outcomes are claims with the numbers they rest on; no default changes.
- Commands: `./mvnw -q -o verify` with exactly the two known RetrievalEvaluationSetTests failures on aapl-08 (RAG-29)
  and no other; `lsof -iTCP:8081 -sTCP:LISTEN` empty after the last stop.
- Out of scope: the evaluation set, the parser, `searchTopK`, any default, a word-based chunker, a second size or
  overlap, rerank inputs above 40.
- User-facing flow (UT): the committed snapshots of the default reference, the four chosen points, and the
  post-rollback run read back equal from `GET /api/rag/evaluate/{id}`; three rank-table rows re-derived; the store
  uniform at 4,000 / 500 with 569 chunks; `POST /api/rag/retrieve` for nvda-04 at the defaults returns what the
  post-rollback run records.

## Status

- 2026-09-17: plan written and frozen before any run; Jay instructed to proceed (250-word chunks, pools adjusted for
  the best result, reranker off and on, 4,000 ms timeout, latency checked, events logged).
- 2026-09-17, Milestone 1 handed off (b18fb28 bounds, 477a827 store A, e9f294e store B, d0be553 rollback, 2a53bd4
  checker, c98173b write-up): no stop condition triggered; 18 runs (1777 to 1794), no rerank fallback; the phrase gate
  held on store B (56 of 57, aapl-08 exempt); the held-out test of store B's chosen points is FAIL with reranking off
  and FAIL with reranking on; the store is back at 4,000 / 500 (569 chunks, every chunk's content md5 equal to the
  store before the rebuild) and 1794 equals 1777 per question; defaults unchanged; decision RAG-30 for Jay.
- 2026-09-17, Scrutiny round 1: every number, choice, held-out outcome, listing, latency and storage value, and the
  rollback equality recomputed and equal; FAIL on the evidence rule (an inferred claim with clauses its premises did
  not support; two per-question equality claims resting on text files; a sentence misdescribing the plan's Reported
  list). Remediation round 1 (fdfbe05) added a test-scope `questionEquality` check so both equalities are derived by
  `verify`. Its scoped re-check failed on three narrow gaps of the same class; remediation round 2 (767a4f8) audited
  all ten inferred claims clause by clause and refused duplicate question ids in the new check; the scoped re-check
  PASSED with two low notes (the choice sentences omit the rule's fallback clause, zero fallbacks being stated in
  `runs.txt`; an older check type, `candidateLists`, shares the duplicate-id weakness, no committed snapshot triggers
  it). Lesson repeated from earlier loops: an inferred sentence needs a premise or a named source per clause, and a
  blanket sentence about one's own claims is itself a claim. UT pending.
- 2026-09-17, UT: PASS. Snapshots 1777, 1778, 1782, 1786, 1790, 1794 read back equal to the committed exports in
  metrics, properties, and every question's rank and matched chunk; every rank-table cell of those five grid columns
  and two histograms re-derived; hit@5 over the 42 and over the 14 held-out questions recomputed equal to `grid.txt`;
  the store uniform at `sections-v2-context-v2-chunk4000-500` with 569 chunks and 1,486,993 characters; retrieve for
  nvda-04 at the defaults returns no chunk holding the answer, as 1794 records; defaults unchanged (56 candidates
  retrieved); no ERROR line; port 8081 free. Milestone 1 closed. Open for Jay: RAG-30 (adopt a size and pool or not),
  RAG-29 (aapl-08 section key, which keeps `verify` red on this database), and the merge of branch `chunk-size`.
