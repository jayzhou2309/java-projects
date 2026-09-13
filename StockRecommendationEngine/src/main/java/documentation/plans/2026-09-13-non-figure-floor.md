# Plan: a second regression floor over questions without a figure (Follow_Ups RAG-13)

Orchestrated build per CLAUDE.md. One milestone: the change is a single reviewable diff (slice metrics, one
property, one assertion, one measurement, docs). Branch: `non-figure-floor`, stacked on `evaluation-set-v2`
(PR #14, not yet merged); this PR's base is `evaluation-set-v2` and retargets to `main` when #14 merges. Build:
`set -a; source .env; set +a; ./mvnw -q verify`; never export the application enable flags or any `RAG_*`
override in a shell that runs tests. Token cost: one v2 evaluation run (42 embeddings) plus the live floor test
twice (passing and forced-failing, 42 each). No chat model.

## Why

The aggregate hit@5 floor of 0.65 on set v2 needs 28 of 42 hits. The 12 figure-literal questions all hit under
baseline snapshot 69 because each states its exact figure, so only 16 of the other 30 questions must hit (about
0.53): five of today's 21 hits on those questions could be lost with the floor still green. The guard that
matters most, retrieval on ordinary analyst questions, is no stronger than v1's.

## Design

- **Slices, defined by the retrieval rule, not by set version.** A question belongs to the figure slice when
  `FilingRetrievalRepository.figureTerms(question)` is non-empty, otherwise to the non-figure slice. On v2 this is
  exactly the 12 new questions versus the 30 carried from v1, but the definition carries to any future set
  without a hand-kept list, and it is the same rule that decides whether the figure leg runs.
- **Per-slice metrics are computed and stored at evaluation time**, because classification needs the question
  text and a snapshot stores only ids and ranks. Each slice records question count, hit@1, hit@3, hit@5, and MRR
  (scale 6, same definitions as the aggregate). They are stored inside the existing `results` JSONB (no
  migration) and returned on the evaluation record. Snapshots taken before this change carry no slices and
  return null for them; nothing is backfilled.
- **One new floor, non-figure hit@5.** Property `rag.evaluation.min-non-figure-hit-at-5` (BigDecimal, 0..1),
  derived by the existing rule from the v2 baseline slice: hit@5 − 0.1, rounded down to a multiple of 0.05, with
  decimal arithmetic (0.70 − 0.1 must give 0.60, not 0.5999…). Expected 21 of 30 = 0.70 → 0.60, which needs 18 of
  30, so at most three of today's 21 carried hits can be lost. The aggregate floor stays at 0.65.
- **No figure-slice floor.** Twelve questions make a noisy guard (one miss moves hit@5 by 0.083), and the
  figure leg's contribution is already pinned by the aggregate and by the recorded snapshot 71 comparison. Say
  so in the docs rather than adding it silently.
- **An empty non-figure slice is a failure, not a pass.** If a configured set has no non-figure questions the
  live test must fail with a message naming the set, so a misconfigured set can never satisfy the floor vacuously.

## Milestone 1: slice metrics, the non-figure floor, measurement, docs

Scope: evaluation-time slice classification and metrics on `RetrievalEvaluation` (stored in `results`, read back
by the repository), `rag.evaluation.min-non-figure-hit-at-5` on `RetrievalEvaluationProperties` and in
`application.yaml`, the live test asserting both floors and printing both slices, one `POST /api/rag/evaluate` on
v2 with current defaults to produce a snapshot carrying slices, the floor value derived from that snapshot, and
documentation.

Correctness contract:
- C1. Slice arithmetic with a mocked retrieval service: a scripted set of 5 questions, 3 without a figure ranked
  1, null, 4 and 2 with a figure ranked 2, 7, window 10, gives non-figure count 3, hit@1 0.333333, hit@3 0.333333,
  hit@5 0.666667, MRR (1 + 0 + 1/4)/3 = 0.416667; figure count 2, hit@1 0, hit@3 0.5, hit@5 0.5, MRR
  (1/2 + 1/7)/2 = 0.321429; the aggregate metrics are unchanged from today's computation. A question is
  classified with `FilingRetrievalRepository.figureTerms`, so "revenue in fiscal 2025" is non-figure and
  "revenue of $64,377 million" is figure. A retrieval error on a question counts as a miss in its slice.
- C2. Persistence: a snapshot saved with slices reads back with identical slice values from PostgreSQL; a
  snapshot row written before this change (no slices key in `results`) reads back with null slices and no
  exception.
- C3. Floor property: default equals the value derived from the new v2 snapshot by the rule, computed with
  BigDecimal; validation rejects values outside 0..1; the yaml comment states the derivation, the snapshot id,
  and what the floor protects (hits needed out of the slice size).
- C4. Live test: with `-Drag.evaluation.live=true` it passes on v2 with the defaults and prints the aggregate and
  both slices; with `-Drag.evaluation.min-non-figure-hit-at-5=0.95` it fails with a message naming the achieved
  non-figure hit@5, the floor, and the non-figure miss ids, while the aggregate assertion alone would have
  passed; an empty non-figure slice fails with a message naming the set (unit-level test with a scripted
  evaluation, not a live run). It still rolls back (row count before and after).
- C5. The new snapshot reproduces snapshot 69 question by question (rank and matched chunk) and its non-figure
  slice is 21 of 30; if it does not, stop and report drift instead of deriving a floor.
- C6. Documentation: RAG.md's floor section describes both floors, the slice rule, what each protects, why
  there is no figure-slice floor, and cites the snapshot; Follow_Ups RAG-13 DONE with evidence; the PRD phase 9
  row mentions the second floor; evidence under `documentation/live-runs/2026-09-13-non-figure-floor/`
  (snapshot JSON, both live-test logs, run.log).
- Commands: `./mvnw -q -o verify` exit 0; `./mvnw -q -o test -Dtest=RetrievalEvaluationServiceTests,RetrievalEvaluationRepositoryTests,RetrievalEvaluationSetLoaderTests`;
  the live test twice as in C4.
- Out of scope: any retrieval change, set change, weight change, aggregate floor change, backfilling old
  snapshots, a figure-slice floor.
- User-facing flow (UT Validator): app up; `POST /api/rag/evaluate` is not called again; `GET /api/rag/evaluate/{new id}`
  returns the slices with the values RAG.md states; `GET /api/rag/evaluate/69` (an older snapshot) still returns
  200 with slices null; the aggregate figures of the new snapshot equal snapshot 69's.

## Gate rules

Both validators must pass. Findings go to a fresh Worker; at most two remediation rounds, then escalate.
Validators never see the Worker's report or each other's output. Per the evaluation-set-v2 lessons, Scrutiny
recomputes the slice split from stored ranks and the question texts itself, and checks that no document frames
the second floor as stronger than its arithmetic.
