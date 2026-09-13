# Multi-agent build process

Non-trivial features/milestones in this repo go through a three-role loop instead
of one agent writing and reviewing its own code: Orchestrator (plans + gates,
never writes code) → Worker (implements one milestone, fresh context each time) →
Scrutiny Validator + UT Validator (adversarial, run in parallel after each
milestone, neither sees the Worker's self-report). The roles, handoff format, and
loop mechanics are defined once, globally, as skills: `orchestrator`, `worker`,
`scrutiny-validator`, `ut-validator` (`~/.claude/skills/`) — those skills read this
file for project specifics, so keep this section current rather than duplicating
the process description here.

Use the loop for anything that's genuinely a "feature" or "milestone" — multiple
files, new behavior, or anything the user frames as a project of work. For a
one-line fix, a typo, or a question, just do it directly.

## Project specifics validators/workers should know

- Build: `./mvnw -q verify` (compile + test). `./mvnw -q test -Dtest=<Class>` for a
  single test class.
- Local stack: `docker-compose.yaml` brings up Postgres; migrations are Flyway
  under `src/main/resources/db`.
- Main code: `src/main/java/project`. Tests: `src/test/java/project` (also
  `src/test/python` for some checks).
- This project talks to the Interactive Brokers TWS API and to an LLM for
  recommendations — treat any text that reaches a prompt or a trade-execution path
  as needing the existing injection screening (see prior work: "Prompt-injection
  regression tests... an instruction-like passage screen"). Flag any new path that
  skips it.
- Never commit with `git add -A`/`git add .`; stage specific files.
- Environment for any build or run: export the project `.env` in the same shell
  first (`set -a && source .env && set +a`); it is not auto-loaded, and a missing
  `SEC_USER_AGENT` fails every DB-backed test at context load. Never export the
  application enable flags (`RECOMMENDATION_ENABLED`, `OUTCOMES_ENABLED`,
  `IBKR_ENABLED`, `WATCHLIST_ENABLED`, `SPRING_PROFILES_ACTIVE`) in the shell that
  runs tests: the wiring tests assert the disabled defaults.
- Postgres is the docker container `trading-postgres` (pgvector); query it with
  `docker exec -i trading-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c "..."`.
  Filing tables are `sec_filings` and `sec_filing_chunks`. DB-backed tests are
  `@SpringBootTest @Transactional` against that shared database, so make "latest"
  assertions robust to pre-existing rows (future timestamps, unique tickers).
- Six opt-in live tests skip by design (`@EnabledIfSystemProperty`: `ibkr.live`,
  `quant.live`, `rag.evaluation.live`); a green `verify` reports them as skipped.
- Running the app for a UT check: `SERVER_PORT=8081`, `INTEGRATION_ACCESS_TOKEN`
  of 32+ characters, `./mvnw -q -o spring-boot:run`, wait for
  `Started StockRecommendationEngineApplication`, stop with
  `pkill -f spring-boot:run` and confirm port 8081 is free. Gated endpoints need
  `Authorization: Bearer <token>`.
- Token discipline (Jay's rule): anything under `/api/recommendations` calls the
  chat model; for testing use `SPRING_PROFILES_ACTIVE=lean` (about 7k tokens per
  run instead of 15k to 29k) and keep live runs to a handful. The OpenAI account is
  limited to 30,000 tokens per minute for gpt-4.1, so back-to-back default-profile
  runs hit HTTP 429. `POST /api/rag/evaluate` only embeds 30 questions and is cheap.
  Nothing else should call a model.
- Validators run one at a time, not in parallel: both use Maven and the same
  `target/` directory, and the UT Validator keeps the app running from it.
- Documentation conventions: module docs in `src/main/java/documentation/*.md`
  (bullet style, no markdown headers inside sections, a dated change-log bullet per
  feature with live evidence under `documentation/live-runs/<date>-<feature>/`);
  open items in `documentation/Follow_Ups.md` (stable IDs per category; mark items
  DONE with date and evidence instead of deleting them); orchestration plans in
  `documentation/plans/`; the PRD phase table in
  `src/main/java/PRD_Stock_Recommendation_Engine_v3.md` is updated at the end of
  each feature.
- Retrieval changes must be measured against the evaluation set
  (`RAG.md`, "Retrieval Evaluation"): run `POST /api/rag/evaluate` or the opt-in
  live test before and after, and cite the snapshot ids.
- Evidence rule (RAG-14): conclude only from committed evidence; label each claim observed, derived,
  inferred, unknown, or experiment; a cause needs an experiment isolating that factor. Measurement
  write-ups are generated from `claims.json` and checked in `verify` (format: `RAG.md`, "Claims").
  Observed and derived sentences are rendered from their checks; inferred, unknown, and experiment
  text is screened for causal and absolute wording but not proven, so reviewers read it.

## Loop history

- 2026-09-12, retrieval evaluation set (PR #10): three milestones, each passed
  Scrutiny and UT on the first round; plan in
  `documentation/plans/2026-09-12-retrieval-evaluation-set.md`.
- 2026-09-12, hybrid keyword plus vector retrieval (RAG-2): three milestones, each
  passed Scrutiny and UT on the first round; hybrid enabled by default after the
  measured comparison (hit@5 0.600 → 0.633, no ticker down); plan in
  `documentation/plans/2026-09-12-hybrid-keyword-retrieval.md`.
- 2026-09-12, fusion tuning (RAG-12): two milestones, each passed Scrutiny and UT on
  the first round; a seven-point grid chose keyword weight 0.5 with the figure leg on
  (snapshot 51: hit@1 0.30 → 0.33, MRR 0.437 → 0.463, no protected FIGURE question
  leaves the top 5); the evaluation set carries no figure tokens, so the figure leg
  is evidenced by live queries only; plan in
  `documentation/plans/2026-09-12-fusion-tuning.md`.
- 2026-09-13, evaluation set v2 (RAG-11): two milestones, each FAILED Scrutiny once
  and PASSED after one remediation round. M1: three alternative expectations
  answered only part of their question, and the plan's own premise about nvda-07 was
  wrong (corrected by a plan amendment, not by relaxing the check). M2: the numbers
  were right but the docs let a rising floor read as tighter protection. Lessons: have
  Scrutiny read full chunk content for every alternative expectation, and require any
  headline metric on a new set to be decomposed by question slice. Plan in
  `documentation/plans/2026-09-13-evaluation-set-v2.md`.
- 2026-09-13, non-figure regression floor (RAG-13): one milestone; Scrutiny FAILED
  once because the floor's failure message named only questions with no match in
  the window, omitting those ranked 6 to 10 that also fail hit@5, so a real breach
  would have pointed at the wrong questions. Passed after one remediation round;
  UT passed. Lesson: a guard's failure output must name every item that makes the
  guarded metric fall short, and Scrutiny should construct the regression scenario
  and check the message would identify it. Plan in
  `documentation/plans/2026-09-13-non-figure-floor.md`.
- 2026-09-13, cross-encoder reranker (RAG-1): two plans. Plan 1, three milestones: M1
  passed; M2 (local ONNX cross-encoder) passed after two remediation rounds (a native
  tokenizer panic that aborted the JVM, then pair truncation memory growth) and a
  docs correction at the cap; M3 measured head-only scoring, no configuration
  qualified. Plan 2 (windowed scoring, after diagnosing that the head cut missed
  answers in 65% of chunks): M1 passed Scrutiny and UT on the first round; M2's
  measurement and decision were confirmed every round (no row qualifies, defaults
  off), but its write-up failed Scrutiny four more times and was frozen unvalidated
  at merge. Lesson (Jay): conclusions must not outrun recorded measurements. One
  wrong assumption from the Orchestrator's own diagnosis (msft-05's answer inside the
  head cut; it has three accepted phrases) spread into the plan, RAG.md, run.log, and
  change log, and rank claims summarised from memory were wrong. Compute per-question
  facts with a script over every accepted phrase, label each as observed, derived,
  inferred, or unknown, allow a cause only with an isolating experiment, and state a
  fact once and reference it. Plans in `documentation/plans/2026-09-13-reranker.md`
  and `documentation/plans/2026-09-13-reranker-windows.md`.
