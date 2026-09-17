# Plan: smaller filing chunks, measured on evaluation set v2 (RAG-15, lever 2)

Orchestrated build per CLAUDE.md. Branch `chunk-size`, stacked on `housekeeping-2026-09-16` (49f5483, one documentation
commit ahead of `main`), in its own git worktree so the housekeeping branch's uncommitted edits are never staged
(`git worktree add ../java-projects-chunk-size -b chunk-size 49f5483`; `.env` and `models/` copied or symlinked into
the worktree's `StockRecommendationEngine/`). Build: `set -a && source .env && set +a && ./mvnw -q -o verify`; never
export the application enable flags or `RAG_*` overrides in a shell that runs tests. Validators run one at a time. No
chat model call in this plan; rebuilds and evaluation runs make embedding calls only (`text-embedding-3-small`).

## Why

- nvda-04's accepted chunk 754 (721 tokens) holds its answer at tokens 547 to 567, wholly inside the second scored
  window, and the current reranker still scores that window -0.65 against 0.735 for chunk 755's head window, the
  employee paragraph that follows the answer (observed: `live-runs/2026-09-14-recall-one-factor/evidence-947.json`,
  chunk 754 `windowScores` and `rankedAbove`). Under default settings 754 is not a rerank input at all (fused position
  54; RAG-15). The second reranker model kept the same order (`live-runs/2026-09-15-reranker-gte/evidence-1368.json`,
  754 seventh, 755 first). Two models agreeing points at the passage shape, not the model.
- The unrun RAG-25 diagnostic (`CrossEncoderAnswerVisibilityLiveTests`), run in this session for nvda-04 and nvda-02
  only (output not yet committed; Milestone 1 commits a full run): re-splitting each answer's section in the JVM at
  1,000 characters ranks the piece holding nvda-04's answer first in its section pool (score 4.34) and at 500 first
  (4.87), against second at the stored 4,000 (-0.65) and fourth at 2,000 (-2.68); nvda-02's piece ranks first at 1,000
  and 500, first at 4,000, third at 2,000. Re-cutting 754 to start 200 characters before the phrase scores 5.16.
  These are in-section reranker ranks only; they say nothing about the vector or keyword legs, which only a rebuilt
  store shows. Jay chose (2026-09-17) to pursue chunk size before recall or the model.

## Boundaries and design constraints

- One factor: chunk size, with the chunker's rule unchanged (sentence and paragraph boundary preference, minimum end at
  half the size) and the overlap scaled with the size as the diagnostic scales it (size / 8, integer division: 4,000 /
  500, 2,000 / 250, 1,000 / 125, 500 / 62). Retrieval defaults (fusion weights, `candidate-count` 40,
  `rerank-candidates` 20, reranking off, diversification, window 10) are not changed by this plan.
- Exactly one rebuild pass at one new size, chosen by the frozen rule in Milestone 1. No second size is tried in this
  plan; if the chosen size fails the selection rule, the store is rebuilt back at 4,000 / 500 and that is the result.
- The whole store is rebuilt in one pass (13 filings: 10-K, 10-Q, and 8-K of AAPL, MSFT, NVDA); no evaluation runs on
  a store whose filings carry different processing versions.
- A rebuild re-downloads each filing from SEC (`SEC_USER_AGENT` from `.env`), re-parses, re-chunks, and re-embeds.
  Store today: 569 chunks, 1,486,993 characters (about 370,000 embedding tokens); a rebuild at 1,000 / 125 embeds
  about 1.15 times that, one embedding call per chunk (about 1,900 calls at 1,000 characters). No chat model.
- A rebuild replaces chunk ids. Evidence reports for the baseline snapshots are generated and committed before the
  rebuild; committed JSON stays valid, but `GET /api/rag/evaluate/{id}/evidence` for any pre-rebuild snapshot reports
  against the new store afterwards (`heldByStoredChunk` and chunk lookups), which RAG.md must say.
- The recommendation tool retrieves `searchTopK` chunks into the prompt; at 1,000 characters each chunk carries about a
  quarter of today's text. Changing that top-k or the prompt is out of scope; if the new size is kept, it is recorded
  as a DECISION item for Jay.
- Evidence rule RAG-14 applies to every write-up: claims in `claims.json`, generated blocks in RAG.md, labels on every
  claim. Claim ids: Milestone 1 uses C-1001 to C-1199, Milestone 3 uses C-1201 to C-1499 (the highest id in use is C-971).
- Documentation conventions: bullet style, dated change-log bullet per milestone, live evidence under
  `documentation/live-runs/2026-09-17-chunk-size/`, Follow_Ups items with stable ids, PRD phase table and CLAUDE.md
  loop history at the end.

## Frozen rules (written before any run of Milestone 1 or any rebuild)

Size choice (Milestone 1, from the in-JVM chunkSize experiment over every accepted phrase of set v2, current model,
windowed scoring at the application defaults):
- For each size in {2,000, 1,000, 500} count the accepted phrases whose holding piece ranks first in its own section
  pool (`bestRank` 1), and separately the phrases with `piecesHoldingPhrase` 0 (a phrase split by a piece boundary).
- A size with any split phrase is excluded.
- Among the remaining sizes, choose the one with the most first-ranked phrases; on a tie choose the larger size. If no
  remaining size has more first-ranked phrases than the stored 4,000, the plan stops after Milestone 1 with no rebuild.

Selection rule (Milestone 3, against the default reference, reranking off):
- Reference B0: a fresh traced snapshot at the stored size with the application defaults, run in the same session
  before the rebuild. B0 must reproduce snapshot 598 per question (rank and matched chunk id); otherwise stop.
- Candidate N0: the same settings on the rebuilt store, in the same session.
- R1 (hard gate): every accepted phrase of set v2 (57 phrases) is held by at least one stored chunk after the rebuild
  (evidence report `heldByStoredChunk` true for all). A failure rolls the store back without running N0.
- R2: hit@5 of N0 is at least hit@5 of B0.
- R3: MRR of N0 is at least MRR of B0.
- R4: every question that leaves the top 5 (in B0's top 5, not in N0's) is named, and their count is at most the count
  of questions entering.
- R5: both floors hold in N0 (`min-hit-at-5` 0.65, `min-non-figure-hit-at-5` 0.60), by the opt-in live test.
- Meets R1 to R5: the rebuilt store stays and the new size becomes the code default, recorded as DECISION RAG-27 for
  Jay (keep, or roll back later). Fails any: the store is rebuilt back at 4,000 / 500 within Milestone 3, and a
  post-rollback snapshot must equal B0 per question in rank and matched chunk content (ids differ after any rebuild).
- Secondary rows, reported and not part of the rule: B1 and N1 with reranking on at the defaults (`rerank-candidates`
  20, timeout 2,000 ms); B1 must reproduce snapshot 613 per question. Per-question fused position and reranked
  position of the best accepted chunk, slices (figure, non-figure), per-ticker hit@5, retrieval and rerank time per
  question from the logs, fallbacks. A row with a rerank fallback is labelled and gives no comparison.

## Milestone 1: diagnostic over the whole set, and the size choice

Scope: run `CrossEncoderAnswerVisibilityLiveTests` for every question of set v2 with the visibility, truncation, rank,
and chunkSize experiments (the overlap grid is excluded: it scores each pool nine times and is not a factor here);
commit the `ANSWER_VISIBILITY` output under `live-runs/2026-09-17-chunk-size/diagnostic/` with a run log, a script that
computes the size-choice table from the output, `claims.json`, and a generated RAG.md block (new subsection under
Retrieval Evaluation, "Chunk size diagnostic"); apply the frozen size rule; update Follow_Ups RAG-25 (which experiments
ran, which did not) and RAG-15. No production code changes.

Correctness contract:
- D1. The committed output holds one chunkSize line per accepted phrase and size (57 phrases x 4 sizes), one truncation
  and one visibility line per phrase, and one rank line per question; the script counts from the output, not from the
  lead-in, and its table is committed.
- D2. The size-choice table lists per size: first-ranked phrases, split phrases, phrases whose piece is not seen by any
  scored row; the chosen size (or "none") follows the frozen rule, stated as a derived claim.
- D3. Every claim about a score or rank cites the line it comes from; nothing states why a score changed.
- D4. `verify` exit 0 (the claims check passes on the new block); skipped count reported.
- Commands: `./mvnw -q -o verify`; the diagnostic with `-Drag.rerank.live=true` and the four method names.
- Out of scope: any chunker change; the overlap grid; a second model.
- User-facing flow (UT): none beyond `verify`; UT Validator re-computes the size table from the committed output with
  an independent one-off script and compares three lines to the JVM output re-run for nvda-04 and nvda-02.

## Milestone 2: configurable chunk size, recorded where it matters

Scope: `FilingChunker` takes its size and overlap from validated properties (`rag.ingestion.chunk-max-chars`, 100 to
20,000; `rag.ingestion.chunk-overlap-chars`, 0 to half the size), defaults 4,000 / 500 (behaviour unchanged);
`PROCESSING_VERSION` becomes a value that names the size and overlap (for example `sections-v2-context-v2-chunk4000-500`)
so `filing_rebuild_runs` and `sec_filings.processing_version` distinguish stores; evaluation snapshot `properties`
record `chunkMaxChars`, `chunkOverlapChars`, and the distinct `processing_version` values of the stored filings at run
time (`storeVersions`); the evidence report shows them; the diagnostic's test-local chunker replica is replaced by the
real chunker parameterised, with a test proving the pieces are identical on a fixed text. Unit tests for the chunker at
1,000 / 125 and 500 / 62 (no piece longer than the size, overlap as configured, no empty piece, boundaries at half the
size or later). RAG.md documents the properties and the version string; change-log bullet.

Correctness contract:
- E1. With no overrides, the chunker produces byte-identical pieces to today's for a fixed sample (a recorded fixture),
  and `verify` is green with the skipped count unchanged from Milestone 1.
- E2. `rag.ingestion.chunk-max-chars=1000` with `chunk-overlap-chars=125` yields pieces equal to the diagnostic's
  replica at that size on the same text (the replica is deleted only after this test passes).
- E3. A new snapshot's `properties` carry `chunkMaxChars`, `chunkOverlapChars`, `storeVersions`; an old snapshot read
  back reports them null, not an error.
- E4. Existing stored filings stay complete and retrievable (`isComplete` does not depend on the version string).
- Commands: `./mvnw -q -o verify`.
- Out of scope: any rebuild; changing defaults.
- User-facing flow (UT): app on 8081 at defaults; `POST /api/rag/evaluate` runs and its snapshot shows the three new
  properties with 4,000, 500, and the current single store version; `GET /evidence` renders them.

## Milestone 3: baseline, rebuild at the chosen size, measurement, decision

Scope: with the app on 8081 (defaults, trace on): B0 and B1 as above with committed evidence reports; then
`POST /api/rag/filings/{id}/rebuild` for all 13 filings with the size from Milestone 1 (run log with per-filing chunk
counts, embedding call counts from the logs, elapsed time, `filing_rebuild_runs` rows); the R1 check; N0 and N1 with
evidence reports; a comparison script and table; `claims.json`; a generated RAG.md block ("Chunk size measurement");
the selection rule applied; on failure the rollback rebuild and its check; Follow_Ups RAG-15 and RAG-25 updated, RAG-26
(chunk-size measurement, DONE with evidence) and RAG-27 (decision) added; RAG.md notes on post-rebuild evidence of old
snapshots and on prompt context per chunk; PRD phase table; CLAUDE.md loop history; Project_Challenges if a lesson.

Correctness contract:
- F1. B0 reproduces 598 and B1 reproduces 613 per question, or the milestone stops before any rebuild and names the
  differing questions.
- F2. After the rebuild every filing has the new processing version and `ingestion_status` EMBEDDED; chunk counts per
  filing are recorded; R1 holds or the rollback runs and N0 does not.
- F3. N0's recorded `properties` differ from B0's only in `chunkMaxChars`, `chunkOverlapChars`, `storeVersions` (a
  script diffs them; the diff is committed); the same for N1 against B1.
- F4. Every rule row R1 to R5 is a claim with the numbers it rests on; questions entering and leaving the top 5 are
  named from the evidence, and for nvda-02 and nvda-04 the block states rank, matched chunk, and fused position in N0.
- F5. No sentence states why a rank changed; hypotheses stay labelled in Follow_Ups.
- F6. On a rollback, the post-rollback snapshot equals B0 per question in rank and matched chunk content.
- F7 (Jay, 2026-09-17). Rank table: for every question of set v2 (all three tickers), the rank of the first chunk
  holding an accepted phrase in B0 and in N0, and in B1 and N1 (1 to 10, or "not in window"), with the matched chunk
  id, as a committed table and a rank histogram per run (how many questions at rank 1, 2, 3, ..., not in window). If
  most questions are at rank 1, the write-up names every question that is not, with its matched chunk and the chunks
  ranked above it in that run (ids, section, first 80 characters).
- F8 (Jay, 2026-09-17). Latency: per question, retrieval elapsed and, for B1 and N1, rerank elapsed, from the
  application logs of the same runs (median, p95, maximum per run) and the whole-run wall time of each evaluation call;
  B0 against N0 and B1 against N1, as observed values with no cause stated.
- F9 (Jay, 2026-09-17). Storage: before and after the rebuild, chunk count, total content characters, mean and maximum
  chunk length, `pg_total_relation_size('sec_filing_chunks')`, the table's own size, and each index's size (embedding
  index and `content_tsv` index included), from one committed SQL script run at both points.
- Commands: `./mvnw -q -o verify` exit 0; `lsof -iTCP:8081 -sTCP:LISTEN` empty after the app stops.
- Out of scope: changing `searchTopK` or any retrieval default; a second size; reranker defaults.
- User-facing flow (UT): `GET /api/rag/evaluate/{id}` and `/evidence` for B0, B1, N0, N1 return what the committed JSON
  holds; `POST /api/rag/retrieve` for nvda-04's question returns, in its top 5 or not, what N0 records; the store's
  `processing_version` is uniform; three rows of the rank table re-derived from the live snapshots match.

## Status

- 2026-09-17: plan written after the nvda-04 investigation.
- 2026-09-17, amendment 1 (Jay, before Milestone 1): after the rebuild the evaluation reruns over all questions of all
  three tickers with a per-question rank table and histogram, the non-rank-1 questions named with their chunks, and
  latency and storage compared (F7 to F9). Approved to start Milestone 1.
- 2026-09-17, amendment 2 (Milestone 1, before Scrutiny): contract D1 said "one rank line per question"; the rank
  experiment prints one line per located accepted phrase (its loop is over targets, one per phrase), so D1 reads "one
  rank line per located phrase" (57). The Worker also reworded this plan's claim-id note at line 46, which `verify`
  screened as a citation outside a block; both are the Orchestrator's errors.
- 2026-09-17, Milestone 1 handed off (79e953c): the frozen size rule gives none. First-ranked phrases of 57: 38 at the
  stored 4,000, 33 at 2,000, 37 at 1,000, 35 at 500 with one split phrase (aapl-13), so 500 is excluded and no remaining
  size beats 4,000. Under the plan's boundaries no rebuild follows; Milestones 2 and 3 do not start unless Jay amends
  the rule or the scope. Validation of Milestone 1 pending.
- 2026-09-17, Milestone 1 closed: Scrutiny PASS on every contract item (independent recount of the 401 lines, the size
  table recomputed, the rule re-applied, all 44 claims read against their lines, checker code reviewed); UT PASS (own
  script reproduced the table; three chunkSize lines replayed against the live model byte for byte; `verify` exit 0,
  37 skipped, port 8081 free). One low finding, a stale contract reference in RAG.md, fixed in 034657c and passed a
  scoped Scrutiny check. CLAUDE.md's skipped count (32) is updated at plan close-out. Decision open for Jay: close with
  the measurement, override the size rule and run Milestones 2 and 3 at a named size, or replan around rerank-time
  passage cuts.
