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
- E5 (amendment 3). Section awareness and metadata are unchanged at any size: no piece spans two parsed sections, and
  every piece carries the section key, section title, section chunk index, start and end character offsets within
  the section, and token count exactly as the stored chunks do today; a unit test proves it at 1,000 / 125 on a
  multi-section fixture, and the rebuild path stores the same columns.
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

## Milestone 4 (added 2026-09-18): RAG-29, the evaluation set's aapl-08 section key, and the id-bound live tests

Findings (Orchestrator, 2026-09-18, from the repository and the store; see RAG-29): commit 19876f2 (2026-09-12) changed
`FilingHtmlParser`'s item pattern from `item (\d+[a-z]?)` to `item (\d+[a-z]?(?:\.\d+)?)`, keeping an 8-K sub-item in
the key; on the actual headings "Item 2.02", "Item 9.01", "Item 5.02" the old pattern keys ITEM_2, ITEM_9, ITEM_5 and
the new one ITEM_2_02, ITEM_9_01, ITEM_5_02, while 10-K and 10-Q headings key the same under both (a two-pattern
experiment on the heading strings, to be committed with this milestone). Apple's three 8-Ks were ingested on
2026-09-10 under the old pattern and Microsoft's on 2026-09-12 under the new one, so the set of 2026-09-13 names
ITEM_7_01 for msft-09 and the stale ITEM_2 for aapl-08. The rebuilds of 2026-09-17 re-keyed Apple's 8-Ks; the set
entry is the stale side. Two opt-in live tests pin chunk ids of the 2026-09-13 store (297's matched ids;
msft-05's chunks 460, 515, 571 in snapshot 459), which every rebuild renumbers.

Scope: correct aapl-08's `sectionKey` to ITEM_2_02 in `retrieval-set-v1.json` and `retrieval-set-v2.json` with a
dated note in the entry (question and phrase unchanged; no set-version bump, the version convention concerns floors
on a new set); commit the two-pattern experiment as a unit test on the heading strings; make
`RetrievalEvaluationTraceLiveTests` and `RetrievalEvidenceLiveTests` resolve their chunks by filing, section, and
content (md5 or the accepted phrase) instead of by id, and run each once with its opt-in flag on the current store
with the output committed; one fresh default traced snapshot on the current store recording aapl-08's rank and the
set's hit@5, committed under `live-runs/2026-09-18-rag29-section-key/` with `claims.json` (ids C-2001 to C-2099) and a
generated RAG.md block; RAG-29 DONE with evidence; RAG-26, RAG-27, RAG-30, and RAG.md prose that cite 0.761905 as the
current store's baseline gain a pointer to the corrected figure; a new Follow_Ups item RAG-31 recording the
fusion-gate observation (with weights 1.0 / 0.5 and k 60, a chunk found only by the keyword leg scores at most
0.5 / 61 = 0.0082 while the 40th vector candidate scores 1 / 100 = 0.0100; over the 42 questions of snapshots 1777 and
1786 no keyword-only chunk reached a fused position above 39; nvda-04's chunk at keyword rank 35 and 8 with no vector
rank sat at 54 and 46; the cheapest tests being keyword weight 1.0 and guaranteed keyword slots in the reranker input,
untested); change-log bullet; CLAUDE.md skipped count from the final `verify`.

Correctness contract:
- H1. `./mvnw -q -o verify` exit 0 on the shared database (the two set tests pass), skipped count reported.
- H2. The heading-pattern unit test shows the old and new keys for the five headings above.
- H3. The fresh snapshot records aapl-08 with a rank (not null) and its matched chunk holds the phrase under
  ITEM_2_02; its hit@5 and MRR are stated as observed claims beside snapshot 1777's 0.761905 / 0.631378 and the
  2026-09-13 reference 598's 0.785714 / 0.655187, with no cause stated beyond the set correction being the one factor
  against 1777 (same store, same settings: an experiment claim).
- H4. Both opt-in live tests pass on the current store with their outputs committed, and neither names a chunk id.
- H5. No production code changes; no store change; no default change.
- Commands: `./mvnw -q -o verify` exit 0; the two opt-in tests; `lsof -iTCP:8081 -sTCP:LISTEN` empty after the app
  stops.
- Out of scope: any parser change; changing msft-09 or any other entry; the fusion weights (RAG-31 is a record only).
- User-facing flow (UT): `GET /api/rag/evaluate/{id}` for the fresh snapshot returns aapl-08 at the recorded rank;
  `GET .../evidence` reports 57 of 57 phrases held; `POST /api/rag/retrieve` for aapl-08's question (ticker AAPL) at
  the defaults returns a chunk holding the phrase within the recorded rank.

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
- 2026-09-17, amendment 3 (Jay, after Milestone 1): Jay overrides the size rule's "none" outcome and chooses a rebuild
  at 1,000 / 125 characters, on the reasoning (inferred, not measured) that the vector and keyword legs, which the
  diagnostic does not score, decide whether nvda-04's chunk reaches the reranker at all. The selection rule of
  Milestone 3 (R1 to R5 against B0, with F7 to F9) is unchanged and decides whether the store stays. Jay asked that
  chunking stay section-aware with its metadata: chunks are already cut within parsed sections and carry section key,
  title, index, offsets, and token count; Milestone 2 contract E5 makes that an explicit check at the new size.
  Adding heading text into chunk content would be a second factor and is out of this plan. Milestone 2 starts.
- 2026-09-17, Milestone 2 closed (1ba6c23 fixture, 99c694d change): Scrutiny PASS on E1 to E5 with no findings (the
  fixture re-derived from the old rule independently; the chunker run against the replica on an eleven-section edge
  fixture at eleven size and overlap pairs; startup refusal of an over-half overlap shown by experiment); UT PASS
  (snapshot 1610 at defaults records chunkMaxChars 4000, chunkOverlapChars 500, storeVersions
  [sections-v2-context-v2] equal to the store; snapshots 598 and 947 read back with the keys unknown; store untouched at
  569 chunks; retrieve smoke check green; port 8081 free). Two low notes recorded by Scrutiny, no fix required: the
  bounds are stated both in the properties annotations and in the chunker's guard; RAG.md calls the half-size rule
  class-level while it is an `@AssertTrue` method. Milestone 3 starts at the size of amendment 3 (1,000 / 125).
- 2026-09-17, Milestone 3 handed off (25c1cd4, 1dba3f0, 1b16ee9; the Worker's session was cut by a usage limit after
  the rollback and resumed for the write-up). B0 (1611) and B1 (1612) reproduced 598 and 613. The rebuild at 1,000 /
  125 gave 1,917 chunks; R1 failed (55 of 57 phrases held: aapl-13 split across a piece boundary, aapl-08 keyed
  ITEM_2_02 by the current parser where the set names ITEM_2), so no selection row exists; D0 (1613) and D1 (1614) are
  diagnostic records only, taken after R1 failed to satisfy F7 and F8 (a deviation the Orchestrator accepts); the
  rollback rebuilt 4,000 / 500 (569 chunks) and P0 (1615) equals B0 on 41 questions. F6 does not hold for aapl-08:
  the rollback re-parses under the parser in the tree, which keys 8-K items with the sub-item, so a rollback is not a
  restore of section keys. Consequences: `verify` is exit 1 on the shared database (RetrievalEvaluationSetTests,
  aapl-08 in sets v1 and v2) and two opt-in live tests bound to old chunk ids no longer reproduce; recorded as
  Follow_Ups RAG-29 (decision for Jay). The Worker's attempt to restore the old keys by SQL was refused by the
  permission system and is not to be retried: the store reflects the current parser. Defaults stay 4,000 / 500;
  RAG-27 records the decision as not adopted. Validation pending with the build failure named in the contract.
- 2026-09-17, Milestone 3 Scrutiny round 1: FAIL on two contract items that are the shared database's state (the
  `verify` command, exit 1 on aapl-08 in RetrievalEvaluationSetTests; F6 on aapl-08), judged not attributable to the
  diff (no production change; every derived number recomputed and equal: F1, F3, F7 histograms and all 42 x 5 cells,
  F8, F9). Seven documentation findings (a parser-change hypothesis stated as fact in Project_Challenges; no recorded
  provenance for `filings-S2.json`; C-1315 premises; truncated index names; C-1512 wording; RAG-29 reporting an unrun
  test as failed; one label on C-1509) fixed in remediation round 1 (f0c8247) and passed a scoped re-check. The two
  database-state failures close only with RAG-29 (Jay's decision). UT pending.
- 2026-09-17, Milestone 3 UT: PASS. All five snapshots (1611 to 1615) read back equal to the committed exports in
  metrics, properties, and every question's rank and matched chunk; evidence 1615 equals the committed report (56 of
  57 held, aapl-08 not held); evidence for 1613 and 598 reads without error against today's store; three rank-table
  rows re-derived; retrieve for nvda-04's question returns no chunk holding the answer, as P0 records; the store is
  uniform at `sections-v2-context-v2-chunk4000-500`, 569 chunks, 1,486,993 characters. Observation: an evidence report
  generated while no cross-encoder is loaded labels token counts unknown ("tokenizer unavailable") instead of the
  committed derived values, by design. Milestone 3 is closed on everything but the two database-state items, which
  close with RAG-29. Plan close-out (merge) waits on Jay's decisions RAG-27 and RAG-29.
- 2026-09-18, Milestone 4 added (Jay: "proceed to find the fix", then "proceed"): the RAG-29 findings above and the
  fix contract; Worker starts.
- 2026-09-18, amendment 5 (Milestone 4 handoff): H3 asked for an experiment claim for the fresh snapshot against 1777;
  the checker refuses it because the set file's path is the same recorded property in both snapshots and no recorded
  property carries the set's content. The pair is stated as an inferred claim resting on the two evidence reports'
  recorded aapl-08 section keys, the per-question content equality on 41 of 42 questions, and the metrics; the
  Orchestrator accepts this. Handoff: 55ef0f1, df6aca0, 2c61fae, e6ea89d; snapshot 1891 (aapl-08 rank 1 under
  ITEM_2_02, 57 of 57 phrases held, hit@5 0.785714, MRR 0.655187, equal to 598); 1892 at 297's settings equals 297 and
  613 rank for rank; `verify` exit 0 with 37 skipped. Validation pending.
- 2026-09-18, amendment 6 (Milestone 4, Scrutiny round 1): H3's premise "(same store, same settings: an experiment
  claim)" was wrong on both counts. The runs are not on the same physical store: 1777 ran on store A (chunk ids
  11406 to 11974) and 1891 on the rollback rebuild A2 (13789 to 14357), re-embedded separately, so the set key is not
  the one input that differs; the premises establish equal recorded properties and equal matched-chunk content on
  41 of 42 questions, not equal vectors. Amendment 5 recorded only the checker's refusal. Scrutiny failed the milestone
  on the pair claim's sentence that overstated this and its two prose copies, plus two low notes; remediation round 1 (61a6c11)
  bounded the sentence, added the same-store pair 1794 against 1891 as a premise, recorded that the traced live test
  must be re-recorded after any rebuild, and reworded "stored heading strings"; the scoped re-check PASSED with three
  low notes: a source pointer for store A's id range should name the pool plan's E3 or `chunk-hashes-A.json`; the
  re-recording procedure should name `filings.sql` as a separate hand-run step; and this plan's H3 wording, corrected
  here. The two pointer notes are fixed at plan close-out. UT pending.
- 2026-09-18, Milestone 4 UT: every deliverable PASSED against the live app (1891 and 1892 read back equal to the
  committed exports on all 42 questions; evidence 57 of 57 held; aapl-08 at rank 1 under ITEM_2_02 with the phrase
  in the returned chunk; store untouched; set files corrected), and `verify` FAILED on this plan's amendment 6, which
  cited a claim id in prose outside a block. The Orchestrator reworded that note; `verify` exit 0 (658 tests, 0
  failures, 37 skipped, claims check 0 problems). Same lesson as the RAG-14 loop: the Orchestrator's plan text is
  under the claims check like every other document. Milestone 4 closed; close-out (two low pointer notes, CLAUDE.md
  loop history) follows.
