# Plan: retrieval evaluation set (Follow_Ups AGENT-3)

Orchestrated build per CLAUDE.md. Three serial milestones, each with a correctness contract written before code.
Branch: `retrieval-evaluation` from `main` (77cca58 + CLAUDE.md commit). Workers commit with specific `git add`
paths, never push. Full build command: `set -a; source .env; set +a; ./mvnw -q verify` (never export the
application's enable flags such as RECOMMENDATION_ENABLED in the shell that runs the build: the wiring tests
assert the disabled defaults). Token cost: evaluation runs embed each question once (text-embedding-3-small);
no chat model is called anywhere in this plan.

## Why

Every retrieval or prompt change so far was judged by single live runs. Reranking (RAG-1), hybrid retrieval
(RAG-2), lean-profile passage tuning (AGENT-9), and prompt regression checks (AGENT-8) all need a fixed,
versioned question set with known-good passages, measured the same way every time.

## Stored material the set is written against (2026-09-12)

Latest filing per type, which the default retrieval policy searches:
AAPL 10-K 0000320193-25-000079 (76 chunks), 10-Q 0000320193-26-000020 (31), 8-K 0000320193-26-000018 (2);
MSFT 10-K 0001193125-26-323660 (163), 10-Q 0001193125-26-191507 (95), 8-K 0001193125-26-380280 (2);
NVDA 10-K 0001045810-26-000021 (115), 10-Q 0001045810-26-000075 (47).
Chunk IDs change on rebuild, so expectations are (accessionNo, sectionKey, phrase), never chunk IDs.

## Milestone 1: the set and its loader

Scope: `src/main/resources/evaluation/retrieval-set-v1.json` with 24 to 30 questions across AAPL, MSFT, NVDA
covering at least Items 1A, 7 (or 10-Q Item 2), 8, 1, and an 8-K item, mixing figure questions (an exact
number stated in the filing) and narrative questions (a risk, a segment change, a policy). Each question:
`id`, `ticker`, `question`, `expected` (one or more of `accessionNo`, `sectionKey`, `phrase`; any one
satisfies), optional `notes`. A record model plus `RetrievalEvaluationSetLoader` reading the classpath
resource and validating it. A short RAG.md paragraph naming the file and the format.

Correctness contract:
- C1. Loading the resource returns a set with `version` "v1", a `createdOn` date, and between 24 and 30
  questions with unique ids; every ticker matches `[A-Z0-9.-]{1,16}`; every question is non-blank and at most
  4000 characters; every expectation has an accession number matching `\d{10}-\d{2}-\d{6}`, a section key of
  at most 64 characters, and a phrase of 12 to 200 characters.
- C2. A malformed resource (duplicate id, empty expected list, blank phrase) makes the loader throw an
  IllegalStateException naming the question id; verified by unit tests on in-memory JSON.
- C3. Every expectation is satisfiable in the current store: a DB-backed test (`@SpringBootTest`,
  read-only) asserts for each expectation that at least one chunk of that accession and section contains the
  phrase (case-insensitive, whitespace-normalised). The test fails with the question id and expectation on a
  miss.
- C4. At least 8 questions per ticker; at least 6 figure questions in total; no two questions share the same
  expected phrase.
- Commands: `./mvnw -q verify` exit 0 with .env exported; `./mvnw -q test -Dtest=RetrievalEvaluationSetTests`.
- Out of scope: running retrieval, metrics, endpoints, migrations.
- User-facing flow: none (Scrutiny Validator only).

## Milestone 2: evaluation run, snapshot, endpoints

Scope: `RetrievalEvaluationService.evaluate()` runs every question through `FilingRetrievalService.retrieve`
with the ticker, the question as the query, `latestFilingsOnly` true, and a fixed window `topK` = 10. For
each question the rank is the 1-based position of the first returned chunk matching any expectation (same
accession and section, content contains the phrase); null when none matches within the window. Metrics:
hit@1, hit@3, hit@5 (fractions), MRR over the window, per-ticker hit@5, and a `misses` list carrying the
question id and the top three returned chunks (chunkId, accessionNo, sectionKey, similarity). Snapshots are
appended to `retrieval_evaluations` (migration V8: id, evaluated_at, set_version, question_count, hit_at_1,
hit_at_3, hit_at_5, mrr, window, retrieval_strategy, properties JSONB, results JSONB). Endpoints, token-gated
like the other integrations: `POST /api/rag/evaluate` (run and store), `GET /api/rag/evaluate` (newest
snapshot, 404 before the first), `GET /api/rag/evaluate/{id}`.

Correctness contract:
- C1. With a mocked retrieval service returning scripted results, the service computes hand-checkable
  metrics: for 4 questions ranked 1, 3, null, 2 within window 10: hit@1 0.25, hit@3 0.75, hit@5 0.75,
  MRR (1 + 1/3 + 0 + 1/2)/4 = 0.458333; the miss entry carries the unmatched question's id and its top three
  results; a retrieval exception for one question is recorded as a miss with an error string and the others
  still count.
- C2. A phrase matches case-insensitively with runs of whitespace collapsed; accession and section must both
  equal the expectation's; a chunk from the right filing but another section does not match.
- C3. Snapshot round trip through PostgreSQL: save returns an id; `latest()` returns the newest by
  evaluated_at; results JSONB preserves per-question ranks and misses; a second save yields a new id.
- C4. Endpoints: without the token 401; POST returns the stored snapshot (with its id) whose questionCount
  equals the loaded set size; GET returns the same snapshot; unknown id 404. Wiring test proves the new
  controller is covered by the access interceptor.
- Commands: `./mvnw -q verify` exit 0 with .env exported; `./mvnw -q test
  -Dtest=RetrievalEvaluationServiceTests,RetrievalEvaluationRepositoryTests,IntegrationWiringTests`.
- Out of scope: the regression floor test, docs beyond endpoint mentions, any change to retrieval itself.
- User-facing flow (UT Validator): start the app with .env exported plus `INTEGRATION_ACCESS_TOKEN` set to
  any 32+ character value and `SERVER_PORT=8081`; `POST /api/rag/evaluate` with the bearer token returns 200
  and JSON with `questionCount` between 24 and 30, `hitAt5` between 0 and 1, and a non-empty `results`
  array; `GET /api/rag/evaluate` returns the same `id`; `GET /api/rag/evaluate/999999` returns 404; a request
  without the token returns 401; `POST /api/rag/retrieve` (existing) still returns 200 for an AAPL query.

## Milestone 3: baseline, regression floor, documentation

Scope: an opt-in live test `RetrievalEvaluationLiveTests` (`@EnabledIfSystemProperty(named =
"rag.evaluation.live", matches = "true")`) that runs the real evaluation against the local store and asserts
hit@5 at or above `rag.evaluation.min-hit-at-5` (property, default set from the baseline minus 0.1, rounded
down to a multiple of 0.05). Documentation: a "Retrieval Evaluation" section in RAG.md (format, how to add a
question, metric definitions, endpoints, the first baseline table with snapshot id and date, the per-question
misses and what they suggest), Follow_Ups AGENT-3 marked DONE with a new item for answer-level evaluation
under the lean profile, PRD phase 9 row updated, evidence under `documentation/live-runs/2026-09-12-retrieval-eval/`.

Correctness contract:
- C1. `./mvnw -q test -Dtest=RetrievalEvaluationLiveTests -Drag.evaluation.live=true` exits 0 against the
  local store with .env exported, and the same command with the floor property raised to 1.01 exits non-zero
  (proves the assertion is live).
- C2. RAG.md's baseline table matches a stored snapshot (id, date, hit@1/3/5, MRR, question count) that
  `GET /api/rag/evaluate/{id}` returns.
- C3. Without the system property the live test is skipped and `./mvnw -q verify` exits 0.
- Commands: as above.
- Out of scope: improving retrieval; the misses are recorded, not fixed.
- User-facing flow: none beyond M2's (Scrutiny Validator; UT Validator re-runs M2's GET for the baseline id).

## Gate rules

Both validators must pass before the next milestone. Findings go to a fresh Worker; at most two remediation
rounds per milestone, then escalate. Validators never see the Worker's report or each other's output.
