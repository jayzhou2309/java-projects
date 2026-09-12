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
