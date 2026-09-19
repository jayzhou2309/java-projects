# Plan: answer-level evaluation of the recommendation loop (AGENT-10)

Orchestrated build per CLAUDE.md. Branch `answer-evaluation` from `main` (c0840c0) in worktree
`java-projects-answer-eval` (`.env` copied, `models/` symlinked and never staged). Build:
`set -a && source .env && set +a && ./mvnw -q -o verify`; never export the application enable flags or `RAG_*`
overrides in a shell that runs tests. Validators run one at a time. This plan is the first in the retrieval series
that calls the chat model: Jay's token discipline applies (lean profile, paced runs, a budget approved before any
live pass).

## Why

- Follow_Ups AGENT-10: the retrieval set measures whether the right chunk is returned; nothing measures whether the
  manager cites it, or whether the model could read the answer in what it was shown. AGENT-8 (prompt regression) is
  blocked on this, and AGENT-9 asks which passage length and count keep INSUFFICIENT_EVIDENCE rare.
- Two days of retrieval measurement (plans `2026-09-17-chunk-size.md`, `2026-09-17-chunk-size-pool.md`) ended with
  decisions RAG-27 and RAG-30 open; whether the known retrieval misses change answers is the evidence those
  decisions lack.
- Observed in the code (2026-09-19): the manager's final JSON carries `citedChunkIds`, validated against the run's
  retrieved evidence, so citation measures can be deterministic; every recommendation run is stored in
  `recommendations`, which `findPendingEvaluation` (outcome scoring) and the per-ticker history (the manager's track
  record) read; the lean profile cuts passages to 1,500 characters at the model boundary, while an accepted phrase can
  sit later in its chunk (nvda-04's starts at character 3,057).

## Boundaries and design constraints

- Evaluation runs go through `RecommendationService` exactly as a user request does: same validation, same tool
  allowlist, same instruction-like passage screen, same citation validation. No side path reaches a prompt. The
  questions come only from the committed evaluation set.
- Evaluation runs never feed product data: they are marked, and excluded from outcome scoring, the per-ticker track
  record shown to the manager, the watchlist, and calibration. The public request cannot set the mark.
- Deterministic measures only in this plan. "Does every cited passage support the claim" needs a judge; this plan
  reports the existing critic verdict and the numeral check beside the deterministic measures and leaves a judged
  metric to a follow-up.
- Measures per question (definitions frozen here): `retrievedExpected` (some chunk retrieved during the run holds an
  accepted phrase); `visibleToModel` (the phrase lies inside the text the model was shown for that chunk, after the
  `model-passage-chars` cut); `citedExpected` (a cited chunk holds an accepted phrase); `citedCount` and
  `citedHoldingPhrase`; for FIGURE questions `figuresInReasoning` (every numeric token of an accepted phrase, years
  excluded by the retrieval figure rule, appears in the reasoning); `status`, `assessment`, limitation codes, critic
  verdict, unsupported numerals, model calls, observed tokens, elapsed.
- Aggregates: share retrieved; share visible given retrieved; share cited given visible; share of FIGURE questions
  with the figures in the reasoning; INSUFFICIENT_EVIDENCE rate; invalid-citation and limit codes; total tokens.
- Token budget: no live run before Jay approves a number. Proposed: a pilot of 6 questions (two per ticker, nvda-04
  and aapl-08 among them, about 45,000 tokens), then one full lean pass of 42 (about 300,000 tokens, paced at one run
  per 20 seconds to stay under the 30,000 tokens-per-minute limit, about 15 minutes). A 429 after the existing single
  retry stops the pass; a stopped pass is recorded as partial and not repeated without approval.
- Broker and quant stay off for evaluation runs (`IBKR_ENABLED` unset): the questions are filing questions; the
  limitation codes this causes are recorded, not treated as failures.
- Evidence rule RAG-14 for the write-up; claim ids C-2101 to C-2399; no sentence states why the model answered as it
  did.

## Milestone 1: evaluation runs are marked and kept out of product data

Scope: migration V10 adds `recommendations.purpose` (`USER` default, `EVALUATION`), set only by an internal
service entry point; `findPendingEvaluation`, the per-ticker history behind the track record and
`GET /api/recommendations?ticker=`, the watchlist, and calibration joins exclude `EVALUATION`; `GET
/api/recommendations/{runId}` still returns such a run and shows its purpose. Docs: Agent_Harness.md, Outcomes.md.

Correctness contract:
- A1. A run stored with purpose EVALUATION is never returned by `findPendingEvaluation`, never appears in the track
  record given to the manager, and never in the per-ticker listing; a USER run is unaffected (tests against the
  shared database, robust to existing rows).
- A2. `RecommendationRequest` has no field that sets the purpose; a request body carrying one is rejected or ignored
  (test).
- A3. Existing rows read back as USER; migration applies on the existing database; `verify` exit 0.
- Commands: `./mvnw -q -o verify`.
- Out of scope: the runner; any prompt change.
- User-facing flow (UT): app on 8081 with `RECOMMENDATION_ENABLED=true` and `SPRING_PROFILES_ACTIVE=lean`: one normal
  request (one live lean run, about 7,000 tokens) is stored as USER and listed; no public way to store an
  EVALUATION run.

## Milestone 2: the answer-evaluation runner, measures, and storage

Scope: `POST /api/rag/evaluate/answers` (gated; optional `questions=` ids and `limit=`; pacing
`rag.evaluation.answers.pause-ms`, default 20,000) runs the set's questions sequentially through the internal entry
point with purpose EVALUATION and stores one `answer_evaluations` snapshot (migration V11) with run properties
(profile values: search-top-k, model-passage-chars, critic-rounds, prompt version, chat model, set version, store
versions) and per-question measures as defined above; `GET` latest, by id. The model-visible text is computed by the
same function the tools use to cut passages, not re-implemented. Unit tests with the scripted chat model cover: the
expected chunk retrieved and cited; retrieved but cut out of view; retrieved, visible, not cited; an invalid
citation; INSUFFICIENT_EVIDENCE; a FIGURE question with and without the figures in the reasoning; a 429 stopping the
pass as partial. No live model call in this milestone's tests.

Correctness contract:
- B1. Each measure equals its frozen definition on the scripted cases above.
- B2. A pass stores exactly one snapshot, lists every attempted question, and marks a stopped pass partial with the
  reason; runs it created carry purpose EVALUATION.
- B3. The endpoint is gated like `/api/rag/evaluate`; with `RECOMMENDATION_ENABLED` false it refuses with a clear
  error and makes no model call.
- B4. No new path to a prompt: the runner calls the service entry point; a test asserts the instruction-like screen
  still runs for evaluation questions' evidence.
- Commands: `./mvnw -q -o verify`.
- Out of scope: a judged support metric; changing prompts or profiles.
- User-facing flow (UT): lean app; `POST /api/rag/evaluate/answers?questions=aapl-08` (one live lean run): the
  snapshot shows the measures for aapl-08 and the run is stored as EVALUATION; disabled-flag refusal checked without a
  model call.

## Milestone 3: pilot, full lean pass, write-up

Scope: within the approved budget: the 6-question pilot, then the full 42-question lean pass; snapshot exports and
app-log windows under `live-runs/2026-09-19-answer-evaluation/`; a script for the aggregate table and the
per-question table (rank from the latest retrieval snapshot beside the answer measures); `claims.json`; a generated
block in Agent_Harness.md or RAG.md; Follow_Ups AGENT-10 DONE, AGENT-8 unblocked, AGENT-9 updated with what the
visible-to-model measure shows under the lean cut; RAG-27 and RAG-30 pointed at the result; PRD rows; CLAUDE.md loop
history.

Correctness contract:
- C1. Tokens used per pass are reported from the snapshots and stay within the approved budget; a stopped pass is
  reported as partial.
- C2. Every aggregate is recomputable from the committed snapshot by the committed script.
- C3. No evaluation run appears in outcome scoring, the track record, or the listings after the passes (queried).
- C4. No sentence states why the model answered as it did; measures are observed or derived.
- Commands: `./mvnw -q -o verify`; port 8081 free after the app stops.
- User-facing flow (UT): the committed snapshot reads back equal from the API; three per-question rows re-derived.

## Status

- 2026-09-19: plan written; awaiting Jay's approval of the milestones, the frozen measure definitions, and the token
  budget before Milestone 1's Worker starts.
- 2026-09-19: Jay approved ("proceed") the milestones, the frozen measure definitions, and the proposed token budget:
  one live lean run in each of the Milestone 1 and Milestone 2 UT checks (about 7,000 tokens each), the 6-question
  pilot (about 45,000), and one full lean pass of 42 (about 300,000), paced; a rate-limit rejection stops a pass, which
  is then recorded as partial and not repeated without approval. Milestone 1 starts.
