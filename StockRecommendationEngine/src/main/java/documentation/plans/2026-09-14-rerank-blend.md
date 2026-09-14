# Plan: blend reranker order with fused order (Follow_Ups RAG-20 candidate; after plan 2026-09-14-retrieval-recall)

Orchestrated build per CLAUDE.md. Branch `rerank-blend` stacked on `retrieval-recall` (PR #19, not merged; rebase onto
`main` once it is). Build: `set -a && source .env && set +a && ./mvnw -q -o verify`; never export the application enable
flags or `RAG_*` overrides in a shell that runs tests. Validators run one at a time.

## Why

- In snapshot 947 (`candidate-count` 200, reranking at `rerank-candidates` 40) every question's accepted chunk is a rerank
  input (retrieval-recall plan, Milestone 2), yet questions still miss the top 5, and the selection row against the default
  reference 598 fails (claim C-657). The recorded traces show the reranker moving some accepted chunks up and others down
  (evidence `live-runs/2026-09-14-recall-one-factor/evidence-947.json`, fields `fusedPosition` and `rerankedPosition`).
- Question: does an order that blends reranked position with fused position do better on questions it was not tuned on?
  No cause is asserted for the reranker's moves.
- Traces record, for every rerank input, its fused position and reranked position, so any blend below can be recomputed
  from committed evidence with no model call.

## Frozen before any simulation (this section may not change after the commit that adds it)

- Split rule: stratum = (ticker, kind) from `src/main/resources/evaluation/retrieval-set-v2.json`; within each stratum,
  question ids sorted by the hex SHA-256 of `"rerank-blend-split-v1:" + id`; each stratum holds out floor(n/3), plus one
  for the strata with the largest remainders (ties by stratum name) until 14 are held out; the first ids in sorted order
  are held out.
- Held-out set (14), as that rule yields: aapl-04, aapl-05, aapl-11, aapl-12, aapl-14, msft-02, msft-03, msft-04, msft-07,
  msft-10, nvda-04, nvda-08, nvda-10, nvda-11. The tuning set is the other 28. Milestone 1's script recomputes the rule and
  fails if it yields a different list.
- Input: snapshot 947 and its evidence report (primary); snapshot 932 (fused order check); snapshot 598 (default
  reference). No other snapshot is used to choose.
- Blend: for a question, the rerank inputs are the first 40 fused chunks with their fused position f and reranked
  position r (1-based within the inputs). Blended score = w / (k + r) + (1 - w) / (k + f). Inputs are ordered by blended
  score descending, ties by smaller f; fused chunks after the inputs keep fused order; the returned window is the first 10.
  Rank = 1-based position of the first returned chunk matching an accepted phrase (the evaluation's rule), null if none.
- Grid (6 points): k in {10, 60} x w in {0.25, 0.5, 0.75}.
- Checks, not choices: w = 1 must reproduce 947's per-question ranks; w = 0 must reproduce 932's per-question ranks
  (same fused lists) or the milestone stops and reports the differing questions.
- Choice (tuning set only): the grid point with the highest tuning hit@5; ties by higher tuning MRR, then smaller w, then
  k 60. Held-out metrics are never computed for any grid point other than the chosen one.
- Held-out test, run once on the chosen point against 598 on the 14 held-out questions: PASS if held-out hit@5 is at least
  598's and no held-out FIGURE question ranked 1 to 5 in 598 leaves the top 5. The full-42 selection rule against 598 is
  reported beside it for the decision but does not replace the held-out test.
- If the held-out test fails: the result is recorded as the outcome, Milestones 2 and 3 do not run, no weight is adjusted,
  and the next step returns to Follow_Ups RAG-20. No second grid, split, or blend form is tried against these 14 questions.

## Milestone 1: offline blend simulation from committed traces

Scope: a deterministic script under `live-runs/2026-09-14-rerank-blend/` reading only the committed snapshots and evidence
named above; outputs: split check, w = 0 and w = 1 reproduction checks, tuning metrics for the 6 grid points, the chosen
point, its held-out test, and the full-42 selection rule against 598; claims file (ids C-801 to C-899) and a generated
RAG.md block in a new subsection "Rerank blend simulation"; one change-log bullet; Follow_Ups entry. No app start, no model
call, no application code change.

Correctness contract:
- S1. The script recomputes the split by the frozen rule and fails unless it equals the frozen list.
- S2. w = 1 reproduces 947 and w = 0 reproduces 932 per question, or the script stops naming every differing question.
- S3. Tuning hit@5 and MRR per grid point, the chosen point by the frozen tie order, and held-out metrics only for the
  chosen point (a validator confirms no held-out value for another point appears in any output or claim).
- S4. The held-out outcome is stated as PASS or FAIL by the frozen rule, naming every held-out question that decides it;
  a FAIL is written as the result, with no follow-on tuning.
- S5. `git log` shows the frozen section committed before the first commit adding the script; the frozen section is
  unchanged at the milestone's head (`git diff <plan commit> HEAD -- this file` touches only Status).
- S6. Evidence rule: no cause for any reranker move; prose outside the block carries no numbers.
- Commands: `./mvnw -q -o verify` exit 0; script re-run reproduces its outputs byte for byte.
- Out of scope: application code, live runs, any other blend form.
- User-facing flow: none (Scrutiny only).

## Milestones 2 and 3 (only if Milestone 1's held-out test passes; contracts written then, before code)

- M2: the blend as a retrieval setting, off by default; traced and untraced paths identical; the blended order equals the
  simulation on scripted traces.
- M3: one live traced run at the chosen point with `candidate-count` 200 and `rerank-candidates` 40; its ranks must equal
  the simulation's; the selection rule against 598; a DECISION item for Jay if it passes. Defaults unchanged.

## Status

- 2026-09-14: plan written; split, grid, blend, choice rule, and held-out rule frozen by this commit, before any
  simulation code or result.
