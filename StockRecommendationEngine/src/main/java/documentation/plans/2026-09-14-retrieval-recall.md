# Plan: candidate recall, one-factor recall experiment, diversification evidence, project challenges (Follow_Ups RAG-15)

Orchestrated build per CLAUDE.md. Branch `retrieval-recall` from `origin/main` (49ae87c). Build:
`set -a && source .env && set +a && ./mvnw -q -o verify`; never export the application enable flags or `RAG_*`
overrides in a shell that runs tests. Validators run one at a time. No chat model call anywhere in this plan; the only
model calls are evaluation embeddings (42 per `POST /api/rag/evaluate`).

## Why

An external review proposed eight retrieval changes (score fusion over raw scores, figure leg as a graded feature, deeper
reranking, diversification after reranking, neighbour expansion, section-first retrieval, RRF tuning). Checked against the
committed evidence (2026-09-14): reranker depths 10, 20, and 40 were already measured (snapshots 296 to 299, no row
qualifies); the figure leg at weight 1.0 is the best measured setting on set v2 (snapshots 69 and 71 to 75); diversification
drops only a chunk contained in, or overlapping at least half of, a kept chunk of the same filing and section
(`FilingRetrievalService.diversify`), not ordinary 500-character neighbours; and RAG-15's two misses sit at fused positions
41 and 54, beyond any reranker depth measured. The review's useful split is between "did retrieval find the answer"
(candidate recall) and "did ranking put it high" (hit@5, MRR after reranking). This plan measures the first before any
ranking change, and records what diversification removes, which traces do not list today.

Agreed order (2026-09-14): 1 candidate recall from committed traces; 2 RAG-15's one-factor E1 rerun; 3 diversification
drops in the trace, move only if an accepted chunk was dropped; 4 raw-score fusion with a held-out split; 5 rerank depth 30
only if misses sit at fused positions 21 to 40; 6 graded figure features only after questions that do not quote the figure;
7 neighbour expansion, section-first retrieval, RRF k. Plan A below covers 1 to 3 plus the project challenges document.
Steps 4 to 7 are Plan B: their contracts depend on Plan A's evidence and are written, and approved, after Plan A closes.

## Design constraints

- Evidence rule (CLAUDE.md, RAG-14): conclusions only from committed evidence; every claim labelled; a cause only with an
  experiment varying one factor. New claims files use ids C-501 to C-799 (C-101 to C-410 are taken).
- Defaults unchanged in Plan A: reranking, the cross-encoder, candidate counts, weights, and diversification order stay as
  they are. A row that meets the selection rule is recorded as a DECISION for Jay, not enabled.
- Tracing never changes what retrieval returns, and no trace reaches `/api/rag/retrieve` or a prompt (RAG-14 constraint).
- Documentation conventions: bullet style, dated change-log bullet per feature, live evidence under
  `documentation/live-runs/2026-09-14-<feature>/`, Follow_Ups items with stable ids, PRD phase table at the end.

## Milestone 1: candidate recall from committed traces

Scope: a committed, deterministic script under `live-runs/2026-09-14-candidate-recall/` that reads the committed traced
evidence reports (598, 599, 613, 627, 641 under `live-runs/2026-09-13-evaluation-evidence/measurement/`, and 694 under
`live-runs/2026-09-13-rag15-recall/`) and writes a recall table; a claims file and a generated RAG.md block (new
subsection "Candidate recall" under Retrieval Evaluation); a change-log bullet; Follow_Ups RAG-18 for the metric. No app
code change, no application start, no model call.

Definitions (fixed before the script is written): a question's best fused position is the smallest `fusedPosition` over
every chunk holding any accepted phrase of that question (null when none is in the fused list). Candidate recall@K is the
share of the 42 questions whose best fused position is at most K, for K in 10, 20, 30, 40, and the whole fused list.
Each question outside hit@5 is placed in exactly one class: `fused 6-20` (reachable at `rerank-candidates` 20), `fused 21-40`
(reachable only at 30 or 40), `fused >40`, or `not fused`.

Correctness contract:
- R1. For each evidence file, the per-question best fused position equals the minimum over all accepted-chunk
  `fusedPosition` values in that file (a validator recomputes it independently for at least 598 and 694); the class counts
  sum to the number of questions outside hit@5 recorded in the matching snapshot.
- R2. For 598 and 694 (reranking off, window 10, so the returned window is the fused list's first 10), recall@10 equals the
  share of questions with a non-null snapshot rank; any question where the two disagree is named in the output as a
  contradiction.
- R3. Every sentence in the generated block is an observed or derived claim rendered from a check; the prose outside the
  block states no number and no cause; `verify` passes, and a deliberately altered `fusedPosition` in a copy of an evidence
  fixture makes the corresponding claim fail naming the question (captured, rolled back).
- R4. nvda-02 and nvda-04 appear with the positions RAG-15 records (41 and 54 in 598; 25 and 37 in 694) or the block
  reports the difference.
- Commands: `./mvnw -q -o verify` exit 0; the script re-run reproduces its committed output byte for byte.
- Out of scope: any new evaluation run, any retrieval change, choosing a rerank depth.
- User-facing flow (UT): app on 8081, no rerank, no evaluate call; for 598 and 694 `GET /api/rag/evaluate/{id}/evidence`
  returns the fused positions the table uses for every question outside hit@5.

## Milestone 2: one-factor recall experiment (RAG-15 E1 rerun)

Scope: one application start with the cross-encoder loaded (as for 598), three traced runs: (a) the settings of 598
(reproduction), (b) `candidate-count` 200 with reranking off (one factor against 598), (c) `candidate-count` 200 with
reranking on at `rerank-candidates` 40 and `rerank-timeout-ms` 4,000 (one factor against 627). Evidence reports, a run log
in the RAG-15 run-log style, comparison scripts, a claims file, the selection rule applied to (c) against (b) and 627
against 598, a generated RAG.md block, Follow_Ups RAG-15 updated. 126 embedding calls; no chat model.

Correctness contract:
- X1. Run (a) reproduces 598's per-question ranks and matched chunks exactly, or the milestone stops and reports the
  differing questions without running (b) and (c).
- X2. The recorded `properties` of (b) differ from 598's only in `candidateCount` and those of (c) from 627's only in
  `candidateCount` (a script diffs them and the diff is committed); any other difference is named and no claim compares
  the pair.
- X3. Per question, the comparison lists best fused position and rank in both runs of each pair and the recall@K table of
  Milestone 1 for (b) and (c); the selection rule outcome per criterion is a `ruleRow` claim.
- X4. No sentence states why a chunk's vector rank changed; the vector-rank hypotheses stay labelled as hypotheses in
  Follow_Ups.
- X5. Rerank fallbacks per run are reported; a run with any fallback is labelled and not used for a selection outcome.
- Commands: `./mvnw -q -o verify` exit 0; `lsof -iTCP:8081 -sTCP:LISTEN` empty after the app stops.
- Out of scope: changing any default, even if (c) qualifies (DECISION item for Jay instead); explaining vector ranks.
- User-facing flow (UT): `GET /api/rag/evaluate/{id}` and `/evidence` for the three new snapshots return what the committed
  JSON holds; for nvda-02 and nvda-04 the live evidence shows the fused position and rerank input membership the block
  states.

## Milestone 3: diversification drops in the trace, and the move decision

Scope: the traced path records each chunk `diversify` removed: its chunk id, its position in the fused list before
diversification, its leg ranks, and the id of the kept chunk that made it redundant; the evidence report marks an accepted
chunk that was removed; unit tests; one traced run at 598's settings with the cross-encoder loaded; a claims block with the
count of questions whose accepted chunk was removed. If that count is 0, RAG-15/Follow_Ups records that diversification is
not the cause of any recorded miss at these settings and the order stays; if it is greater than 0, the milestone stops
there and a plan amendment (approved by Jay) specifies moving diversification after reranking as its own milestone.

Correctness contract:
- D1. Traced and untraced paths return identical results and strategy for rerank off, on, timeout, exception, and invalid
  evidence (the RAG-14 C1 scenarios) with scripted chunks that include a contained chunk and a half-overlapping chunk.
- D2. Every chunk in the pre-diversification list appears exactly once, either in `fused` or in the removed list; the
  recorded redundant-with chunk is in `fused` at a smaller position; ordinary 500-character neighbours are not removed.
- D3. Snapshots stored before this milestone (598, 694) read back with the removed list null and the evidence report says
  `unknown` / `no trace of removals`, never an empty list.
- D4. The new traced run reproduces 598's per-question ranks and matched chunks; its removal count claim is checked by
  `verify`.
- Commands: `./mvnw -q -o verify` exit 0.
- Out of scope: moving diversification (conditional amendment only); changing the redundancy rule.
- User-facing flow (UT): `POST /api/rag/evaluate?trace=true&rerank=false` stores removal lists for 42 questions;
  `/evidence` shows them; `POST /api/rag/retrieve` response shape is unchanged.

## Milestone 4: project challenges document

Scope: `src/main/java/documentation/Project_Challenges.md`, built from the project's episodic record: CLAUDE.md loop
history, the auto-memory files (stock-engine verification, follow-ups log, build loop, evaluation claims), Follow_Ups,
the plans directory, live-run logs, and git history. Sections by theme (for example: broker sessions and market data,
LLM token limits and injection screening, local environment and test isolation, retrieval quality, evaluation honesty
and the claims check, the multi-agent loop itself), each bullet a challenge, what happened, and the lesson, ending in a
pointer to its source. Includes this plan's outcome. Linked from RAG.md or the PRD's documentation list.

Correctness contract:
- P1. Every bullet ends in at least one pointer (file path, plan milestone, Follow_Ups id, or commit) that exists in the
  repository, and a validator reading the source finds the described event there.
- P2. Measurement numbers are not restated; a result is referenced by snapshot id or claims block (the RAG-14 M4b lesson:
  history that restates results creates copies that drift). Counts of rounds and failures are allowed with a pointer.
- P3. No cause is stated that the source does not state; lessons are attributed (Jay, a validator, a plan) as the source
  does.
- P4. Bullet style, no markdown headers inside sections; `verify` passes (the claims check reads documentation).
- P5. Nothing from memory files that is personal or outside this project is copied; no secrets, tokens, or account ids.
- Commands: `./mvnw -q -o verify` exit 0.
- Out of scope: new analysis or re-measurement; editing the sources it summarises.
- User-facing flow: none (Scrutiny only).

## Plan B (after Plan A closes; contracts written then)

- B1 raw-score fusion: vector cosine (absolute, not per-query min-max), keyword score, RRF score, figure feature, behind a
  strategy property that defaults off; weights chosen on a split fixed before any run (28 tuning / 14 held-out questions,
  stratified by ticker and kind, recorded in the set file) and reported on the held-out split only. Gate: Milestone 2 shows
  first-stage misses remain at `candidate-count` 200.
- B2 rerank depth 30: gate: Milestone 1 or 2 shows at least one question outside hit@5 at fused positions 21 to 40.
- B3 graded figure features: gate: a set v3 adding figure questions that do not quote the figure, authored with full chunk
  reads (RAG-11 lesson), baselined first.
- B4 neighbour expansion before reranking, section-first retrieval, RRF k: one experiment each, each one factor.

## Status

- 2026-09-14: plan written; Plan A approved by Jay.
