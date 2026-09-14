# Plan: windowed passage scoring for the cross-encoder reranker (Follow_Ups RAG-1, continuation)

Orchestrated build per CLAUDE.md. Two serial milestones with contracts written before code. Branch: `reranker`
(12 commits ahead of `main`, unmerged; `main` has not moved). Build: `set -a; source .env; set +a; ./mvnw -q -o verify`;
never export the application enable flags or `RAG_*` overrides in a shell that runs tests. Plan
`2026-09-13-reranker.md` and its Amendments 1 and 2 stay in force unless changed below.

## Why: the head cut does not reach some expected phrases

The 2026-09-13 measurement (snapshots 247 to 250) found no configuration meeting the selection rule. The cause was
diagnosed on 2026-09-13 with the real tokenizer and model against every chunk in the database
(`live-runs/2026-09-13-reranker-windows/truncation-probe-512.txt`, source beside it).
(Corrected 2026-09-14, plan `2026-09-13-evaluation-evidence.md` Milestone 4b: "The cause was diagnosed" is not
supported. The probe recorded which expected phrases the head cut did not reach; no experiment isolating truncation
as the cause of the measurement's outcome was run, so the probe is a finding about what the model saw, not a
diagnosed cause. The windowed rows' outcome is stated in RAG.md, Cross-encoder reranker, Reranker measurement,
Windowed rows, generated blocks.)
(Corrected 2026-09-14, plan `2026-09-13-evaluation-evidence.md` Milestone 4b remediation round 1: this section's heading
read "the reranker cannot see most answers". The probe's 65% and 78% describe chunk length and the share of a chunk the
model saw; for answers it records that for 11 of the 39 questions with a matched chunk the expected phrase starts past the
kept tokens.)

- The chunker cuts filings at 4,000 characters and stores `token_count` as characters / 4 (median 780). Exact
  WordPiece lengths are somewhat lower but still well past the window: median 647 tokens, 90th percentile 849, longest
  1,214 (corrected 2026-09-13 after Milestone 2 Scrutiny; this line first said exact lengths were higher than the
  estimate, which the probe does not show). The model's window is 512 tokens
  including the question and three special tokens, so a chunk keeps about 475 to 495 tokens beside the set's
  questions. The probe counted 371 of 569 chunks (65%) longer than 469 tokens, the room beside a 40-token question,
  and on average the model sees 78% of a chunk (corrected after Milestone 2 remediation 1: this line first attached
  the 65% to the 475 to 495 range).
- The scorer keeps the head of the chunk (longest-first truncation), so an answer in the tail is invisible. For 11 of
  the 39 questions with a matched chunk the expected phrase starts past the kept tokens, and nvda-01's phrase begins
  at token 483 of 489 kept, so 6 of its 17 tokens were seen (the probe's 485 is the offset of "215,938" inside
  it; corrected after Milestone 2 remediation 2). Every top-5 loss in the measurement but one is such a question: nvda-11 (answer at token
  596, 477 kept), nvda-14 (820 of 996, 477 kept), msft-12 (544, 490 kept), msft-04 (606, 480 kept), nvda-01. The exception is msft-05, which left the top 5 at 20 and 40 candidates although the phrases in the chunks it matched (571, 460) are inside the kept head; its third accepted phrase, in chunk 515, is past it (tokens 614 to 625 of 700), and whether chunk 515 was a reranked candidate is not recorded, so truncation is not ruled out for msft-05 (corrected after Milestone 2 remediation 1: this line first said every loss was a truncation case). (Superseded 2026-09-14, plan `2026-09-13-evaluation-evidence.md` Milestone 4b: this bullet was rewritten in place on 2026-09-13 in commit f757ad1 instead of being marked; as corrected after remediation 1 it said msft-05 left the top 5 "with its answer at token 121, inside the kept head", readable at commit c9b2e5f. Whether chunk 515 was a reranked candidate stays unrecorded for the head rows, which carry no trace; for the windowed rows the traced runs record it, in RAG.md, Reranker measurement, Windowed rows, generated blocks.) Rescoring
  the same question on the chunk text starting 200 characters before the answer (about 40 to 60 tokens in, not a
  production window position) flips the logit: nvda-11 2.6 to 5.3, nvda-14 -0.3 to 4.8, msft-12 -1.8 to 5.1,
  nvda-01 -1.7 to 5.0, msft-04 -10.7 to 0.2, msft-10 -9.5 to 9.9, msft-13 -1.7 to 7.7, aapl-05 -6.6 to 5.1.
- Not addressed here: nvda-02, nvda-04, and nvda-09 miss under every head row (for nvda-02 a check found its answer
  outside the first 20 fused candidates; nvda-04 was not checked, and nvda-09 turned out to be within the first 20 in
  Milestone 2's row 299, so "never in the candidate list" was not established; corrected after Milestone 2
  remediation 1); the reranked order discards the fusion figure leg entirely; short chunks that fit the window gain an edge.

## Decision (Orchestrator, 2026-09-13, for Jay's approval): score each chunk over sliding windows

Score a chunk as the maximum logit over overlapping windows of its tokens that together cover the whole chunk, in
Java on the already separately tokenized ids (no new dependency, no re-tokenization, no re-chunking of the corpus,
no re-embedding). A chunk that fits the window is scored exactly as today. Cost: two windows for a median chunk,
three for the longest, so scoring time roughly doubles to triples (measured in Milestone 1; the 2,000 ms timeout at
40 candidates may need raising for the measurement, recorded per run). Alternatives declined for now: re-chunking
at about 400 tokens (re-ingestion and re-embedding of every filing, and it changes the retrieval baseline the
evaluation set was built on); interpolating the cross-encoder score with the fused rank (a separate experiment,
Follow_Ups candidate, after windowing is measured).

## Design constraints

- Windowing lives in `CrossEncoderPairAssembler` (pure Java, testable without the model) and the max reduction in
  `OnnxCrossEncoderScorer`; `CrossEncoderReranker`, `FilingReranker`, `FilingRetrievalService`, the timeout and
  fallback path, the evidence validator, and `validateRerankedEvidence` are unchanged.
- Window arithmetic: budget = `max-length` - 3; the query keeps what today's longest-first rule gives it against the
  full chunk (so the query is never cut more than today); window length W = budget - query tokens kept; windows
  start at 0 and advance by W - `window-overlap-tokens`; the last window ends at the chunk's last token (its start is
  moved back so it is a full W tokens when the chunk is at least W long); at most `max-windows` windows per chunk,
  taken from the head (a chunk longer than that is scored on its first windows only, which bounds the work per
  call; 4 windows cover 1,214-token chunks with the defaults).
- New properties under `rag.retrieval.cross-encoder`: `passage-scoring` (`head`: today's behaviour, first window
  only; `max-window`: the maximum over windows; default `max-window`), `window-overlap-tokens` (0 to 256, default
  64), `max-windows` (1 to 16, default 4). All windows of a group of chunks flow through the existing per-call
  attention cap (`MAX_ATTENTION_CELLS_PER_RUN`) as rows, so peak memory per ONNX Runtime call is unchanged.
- Ties: the chunk order for equal maxima stays the fused order (unchanged comparator). Determinism: identical input
  gives identical scores and order.
- Observability: the scoring log line gains `windows=` (total windows scored in the call); evaluation snapshot
  `properties` gain `rerankerScoring` (for example `max-window/overlap=64/maxWindows=4` or `head`) through a new
  `FilingReranker.scoring()` default method returning null, so snapshots from different scoring modes are
  distinguishable.
- Defaults `rag.retrieval.reranking-enabled` and `rag.retrieval.cross-encoder.enabled` stay false (Amendment 2: the
  model files are gitignored). Any recommendation goes to Jay.
- Files the Worker must not touch (Amendment 2, still uncommitted in the working tree):
  `src/main/java/PRD_Stock_Recommendation_Engine_v3.md`, `src/main/java/documentation/Follow_Ups.md`, and the
  untracked `src/main/java/documentation/Target_State_Trading_Desk.md`. Stage files by name, never `git add -A`.

## Milestone 1: windowed passage scoring

Scope: the window arithmetic and tensors in `CrossEncoderPairAssembler`; the max reduction, the `head` mode, the
`windows=` log field, and the three properties in `OnnxCrossEncoderScorer`, `CrossEncoderProperties`,
`CrossEncoderConfiguration`, `application.yaml`; `FilingReranker.scoring()` recorded by `RetrievalEvaluationService`
as `properties.rerankerScoring`; unit tests for the arithmetic; an opt-in live test on the real model; RAG.md
component description (Cross-encoder reranker, What it is / Input bounds / Latency) updated for windows, with a
dated change-log bullet. No measurement on the evaluation set.

Correctness contract:
- C1. Window arithmetic (unit-tested without the model, over the assembler's own ids): a chunk of at most W tokens
  yields one window with exactly today's tensors (same ids, mask, types, width); a longer chunk yields windows that
  start at 0, advance by W - overlap, cover every token of the chunk, and end exactly at its last token, with the
  last window a full W tokens; never more than `max-windows` windows, taken from the head; overlap 0 gives
  disjoint windows; a chunk of 0 tokens yields one empty window (scored as today).
- C2. Under `max-window` the chunk's score is the maximum of its windows' logits; under `head` it is the first
  window's logit, and `head` reproduces today's scores exactly on the parity samples of
  `CrossEncoderSeparateTokenizationLiveTests` (those tests pass unchanged).
- C3. Opt-in live test (`-Drag.rerank.live=true`, DB-free, real model): a handwritten chunk of about 900 tokens whose
  answering sentence sits after token 600 scores higher under `max-window` than under `head` for its question, and
  ranks first among three candidates under `max-window`; a chunk with the answer inside its first 200 tokens scores
  the same under both modes to within 1e-4; two calls on identical input give identical scores and order; the
  latency for 20 and for 40 chunks of about 1,000 tokens each is printed and recorded in the handoff.
- C4. The scoring log line reports `windows=`; for 20 chunks of 1,000 tokens it is at least 40. Snapshot
  `properties.rerankerScoring` is recorded for a cross-encoder run and null when there is no reranker; existing
  snapshots read back unchanged (`RetrievalEvaluationRepositoryTests` pass).
- C5. Every ONNX Runtime call still respects `MAX_ATTENTION_CELLS_PER_RUN`; a 20,000-character chunk yields at most
  `max-windows` windows, so the work per call is bounded by `max-windows` times today's.
- Commands: `./mvnw -q -o verify` exit 0; `./mvnw -q -o test -Dtest='CrossEncoder*' -Drag.rerank.live=true` exit 0.
- Out of scope: evaluation-set measurement; any default change of `reranking-enabled` or `cross-encoder.enabled`;
  re-chunking; fusion interpolation; the Follow_Ups and PRD rows.
- User-facing flow (UT Validator): app started with `RAG_CROSS_ENCODER_ENABLED=true` (reranking off by default).
  `POST /api/rag/retrieve` for nvda-11's question (ticker NVDA, topK 10, `"rerank": true`) returns
  `HYBRID_RRF_RERANKED`, the same order on two consecutive calls, and the log shows `windows=` above the candidate
  count; without `rerank` the strategy is `HYBRID_RRF`. App restarted with `RAG_CROSS_ENCODER_PASSAGE_SCORING=head`:
  the same request returns `HYBRID_RRF_RERANKED` with `windows=` equal to the candidate count, and the UT records
  both orders (they are expected to differ: under `head` chunk 805 ranked tenth in snapshot 250; identical orders
  are reported as a finding, not a failure).

## Milestone 2: measure, decide, document

Scope: on set v2, window 10, with the cross-encoder enabled and `max-window` defaults: a fresh reference run with
reranking off, then reranking on at `rerank-candidates` 10, 20, and 40 (a 40-candidate run that reports fallbacks
at the 2,000 ms timeout is rerun once with `rag.retrieval.rerank-timeout-ms` raised to 4,000, the override recorded
in the run log and the table). Amendment 2 rules: warm-up call after each start; only fallback-free runs count.
Selection rule unchanged from Milestone 3 of the previous plan: enable by default only if aggregate hit@5 does not
decrease, non-figure hit@5 does not decrease, no ticker's hit@5 decreases, and no kind-FIGURE question in the top 5
under the reference leaves it; among qualifying rows the highest MRR, then fewer candidates. The head rows 248 to
250 are cited beside the new rows as the prior comparison. Both floors run with the final defaults. No default
flip (Amendment 2): a qualifying row is recorded as a recommendation with the exact properties to set.

Correctness contract: tables match stored snapshots by id and are recomputed from stored ranks; the rule is shown row
by row; per-question rank changes reference to each new row, and each loss or gain on a question named in the Why
section above is explained against the probe (answer visible or not); per-slice (figure, non-figure, per ticker)
decomposition reported; median and maximum scoring time per question per run from the reranker's log lines; RAG.md
Reranker measurement subsection amended (cause named, new rows, outcome), change-log bullet, evidence under
`live-runs/2026-09-13-reranker-windows/measurement/`; both floors pass; `application.yaml` defaults equal the
documented decision. Follow_Ups RAG-1 and the PRD phase row are updated by the Orchestrator with Jay afterwards.

User-facing flow (UT Validator): nvda-11's and nvda-14's questions retrieved with `rerank` true and false; the
ranks of chunks 805 and 872 match the documented table for the best row; strategy labels correct.

## Gate rules

Both validators must pass before the next milestone; at most two remediation rounds, then escalate. Validators never
see the Worker's report or each other's output, and run one at a time (shared `target/`). Scrutiny recomputes
metrics from stored ranks, reads the window arithmetic against C1 with its own examples, and checks that failure
messages name what they guard.

## Amendment 1 (2026-09-13, after Milestone 1 Scrutiny PASS)

Milestone 1 passed Scrutiny on every contract item (commit a791a8d). Two things it surfaced change Milestone 2:

- **Position sensitivity.** The Milestone 1 live sweep (`live-runs/2026-09-13-reranker-windows/live-test-windows.log`,
  `positionSweep`) shows the logit for the same answering sentence falling from about +10 at a window's start to -11 at
  token 213 on synthetic filler, non-monotonically. The truncation probe in the Why section placed each window about
  50 tokens ahead of the answer, the favourable case. Milestone 2 therefore adds one grid row beyond the defaults:
  `rerank-candidates` 20 with `window-overlap-tokens` 224 (stride about 250, so every token sits within 250 of a
  window start; three to four windows for a median chunk, latency about 1.5 times the default row, recorded). The
  selection rule is applied to it like any other row; if it qualifies where the default row does not, the
  recommendation names the overlap to set.
- **Documentation corrections folded into Milestone 2's RAG.md pass** (Scrutiny findings, wording only): the Position
  sensitivity paragraph must quote the sweep as logged (-11.09 at token 213, -7.10 at 257, +2.57 at 340), not "about
  -4 by 250 tokens"; the Latency line must cite only figures present in the evidence file, or the second run's log
  must be committed beside it; the Modes line must name the classes that construct the scorer with `HEAD`
  (`CrossEncoderRerankerLiveTests`, `CrossEncoderLengthProbe`, `CrossEncoderResourceProbe`), not
  `CrossEncoderSeparateTokenizationLiveTests`, which never constructs it.
- **Timeout for the 40-candidate rows.** Milestone 1 measured 40 chunks of 1,009 tokens at about 3.1 s under the
  defaults, so the 40-candidate row is run with `rag.retrieval.rerank-timeout-ms` 4,000 from the start (override
  recorded), instead of after a fallback.

## Amendment 2 (2026-09-13, after Milestone 2 Scrutiny FAIL)

Milestone 2's measurement, decision, defaults, and floors were confirmed by Scrutiny's own recomputation from the stored
snapshots and the database; it failed on documentation counts and claims the committed evidence contradicts. Remediation
round 1 is documentation only, with no new runs. The Why section's claim that exact WordPiece lengths exceed the stored
characters / 4 estimate was wrong (estimate median 780, exact median 647) and is corrected above; the conclusion that 65%
of chunks exceed the window is unaffected.

## Amendment 3 (2026-09-13, after Milestone 2 re-validation FAIL)

Scrutiny re-validated Milestone 2 after remediation 1 (da0394c): metrics, rule, defaults, floors, and build pass again;
it failed on per-question explanations the window arithmetic or the max-window scoring rule contradicts (nvda-09,
aapl-09, msft-04) and on claims no committed evidence supports. Remediation round 2, the last before escalating to Jay,
is documentation only. Rule for it: a per-question cause is written only when the committed evidence establishes it;
otherwise the text states the observed rank change and says the cause was not determined. The Why section's grouping
of nvda-09 with the never-retrieved questions is corrected above.

## Status at merge (2026-09-13, Jay: revisit the write-up later)

- Milestone 1 (a791a8d): Scrutiny PASS and UT PASS on the first round.
- Milestone 2 measurement and decision: snapshots 295 to 299, the selection rule (no row qualifies), the defaults, both
  floors, and the latency figures were confirmed by independent recomputation in every validation round.
- Milestone 2 write-up: NOT validated. It failed Scrutiny after the first attempt and after both remediation rounds
  (da0394c, 788eacd); two Orchestrator corrections at the cap (c9b2e5f, f757ad1) each failed their docs-only check. The
  UT flow for Milestone 2 was not run. Jay chose to freeze it and merge. Open findings from the last check (f757ad1):
  "every accepted phrase is scored in the windowed rows" (RAG.md Windowed rows msft-05 text, the Milestone 1 change-log
  correction, run.log) is unsupported, because no snapshot records whether chunk 515 was a reranker candidate; this
  plan's Why still says "The cause was diagnosed"; run.log's first correction note still calls msft-05 not a truncation
  case without a superseded marker; two earlier correction notes (RAG.md Windows Why, this plan's Why msft-05 bullet) were
  rewritten in place instead of marked superseded; "its matched chunk's" in the Milestone 1 change-log correction should
  name chunks 805 and 466.
- Revisit by recording evidence rather than rewording: store each question's reranker candidates, fused and reranked
  positions, and scores in evaluation snapshots, generate the per-question facts (accepted phrase spans in every chunk,
  head and window membership, rank source observed or inferred) from that record, and regenerate the write-up from it.
- Closed 2026-09-14 (plan `2026-09-13-evaluation-evidence.md`, Milestone 4b, commit 95a3f63): the write-up is regenerated from
  the traced runs at snapshots 295 to 299's settings (598, 599, 613, 627, 641, each reproducing its counterpart) as generated
  blocks in RAG.md, Cross-encoder reranker, Reranker measurement, Windowed rows, written from
  `live-runs/2026-09-13-evaluation-evidence/measurement/claims.json`; the replaced text can be read at commit e11b9b6. The open
  findings are resolved there and in dated notes, not by rewording: chunk 515's candidacy for msft-05 and "every accepted phrase is
  scored" (observed candidate and derived row claims per traced run, and an inferred summary, in the Microsoft segments question's
  block; the Milestone 1 change-log correction and run.log's Other movements sentence marked superseded); chunk 466's position at
  40 candidates and the chunks ranked above nvda-01's, nvda-11's, and msft-04's accepted chunks (observed reranked positions in the
  NVIDIA total revenue, Microsoft headcount, and NVIDIA Compute and Networking blocks; the chunk ids above them, which no check type
  renders, are copied from the evidence reports into the measurement `run.log`, section Milestone 4b); "The cause was diagnosed" in
  this plan's Why (dated correction note there); the earlier correction notes (run.log's re-validation msft-05 note, kept and marked
  superseded; RAG.md's Windows Why note and this plan's Why msft-05 bullet, kept and marked superseded, naming c9b2e5f for their
  earlier wording; RAG.md's Windows Why note and Milestone 1 change-log correction were later collapsed into pointers, 2026-09-14 at the Milestone 4b remediation cap, Orchestrator, with Jay's approval,
  commit bc29a48, and their text is readable at commit 08b7f60; corrected 2026-09-14, plan `2026-09-13-evaluation-evidence.md` Milestone 4b remediation round 1: this
  parenthesis grouped run.log's note, which names no commit, with the two that name c9b2e5f); and "its matched chunk's" in the
  Milestone 1 change-log correction (its superseded note named chunks 805 and 466; corrected 2026-09-14 at the Milestone 4b remediation cap, Orchestrator, with Jay's approval:
  remediation round 2 of Milestone 4b replaced that note's wording, including the chunk ids, with a description, so the
  wording naming chunk 805 for nvda-01 and chunk 466 for msft-04 is readable at commit 43b1931, not in RAG.md at HEAD).
