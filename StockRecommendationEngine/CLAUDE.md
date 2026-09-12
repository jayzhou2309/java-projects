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

## Loop history

- 2026-09-12, retrieval evaluation set (PR #10): three milestones, each passed
  Scrutiny and UT on the first round; plan in
  `documentation/plans/2026-09-12-retrieval-evaluation-set.md`.
