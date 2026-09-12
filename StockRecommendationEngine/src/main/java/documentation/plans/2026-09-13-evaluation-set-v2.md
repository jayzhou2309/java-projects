# Plan: evaluation set v2 and re-baseline (Follow_Ups RAG-11, with the RAG-12 figure leg finally measurable)

Orchestrated build per CLAUDE.md. Two serial milestones with contracts written before code. Branch:
`evaluation-set-v2` from `main` (6d48d99). Workers commit with specific `git add` paths, never push. Build:
`set -a; source .env; set +a; ./mvnw -q verify`; never export the application enable flags or any `RAG_RETRIEVAL_*`
override in a shell that runs tests. Token cost: no chat model; each evaluation run embeds one query per
question (v2 is about 40 questions), and Milestone 2 needs roughly six runs.

## Why

Two defects in the measurement itself, both found by the work it was measuring:

1. **Three misses are expectation narrowness, not retrieval failure.** For nvda-03 the Item 5 chunk states the
   identical repurchase sentence and is rank 1; for nvda-01 the adjacent segment table carries the same totals
   and is rank 3 under the current default; nvda-07's risk is restated in the 10-Q. The set counts these as
   failures, so every baseline understates hit@5 by up to 0.1 and any future change is judged against a
   pessimistic number.
2. **The set cannot measure the figure leg at all.** Its 30 questions contain no numeric token except years
   (the single exception is "200" split out of "H200"). The figure search added in RAG-12 therefore never ran
   during the grid, and its weight of 1.0 was settled by a tie-break plus one live query, not by evidence.

## Design constraints

- **Set v1 stays in the repository unchanged.** A new property `rag.evaluation.set` (default
  `evaluation/retrieval-set-v2.json`) selects the classpath resource, so v1 can still be run for comparison and
  every stored snapshot remains reproducible from its `setVersion`.
- **Metrics are not comparable across sets.** A v2 number may be higher or lower than a v1 number purely
  because the questions differ. Milestone 2 must re-derive the regression floor from a v2 baseline and say in
  the documentation that pre-v2 snapshots are only comparable with each other.
- **Expectations keep the v1 rules**: `accessionNo`, `sectionKey`, and a verbatim 12 to 200 character phrase
  from a chunk of that filing and section; never chunk ids; any one expectation satisfies. Alternative
  expectations are added only where a passage genuinely answers the question, not to paper over a miss.
- **New questions are written like an analyst asks them**, and for the figure questions the number appears in
  the question itself, because that is what triggers the figure leg. No question may be a copy of its phrase.

## Milestone 1: set v2 and a selectable set resource

Scope: `rag.evaluation.set` property (default v2, `@NotBlank`, validated as a readable classpath resource at
load time with a clear failure); `RetrievalEvaluationSetLoader.load()` reads it (keep `load(String resource)`
or equivalent for tests); `src/main/resources/evaluation/retrieval-set-v2.json` with `version` "v2",
`createdOn` 2026-09-13, and:
  - the 30 v1 questions unchanged in id, ticker, kind, and question text;
  - alternative expectations added to nvda-01, nvda-03, and nvda-07 (and to any other question where a second
    passage genuinely answers it), each verified against the store;
  - 10 to 14 new FIGURE questions whose text contains the figure, spread over all three tickers, drawn from the
    same latest filings, each with a verbatim phrase; ids continue the scheme (`aapl-11`, `msft-11`, `nvda-11`,
    and so on).
The snapshot already records `setVersion`; add the resource path to the snapshot `properties` as `set`.

Correctness contract:
- C1. Loading with the default property returns version "v2", `createdOn` 2026-09-13, 40 to 44 questions with
  unique ids, all v1 rules enforced (ticker pattern, question non-blank and at most 4000 characters, accession
  pattern, section key at most 64 characters, phrase 12 to 200 characters, no two questions sharing a phrase);
  loading with the property set to the v1 resource returns version "v1" with 30 questions; a property naming a
  missing resource fails with a clear `IllegalStateException` naming the resource.
- C2. Every expectation in v2 is satisfiable in the current store: the DB-backed test asserts, for each, at
  least one chunk of that accession and section contains the phrase (case-insensitive, whitespace-normalised),
  failing with the question id and expectation on a miss. It runs over whichever set the property selects, and
  a second test runs it explicitly over v1 so both stay honest.
- C3. v2 contains at least 10 questions whose text carries a numeric token that is not a lone year (assert with
  the same rule `FilingRetrievalRepository.figureTerms` uses: a non-empty `figureTerms(question)`), at least 3
  per ticker overall, and at least 8 questions per ticker in total; nvda-01, nvda-03, and nvda-07 each have at
  least two expectations; the 30 v1 ids are all present with their v1 question text (assert by loading both
  sets and comparing).
- C4. Snapshot `properties` carry `set` (the resource path) alongside `setVersion` in the record.
- Commands: `set -a && source .env && set +a && ./mvnw -q -o verify` exit 0;
  `./mvnw -q -o test -Dtest=RetrievalEvaluationSetTests,RetrievalEvaluationSetLoaderTests,RetrievalEvaluationServiceTests`.
- Out of scope: running the evaluation, re-baselining, weight changes, the floor, docs beyond the set format
  and property rows.
- User-facing flow: none (Scrutiny Validator only).

## Milestone 2: re-baseline on v2, re-measure the figure leg, decide, document

Scope: with the current defaults (k 60, weights 1.0 / 0.5 / 1.0), run v2 and v1 once each to record the new
baseline and the unchanged v1 reference; then measure a figure-weight grid on v2, at least
(fig 0.0), (fig 0.5), (fig 1.0), (fig 2.0) at keyword weight 0.5, plus (kw 1.0, fig 1.0) as a cross-check; all
with `hybrid` on. Apply the RAG-12 selection rule, restated for v2: the highest hit@5 such that no ticker's
hit@5 decreases against the v2 baseline and no FIGURE question in the top 5 under the v2 baseline leaves it;
ties by MRR, then by the smaller change from the current defaults. Set the winning weights as defaults if they
differ. Re-derive the floor: v2 winner's hit@5 − 0.1, rounded down to a multiple of 0.05 (this may raise or
lower `rag.evaluation.min-hit-at-5`; lowering is allowed here only because the set changed, and the
documentation must say so explicitly).

Correctness contract:
- C1. RAG.md records the v2 baseline and the grid, each row citing a stored snapshot id whose `properties`
  carry the configuration and the `set` it claims, with hit@1/3/5, MRR, per-ticker hit@5, and the count of
  FIGURE questions in the top 5; a separate short table shows the three narrow-miss questions (nvda-01,
  nvda-03, nvda-07) resolving under v2 and the figure questions' ranks with the figure leg on versus off.
- C2. The defaults in `application.yaml` and `FilingRetrievalProperties` equal the configuration the rule
  selects, with the rule shown row by row; the floor in `application.yaml` equals the re-derived value and its
  comment states the v2 derivation and that the v1 floor is superseded.
- C3. `./mvnw -q -o verify` exit 0; the opt-in live floor test passes with the final defaults and the v2 set
  (it must roll back; count `retrieval_evaluations` rows before and after).
- C4. Follow_Ups RAG-11 DONE with evidence; RAG-12 gains a line recording whether the figure weight survived
  measurement on v2; RAG-1 and RAG-7 rationales updated if v2 changes them; PRD phase 9 row cites the v2
  baseline; evidence under `documentation/live-runs/2026-09-13-evaluation-set-v2/` (every snapshot JSON, a rule
  table, run.log). RAG.md states plainly that v1 and v2 numbers are not comparable.
- Out of scope: retrieval code changes other than the weight defaults, reranking, parser fixes.
- User-facing flow (UT Validator): `GET /api/rag/evaluate/{id}` for the v2 baseline and the winner match the
  RAG.md tables; `POST /api/rag/retrieve` for one new figure question's text (quoted from v2) returns its
  expected chunk within the top 5 with strategy HYBRID_RRF and a figure-candidate count above zero in the
  response's effect (rank reported); the same query with `"hybrid":false` reports FILTERED_VECTOR.

## Gate rules

Both validators must pass before the next milestone; findings go to a fresh Worker; at most two remediation
rounds per milestone, then escalate. Validators never see the Worker's report or each other's output. Scrutiny
and UT run one after the other (shared Maven target directory).
