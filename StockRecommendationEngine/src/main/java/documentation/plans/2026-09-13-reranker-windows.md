# Plan: windowed passage scoring for the cross-encoder reranker (Follow_Ups RAG-1, continuation)

Orchestrated build per CLAUDE.md. Two serial milestones with contracts written before code. Branch: `reranker`
(12 commits ahead of `main`, unmerged; `main` has not moved). Build: `set -a; source .env; set +a; ./mvnw -q -o verify`;
never export the application enable flags or `RAG_*` overrides in a shell that runs tests. Plan
`2026-09-13-reranker.md` and its Amendments 1 and 2 stay in force unless changed below.

## Why: the reranker cannot see most answers

The 2026-09-13 measurement (snapshots 247 to 250) found no configuration meeting the selection rule. The cause was
diagnosed on 2026-09-13 with the real tokenizer and model against every chunk in the database
(`live-runs/2026-09-13-reranker-windows/truncation-probe-512.txt`, source beside it):

- The chunker cuts filings at 4,000 characters and stores `token_count` as characters / 4. Exact WordPiece lengths
  are higher on filing text: median 647 tokens, 90th percentile 849, longest 1,214. The model's window is 512 tokens
  including the question and three special tokens, so a chunk keeps about 475 to 495 tokens: 371 of 569 chunks (65%)
  are longer than that, and on average the model sees 78% of a chunk.
- The scorer keeps the head of the chunk (longest-first truncation), so an answer in the tail is invisible. For 11 of
  the 39 questions with a matched chunk the expected phrase starts past the kept tokens, and nvda-01's phrase begins
  at token 485 of 489 kept. Every top-5 loss in the measurement is such a question: nvda-11 (answer at token 596,
  477 kept), nvda-14 (820 of 996, 477 kept), msft-12 (544, 490 kept), nvda-01. Rescoring the same question with the
  window placed on the answer flips the logit: nvda-11 2.6 to 5.3, nvda-14 -0.3 to 4.8, msft-12 -1.8 to 5.1,
  nvda-01 -1.7 to 5.0, msft-04 -10.7 to 0.2, msft-10 -9.5 to 9.9, msft-13 -1.7 to 7.7, aapl-05 -6.6 to 5.1.
- Not addressed here: nvda-02, nvda-04, and nvda-09 are never in the candidate list (a recall problem no reranker can
  fix); the reranked order discards the fusion figure leg entirely; short chunks that fit the window gain an edge.

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
