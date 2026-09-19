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
- Opt-in live tests skip by design (`@EnabledIfSystemProperty`: `ibkr.live`, `ibkr.live.conid`,
  `ibkr.live.history`, `quant.live`, `rag.evaluation.live`, `rag.rerank.live`,
  `rag.rerank.gte.live`, `rag.reproduction.run`); a green `verify` reports them as skipped (37 tests on 2026-09-17, the five methods of
  `CrossEncoderAnswerVisibilityLiveTests` included).
- Running the app for a UT check: `SERVER_PORT=8081`, `INTEGRATION_ACCESS_TOKEN`
  of 32+ characters, `./mvnw -q -o spring-boot:run`, wait for
  `Started StockRecommendationEngineApplication`, stop with
  `pkill -f spring-boot:run` and confirm port 8081 is free. Gated endpoints need
  `Authorization: Bearer <token>`. A live recommendation run (anything that reaches
  the chat model, `RECOMMENDATION_ENABLED=true`) also needs `CHAT_MODEL_PROVIDER=openai` and
  `RECOMMENDATION_MODEL=gpt-4.1` in the app shell (added 2026-09-19).
- Token discipline (Jay's rule): anything under `/api/recommendations` calls the
  chat model; for testing use `SPRING_PROFILES_ACTIVE=lean` (about 7k tokens per
  run instead of 15k to 29k) and keep live runs to a handful. The OpenAI account is
  limited to 30,000 tokens per minute for gpt-4.1, so back-to-back default-profile
  runs hit HTTP 429. `POST /api/rag/evaluate` only embeds 30 questions and is cheap.
  `POST /api/rag/evaluate/answers` (since 2026-09-19) calls the chat model once per question run:
  a lean pass over the 42 questions used 258,639 tokens on 2026-09-19 (about 6,200 per run, paced
  20 s), so every pass needs a budget Jay approved first and is never repeated or retried without
  approval; its `GET` endpoints are free. Nothing else should call a model.
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
  inferred, unknown, or experiment; a cause needs an experiment isolating that factor. Write a
  measurement's conclusions in its `claims.json`; `verify` checks the claims and the generated
  blocks written from them (format: `RAG.md`, "Claims"). Only block bullets are generated, and only
  observed and derived bullets are rendered from their checks. Prose outside blocks may not cite a
  claim; only the nearest line above each block's start marker that is not blank once normalised
  (skipped: blank lines and lines holding only a one-line HTML comment or tag such as `<br>`,
  markdown marks such as `***` or `___`, `&nbsp;`, invisible format characters (Unicode Cf), or
  whitespace and Unicode space separators (Zs) such as U+00A0; the exact set is `Wording.blank`) is
  screened for causal and absolute words and numbers, so a wrapped lead-in's earlier lines, a setext
  heading's text, and text above a multi-line comment are not.
  Claim ids (`C-` and at least three digits) must be unique across claims files whose blocks share a
  document; give each such file its own number range.
  The check does not prove, so reviewers and validators read all of: prose outside generated
  blocks; labels; the free text of inferred, unknown, and experiment claims (screened for listed
  words only), including numbers that contradict premises, inferences drawn from unknown claims,
  and an experiment text's factor and cause; whether referenced JSON files are committed, unaltered
  exports (a hand-edited copy with id 297 renders as snapshot 297); that window wording rests on
  max-length from configuration at report time; and settings snapshots do not record (for example
  rerank-timeout-ms). (Corrected 2026-09-13, plan amendment 6: this rule said write-ups are
  generated from `claims.json` without saying that only block bullets are. Corrected 2026-09-14:
  it did not say only one line above a block is screened, and it gave no rule for claim ids.
  Corrected again 2026-09-14, Milestone 4b: the skipped lines were given as blank lines and one-line
  comments only, and the note before this one named only the one-line limit, not that a wrapped
  lead-in's earlier lines, a setext heading's text, and text above a multi-line comment go
  unscreened. Corrected 2026-09-14, Milestone 4b remediation round 1: the Milestone 4b correction
  rewrote the earlier note in place; that note's text is restored and this addition follows it.
  Corrected 2026-09-14 at the Milestone 4b remediation cap: the skip list named only "invisible
  characters" and not the Unicode space separators the check also skips, or that other blank-looking
  characters are not skipped; and the Milestone 4b note above was reworded in remediation round 1
  without its own mark (its earlier wording is readable at commit 95a3f63). Corrected again 2026-09-14,
  after that cap check: "other blank-looking characters are not skipped" was too broad, since the
  check also skips whitespace such as line and paragraph separators; the list now points to
  `Wording.blank`.)

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
  qualified. Plan 2 (windowed scoring, after a truncation probe found 65% of chunks
  longer than the window and, for 11 of 39 matched questions, the answer offset past
  the kept tokens; corrected 2026-09-14, this entry first said answers were missed in
  65% of chunks): M1 passed Scrutiny and UT on the first round; M2's
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
- 2026-09-13 to 2026-09-14, recorded retrieval evidence and checked claims (RAG-14):
  four milestones, the fourth split to run in parallel worktrees. M1 (traces) failed
  Scrutiny once (its reproduction test named no question when a fallback coincided
  with a difference), then passed with UT. M2 (evidence report) passed both on the
  first round. M3 (claims check) failed Scrutiny three times, each time on text the
  checker did not read (free-text sentences beside checks, prose citing claims, one
  screened lead-in line promised as a paragraph); it was redesigned so observed and
  derived sentences are rendered from their checks, and closed at the cap with
  Orchestrator wording fixes approved by Jay. M4a (traced re-measurement) passed first
  time: all five runs reproduced 295 to 299. M4b (write-up regenerated as generated
  blocks) passed UT every round (1,396 values against the live app) but failed
  Scrutiny on hand-written history: correction notes kept restating results, so each
  rewording created a new copy; it closed at the cap after collapsing that history
  into pointers. Lessons: a checker proves only what it reads, so its documentation
  must list what it does not read; history notes that describe corrected results
  re-state them, so keep correction history in commits and run logs and leave a
  pointer; count completeness of a generated list from the evidence, not from the
  lead-in; the Orchestrator's own plan text and cap fixes need the same validation
  (a W- id prefix it prescribed would have failed verify). Plan in
  `documentation/plans/2026-09-13-evaluation-evidence.md`.
- 2026-09-14, candidate recall and diversification evidence (RAG-18, RAG-15): Plan A, four
  milestones, from an external review checked against committed evidence first. M1 (recall
  from traces) and M3 (diversification removals in traces) passed Scrutiny and UT on the first
  round. M2 (one-factor candidate-count experiment) failed Scrutiny once on a selection row
  judged against a reranked configuration (amendment 3: selection outcomes only against the
  default reference), then passed with UT; its low-finding wording fixes failed two scoped
  checks on change-log pointers and closed at the cap by collapsing them into one pointer
  (Jay approved). M4 (`Project_Challenges.md`) failed Scrutiny twice on lessons attributed
  beyond their sources, including a note of Jay's with no repository record until the plan
  Status recorded it, and passed on round 2. Lessons: a rule row needs a named reference that
  is the default; fixing a history note creates a new history note, so collapse to a pointer
  early; a user note folded into a document needs a committed record before the document
  cites it. Plan B (RAG-20) is gated. Plan in `documentation/plans/2026-09-14-retrieval-recall.md`.
- 2026-09-15, rerank blend (RAG-21): split, grid, choice and held-out rules frozen in a commit
  before any simulation (Jay's rule); the one held-out test passed by equalling the default
  reference, recorded as no gain, and Jay stopped before implementation. Plan in
  `documentation/plans/2026-09-14-rerank-blend.md`.
- 2026-09-15, second reranker model (RAG-22): the first candidate's official ONNX file had no
  scoring head (found by the Worker's startup probe before any code; amendment 1, Jay chose
  gte-reranker-modernbert-base); the Orchestrator's premise that the assembler rejected the
  new template was also wrong (amendment 2). M1 passed first round; M2 failed Scrutiny once
  because the paired runs' candidate lists before reranking were never compared (a run from an
  earlier session differed), passed after a same-session current-model run (amendment 3).
  Lessons: probe a downloaded model's graph before designing around it; a model comparison
  must check candidate-list identity per question, not only settings. Decision RAG-23 open.
  Plan in `documentation/plans/2026-09-15-reranker-ettin.md`.
- 2026-09-17 to 2026-09-18, chunk size (RAG-15 lever 2, RAG-25, RAG-26, RAG-27, RAG-29): four milestones.
  M1 (the answer-visibility diagnostic over set v2) passed Scrutiny and UT on the first round, with one low
  finding (a stale contract reference in RAG.md) fixed under a scoped check; its frozen size rule chose
  none, and Jay overrode it (amendment 3) for a rebuild at 1,000 / 125 with the selection rule unchanged.
  M2 (configurable chunk size, recorded in snapshots and the processing version) passed both on the first
  round. M3 rebuilt the store, and the R1 gate failed there (55 of 57 accepted phrases held): one phrase
  split at a chunk boundary of the smaller cut, and one under an 8-K section key the re-parse now writes
  with its sub-item; the rollback rebuild at 4,000 / 500 restored the text but not that key, so the
  post-rollback run differed from the baseline on aapl-08 and the database-backed set test failed
  (RAG-29). Scrutiny failed M3 on those two database-state items (not attributable to the diff; every
  derived number recomputed equal) and seven documentation findings; the remediation passed a scoped
  re-check and UT passed. M4 (the RAG-29 fix: the set entry's key corrected, the two id-bound live tests
  re-pointed to a fresh traced snapshot) failed Scrutiny once on a sentence that overstated what the
  compared runs shared (different store rebuilds, vectors not compared); the remediation passed; UT passed
  after `verify` failed on the Orchestrator's own plan note, which cited a claim id in prose and was
  reworded. Lessons: a rebuild re-parses with the parser in the tree, so a rollback under a newer parser is
  not a restore of section keys, and keys and text are exported before any rebuild; a rule-driven size
  choice can be overridden by the user, but the selection rule stays; the Orchestrator's plan text is under
  the claims check like every other document. Plan in `documentation/plans/2026-09-17-chunk-size.md`.
- 2026-09-17, chunk size and candidate pool (RAG-27, RAG-30): one milestone, design frozen before any
  run (stores 4,000 / 500 and 1,650 / 250, pools 40 to 250, reranking off and on, choice on 28 tuning
  questions, one held-out test on 14). The default reference reproduced 1615, the phrase gate held with
  aapl-08 exempt, no run fell back, the held-out test of the rebuilt store's chosen points was FAIL in
  both reranker states, no default changed, and the rollback restored 569 chunks equal by content hash
  (post-rollback run equal to the reference per question). Scrutiny recomputed every number, choice,
  held-out outcome, latency and storage value equal but failed round 1 on the evidence rule (an inferred
  claim with clauses its premises did not support; two equality claims resting on text files; a sentence
  misdescribing the plan's Reported list); remediation round 1 added a test-scope `questionEquality` check
  so both equalities are derived by `verify`, and its scoped re-check failed on three narrow gaps of the
  same class; remediation round 2 audited all ten inferred claims clause by clause and passed with two
  low notes; UT passed. Lesson (repeated from earlier loops): an inferred sentence needs a premise or a
  named source per clause, and a blanket sentence about one's own claims is itself a claim. Worker notes:
  a reference that records a list property broke `TraceReproductionCheck` until it read lists as text;
  subset metrics needed a check type (`subsetMetric`); export content hashes before a rebuild so a
  rollback can be compared in whole text. Plan in `documentation/plans/2026-09-17-chunk-size-pool.md`.
- 2026-09-19, answer-level evaluation (AGENT-10): three milestones, the first plan in the retrieval
  series that spends chat-model tokens, with the milestones, the frozen measure definitions and a
  token budget approved before any run. M1 (evaluation runs marked by `recommendations.purpose`,
  V10, and kept out of scoring, the track record and the listings) passed Scrutiny and UT on the
  first round; the Worker also guarded a score-by-id path the plan had not listed. M2 (runner
  `POST /api/rag/evaluate/answers`, deterministic measures, `answer_evaluations`, V11) passed both
  with eight low findings, fixed before any paid pass because three protected the budget (a failed
  snapshot save keeps a fallback file, a retrieval outage stops a pass, shares also among answered
  runs); the scoped re-check found a pre-existing race on the instruction-like passage ids, fixed
  too. M3 froze the pilot questions and the gate (not partial, mean at most 10,000 tokens per run)
  before the runs, then ran the pilot (snapshot 37, 34,855 tokens) and one lean pass of 42
  (snapshot 38, 258,639 tokens), neither partial; plan total 304,866 tokens. Results only in the
  generated blocks of Agent_Harness.md, Answer Evaluation, First live passes (M3's validator
  outcomes are for the Orchestrator to add at close-out). Lessons so far: a
  live app needs `CHAT_MODEL_PROVIDER` and `RECOMMENDATION_MODEL` as well as the enable flag (the
  first UT instruction lacked them); protect the write at the end of a paid pass before the pass
  is run; freeze the gate between a pilot and the full spend in a commit. Plan in
  `documentation/plans/2026-09-19-answer-evaluation.md`.
