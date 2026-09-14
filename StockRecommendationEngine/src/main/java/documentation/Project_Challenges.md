# Project Challenges

* Purpose
    * A record of the problems this project ran into, what happened, and what was learned, gathered from the project's own history: CLAUDE.md "Loop history", the plans and their Status and Amendment sections, [Follow_Ups.md](Follow_Ups.md), the module docs, live-run logs, and commits.
    * It summarises; it is not a source. Every bullet ends in a pointer to the record that holds the event, and that record wins wherever the two differ.
    * Measurement results are not restated here. A result is referred to by its snapshot id, claim id, or generated block, so this page holds no copy of a value that could drift from the evidence (the RAG-14 Milestone 4b lesson in CLAUDE.md "Loop history").
    * A cause is given only where the pointed-to source states one; a lesson is attributed to whoever the source attributes it to (Jay, a validator, a plan).

* How to read
    * One section per theme. Each bullet names the challenge, then what happened, then the lesson, then the pointer.
    * Pointers are repository paths (relative to the StockRecommendationEngine project directory, where CLAUDE.md sits), Follow_Ups ids, plan milestones or amendments, and commit ids.

## Broker sessions and market data

* Client Portal sessions that were never ready
    * Challenge: the first live manager runs on 2026-09-10 went through the Client Portal gateway, and broker discovery kept returning SESSION_NOT_READY.
    * What happened: a retry after login and a session reset both still returned SESSION_NOT_READY, with the gateway reporting not authenticated; a later ready session reached discovery but received null prices, with the cause recorded as not established. The adapter was then replaced by the TWS socket API, and the Client Portal implementation was archived.
    * Lesson (Agent_Harness.md, Follow_Ups BROKER-4): those runs stayed PARTIAL with NO_VERIFIED_CURRENT_QUOTE in their limitations, and a stale session still fails silently until the next request, so a heartbeat with reconnect is open.
    * Pointers: `src/main/java/documentation/Agent_Harness.md` (Historical change log, Live retry after user login, Session reset retry, Ready-session retry); `src/main/java/documentation/live-runs/2026-09-10-session-reset/`; `src/main/java/documentation/IBKR_Client_Portal_History.md`; Follow_Ups BROKER-4.

* Delayed quotes that did not arrive over TWS
    * Challenge: after the TWS migration, the quote path returned no delayed prices in both the Java adapter and an independent Python SDK probe, even after a clean TWS restart.
    * What happened: IBKR.md records a sequence of diagnostics: an entitlement rejection for realtime data, delivery that appeared after a probe requested a primary exchange, closed-market ticks where the delayed close was the only price, and on 2026-09-11 with the market open the finding recorded as the root cause: SMART-routed requests for NASDAQ stocks received no delayed data on this account while primary-exchange requests did. The adapter now requests quotes on the primary exchange and maps the delayed close tick.
    * Lesson (IBKR.md): earlier entries keep "cause not established" until the market-open comparison of request shapes; its consequence note says a delayed quote still cannot make a run COMPLETE, which needs a realtime subscription.
    * Pointers: `src/main/java/documentation/IBKR.md` (Delayed quote diagnostic, Delayed data delivered, Closed-market close mapping, Root cause of missing delayed quotes); commits 54716dc and 44e5807; `src/main/java/documentation/live-runs/2026-09-11-quote-primary-exchange/`; Follow_Ups BROKER-1 and BROKER-2.

* Data-gated phases waiting on broker-on runs in US hours
    * Challenge: outcomes, calibration, the track record, and critic evaluation need scored directional runs, which need TWS logged in and the broker, quant, and outcomes enabled during US regular hours.
    * What happened: every stored run so far is NEUTRAL or brokerless, so the pipeline is verified with scripted tests only; the watchlist scheduler was added to produce directional runs, and keeping the stack up over those hours is left as operations work.
    * Lesson (Follow_Ups preamble): items that need a market session or accumulated data are logged with what unblocks them, and closed items are marked rather than deleted.
    * Pointers: `src/main/java/documentation/Outcomes.md` (Implementation Status); Follow_Ups DATA-1, DATA-3, AGENT-2, QUANT-1; commit 0ba15f2.

## LLM cost and token limits

* The provider's tokens-per-minute limit
    * Challenge: the chat model account has a tokens-per-minute ceiling that a single default-configuration run with a critic revision can approach.
    * What happened: on the first live watchlist pass on 2026-09-12 the second ticker's critic call was rejected with HTTP 429, and the exception escaped as a FAILED run with zero counters and no logged cause. The harness now logs the cause, stops a failed model call as MODEL_UNAVAILABLE with counters kept, retries a rate-limited call once, and pauses between watchlist tickers; a lean profile cuts tokens per run.
    * Lesson (Jay's token discipline rule in CLAUDE.md): test with the lean profile, keep live recommendation runs to a handful, and let nothing but the documented endpoints call a model; the ceiling itself remains open.
    * Pointers: `src/main/java/documentation/Agent_Harness.md` (Watchlist schedule and provider rate limits, Where the Tokens Go, and the Lean Profile); `src/main/java/documentation/live-runs/2026-09-12-watchlist/response-msft-retry.json`; commits 0ba15f2 and 0c0ffec; Follow_Ups AGENT-9; CLAUDE.md "Project specifics".

## Prompt-injection screening

* A hostile question that steered the model away from research
    * Challenge: filing text and user questions reach model prompts, and before 2026-09-12 no test injected instructions and checked that they were ignored.
    * What happened: scripted regression tests with an "obedient" model showed each attempt blocked by code, and an instruction-like passage screen was added that discloses matches to the manager and critic. A live hostile question under the lean profile was held by the application, but its trace showed the RAG specialist had not searched at all, since the question's instruction not to search filings had worked on the model. A filings prefetch before the specialist model closed that gap, and the rerun searched first.
    * Lesson (Agent_Harness.md): the screen is a pattern list that paraphrases pass, so it discloses rather than filters; a poisoned passage inside the store read by a real model is still untested. CLAUDE.md asks that any new path to a prompt or trade execution that skips the screening be flagged.
    * Pointers: `src/main/java/documentation/Agent_Harness.md` (Prompt Injection Posture; Prompt-injection regression and filings prefetch); `src/main/java/documentation/live-runs/2026-09-12-injection/run-before-prefetch.log`; commit 6c1b613; Follow_Ups SEC-4 and SEC-5.

* An endpoint found without the token gate
    * Challenge: gated endpoints need the integration access token.
    * What happened: during Milestone 3 of the retrieval recall plan, the UT Validator observed that `POST /api/rag/retrieve` is not token-gated, recorded as pre-existing and raised as a separate task.
    * Lesson: none recorded; the plan leaves it to the separate task.
    * Pointer: `src/main/java/documentation/plans/2026-09-14-retrieval-recall.md` (Status, Milestone 3 closed).

## Local environment and test isolation

* Environment variables the suite needs but does not load
    * Challenge: the DB-backed tests and local runs need the project `.env` exported by hand.
    * What happened: a missing `SEC_USER_AGENT` fails the Spring context loads with an unhelpful message; the application enable flags, if exported in the test shell, break the wiring tests that assert disabled defaults. After the first orchestrated feature these rules were written into CLAUDE.md.
    * Lesson (CLAUDE.md): export `.env` in the same shell first and never export the enable flags where tests run; a script or Maven profile is still open, as is remote CI.
    * Pointers: CLAUDE.md "Project specifics"; commit d55713a; Follow_Ups PLAT-6 and PLAT-1.

* One shared database and one `target/` directory
    * Challenge: DB-backed tests run against the shared local Postgres, and both validators use Maven in the same `target/` directory.
    * What happened: CLAUDE.md requires "latest" assertions to be robust to pre-existing rows and validators to run one at a time. In the evidence plan, a Milestone 2 test asserted that every set-v2 phrase is held by a stored chunk, which would make `verify` fail on a freshly migrated database; Amendment 2 made it opt-in or limited to rows the test inserts.
    * Lesson (evidence plan Amendment 2): `verify` must not need ingested filings.
    * Pointers: CLAUDE.md "Project specifics"; `src/main/java/documentation/plans/2026-09-13-evaluation-evidence.md` (Amendment 2).

* Parallel worktrees sharing one CPU
    * Challenge: rerank timeouts are part of a run's settings, so runs from parallel worktrees can interfere through CPU load.
    * What happened: Amendment 5 of the evidence plan split Milestone 4 so its measurement ran in a separate worktree and required each traced run to wait until no other test JVM or application was running. In the recall plan's Milestone 2, the first attempt (P1) was stopped by hand with no snapshot, since the CPU wait matched the run's own processes; the filter was changed before run a.
    * Lesson: the run log records the fix (the filter now excludes the run's own processes) and states no further lesson.
    * Pointers: `src/main/java/documentation/plans/2026-09-13-evaluation-evidence.md` (Amendment 5); `src/main/java/documentation/live-runs/2026-09-14-recall-one-factor/run.log` (Aborted attempt P1).

* Native code and network boundaries in the local cross-encoder
    * Challenge: the reranker runs a local ONNX cross-encoder through a native tokenizer.
    * What happened: Milestone 2 of the reranker plan needed two remediation rounds: first a long query made the native tokenizer panic and abort the whole JVM before any fallback could run, and a telemetry call reached a metadata endpoint outside the approved network boundary; then native pair truncation grew memory until the OS killed the process. Tokenization moved to single sequences with pair assembly in Java, and inference calls were bounded. The memory documentation needed three further correction commits.
    * Lesson (reranker plan Amendment 2): no automatic default flip, since startup fails without the gitignored model files; only runs without fallbacks count, with one warm-up retrieval after each start.
    * Pointers: `src/main/java/documentation/plans/2026-09-13-reranker.md` (Amendment 2); commits 9085d0c, eaeed7a, d06d480, 8316c0e, 6cbbcba; CLAUDE.md "Loop history" (cross-encoder reranker).

* Uncommitted edits in a shared working tree
    * Challenge: a Worker may start while someone else's uncommitted edits are in the tree.
    * What happened: before the reranker measurement, the tree held uncommitted edits to the PRD and Follow_Ups and an untracked design document; the plan told the Worker not to edit, stage, or revert them.
    * Lesson (reranker plan Amendment 2): the Worker neither edits, stages, nor reverts them; CLAUDE.md separately requires staging specific files, never `git add -A` or `git add .`.
    * Pointers: `src/main/java/documentation/plans/2026-09-13-reranker.md` (Amendment 2); CLAUDE.md "Project specifics".

## Retrieval quality

* Exact figures and defined terms that embeddings miss
    * Challenge: filing questions about table rows and exact terms retrieved poorly by embedding alone (Follow_Ups RAG-2).
    * What happened: hybrid keyword plus vector retrieval was measured against the vector-only run and turned on by default; fusion tuning then weighted the keyword leg and added a figure leg. Set v1 carried no figure tokens, so the figure leg was evidenced by live queries only until set v2 re-measured it.
    * Lesson (Follow_Ups RAG-12): a ticker-level decision rule hid question-level regressions, so later selection rules also check per-question and per-slice movement.
    * Pointers: Follow_Ups RAG-2 and RAG-12 (snapshots 34, 35, 51, 69, 71 to 75); `src/main/java/documentation/plans/2026-09-12-hybrid-keyword-retrieval.md`; `src/main/java/documentation/plans/2026-09-12-fusion-tuning.md`; commits b24b582 and 90c2acd.

* A reranker that no configuration qualified
    * Challenge: remaining misses were ranking problems a cross-encoder targets.
    * What happened: head-only scoring was measured and no configuration met the selection rule; a truncation probe recorded in the windows plan then compared chunk lengths with the model's window, windowed scoring was built and measured, and again no row qualified, so reranking stays off by default.
    * Lesson (reranker plan Amendment 2): a configuration that met the rule would be recorded for Jay's decision with its properties, not enabled by the Worker; Plan A of the recall plan keeps the same constraint.
    * Pointers: Follow_Ups RAG-1 (snapshots 248 to 250 and 296 to 299); `src/main/java/documentation/plans/2026-09-13-reranker.md`; `src/main/java/documentation/plans/2026-09-13-reranker-windows.md`; commits ab915f8 and 3aeaa87.

* Answers beyond any reranker depth measured
    * Challenge: nvda-02 and nvda-04 miss the window in every configuration measured.
    * What happened: traced runs recorded their accepted chunks' fused positions beyond every rerank input list measured, and the E1 experiment at a larger candidate count ran without the cross-encoder loaded while its comparison had it loaded, so the pair differed in more than one property. The recall plan's Milestone 2 reran it as a one-factor experiment. Hypotheses about the vector ranks stay hypotheses until isolated.
    * Lesson (Follow_Ups RAG-15): state no cause for a vector rank without an isolating experiment.
    * Pointers: Follow_Ups RAG-15 (claims C-273 to C-284; snapshots 598, 694); `src/main/java/documentation/live-runs/2026-09-13-rag15-recall/run.log`; `src/main/java/documentation/plans/2026-09-14-retrieval-recall.md` (Milestone 2).

* Parser and normalisation defects found while building the set
    * Challenge: expected passages are keyed by section and matched by phrase.
    * What happened: authoring the evaluation set surfaced NVDA financial statements stored under Item 15, inconsistent 8-K item keys across filers, a combined-heading title glitch with Part II items under Part I keys, and whitespace normalisation that does not treat a non-breaking space as a space. The set encodes the current keys and the items stay open.
    * Lesson (Follow_Ups preamble): items that need a decision or later work are logged with stable ids and a status instead of being fixed in passing.
    * Pointers: Follow_Ups RAG-7, RAG-8, RAG-9, RAG-10.

## Evaluation honesty and the claims check

* Expectations narrower or wider than the question
    * Challenge: some v1 misses looked like expectation narrowness rather than retrieval failure.
    * What happened: Milestone 1 of evaluation set v2 failed Scrutiny because three alternative expectations answered only part of their question and the plan's own premise about nvda-07 was wrong; Amendment 1 corrected the premise and narrowed the contract instead of relaxing the check. Milestone 2 failed once because the docs let a rising floor read as tighter protection.
    * Lesson (CLAUDE.md, RAG-11): Scrutiny reads full chunk content for every alternative expectation, and a headline metric on a new set is decomposed by question slice.
    * Pointers: `src/main/java/documentation/plans/2026-09-13-evaluation-set-v2.md` (Amendment 1); commits d6bc2f9 and ebd9597; Follow_Ups RAG-11; CLAUDE.md "Loop history" (evaluation set v2).

* A guard whose failure message named the wrong questions
    * Challenge: the non-figure regression floor must identify what breaks it.
    * What happened: Scrutiny failed the milestone once because the failure message named only questions with no match in the window, omitting those ranked below the top 5 but still in the window, so a real breach would have pointed at the wrong questions.
    * Lesson (CLAUDE.md, RAG-13): a guard's failure output names every item that makes the guarded metric fall short, and Scrutiny constructs the regression scenario to check it.
    * Pointers: commit 2c6966b; `src/main/java/documentation/plans/2026-09-13-non-figure-floor.md`; Follow_Ups RAG-13; CLAUDE.md "Loop history" (non-figure regression floor).

* A write-up frozen unvalidated at merge
    * Challenge: the windowed reranker write-up had to explain per-question outcomes.
    * What happened: the measurement and decision were confirmed in every validation round, but the write-up failed Scrutiny after the first attempt, after both remediation rounds, and after two Orchestrator corrections at the cap; Jay chose to freeze it and merge. One wrong assumption from the Orchestrator's own diagnosis spread into the plan, RAG.md, the run log, and the change log, and rank claims summarised from memory were wrong.
    * Lesson (Jay, CLAUDE.md): conclusions must not outrun recorded measurements; compute per-question facts with a script, label each observed, derived, inferred, or unknown, allow a cause only with an isolating experiment, and state a fact once and reference it.
    * Pointers: `src/main/java/documentation/plans/2026-09-13-reranker-windows.md` (Status at merge); commit c43a4dd; CLAUDE.md "Loop history" (cross-encoder reranker).

* Recorded evidence and a checker that proves only what it reads
    * Challenge: turn Jay's rule into a check in `verify` so that measurement sentences come from committed evidence.
    * What happened: the evidence plan added retrieval traces, a per-question evidence report, and a claims check. Its Milestone 3 failed Scrutiny three times, each time on text the checker did not read: a sentence beside a check, prose citing a claim outside a block, and a screened lead-in line promised as a paragraph. Amendment 4 redesigned it so observed and derived sentences are rendered from their checks, and it closed at the cap by rewording. Milestone 4b passed UT every round but failed Scrutiny on hand-written history notes that kept restating results, and closed at the cap after that history was collapsed into pointers.
    * Lesson (CLAUDE.md, RAG-14): a checker's documentation must list what it does not read; keep correction history in commits and run logs and leave a pointer; count a generated list's completeness from the evidence; the Orchestrator's own plan text and cap fixes need the same validation.
    * Pointers: `src/main/java/documentation/plans/2026-09-13-evaluation-evidence.md` (Amendments 4, 6, 7); commits e44f4c1, e11b9b6, 95a3f63, bc29a48; Follow_Ups RAG-14 and RAG-17; `src/main/java/documentation/RAG.md` (Claims).

* A prescribed id format the checker would have refused
    * Challenge: plan text is not itself run through `verify`.
    * What happened: Amendment 7 of the evidence plan told Milestone 4b to use a `W-` claim-id prefix, which the id format refuses, so `verify` would have failed; it was corrected before the Worker used it.
    * Lesson (CLAUDE.md): the Orchestrator's own plan text needs the same validation as the Worker's output.
    * Pointers: commit b1e6fcf; `src/main/java/documentation/plans/2026-09-13-evaluation-evidence.md` (Amendment 7).

## The multi-agent loop

* First features through the loop
    * Challenge: replace one agent reviewing its own code with an Orchestrator, a fresh Worker per milestone, and two independent validators.
    * What happened: the retrieval evaluation set, hybrid retrieval, and fusion tuning each passed Scrutiny and UT on the first round for every milestone.
    * Lesson: none recorded for these features beyond the process itself, which CLAUDE.md describes.
    * Pointers: CLAUDE.md "Loop history" (2026-09-12 entries); `src/main/java/documentation/plans/2026-09-12-retrieval-evaluation-set.md`; `src/main/java/documentation/plans/2026-09-12-hybrid-keyword-retrieval.md`; `src/main/java/documentation/plans/2026-09-12-fusion-tuning.md`.

* The remediation cap and decisions escalated to Jay
    * Challenge: plans allow at most two remediation rounds per milestone, then escalate.
    * What happened: the cap was reached on reranker Milestone 2 (documentation correction applied by the Orchestrator at Jay's choice), on the windowed write-up (frozen), on evidence Milestones 3 and 4b (closed with Orchestrator wording fixes Jay approved), and on the recall plan's Milestone 2 low-findings fixes (collapsed into one pointer at Jay's approval).
    * Lesson: none stated as a rule; the records show that each cap ended with a choice by Jay (Orchestrator correction, freeze with the open findings listed, rewording, or collapse into a pointer).
    * Pointers: `src/main/java/documentation/plans/2026-09-13-reranker.md` (Amendment 2); `src/main/java/documentation/plans/2026-09-13-reranker-windows.md` (Status at merge); `src/main/java/documentation/plans/2026-09-13-evaluation-evidence.md` (Amendment 7); `src/main/java/documentation/plans/2026-09-14-retrieval-recall.md` (Status, Milestone 2 closed); commit 48db9cd.

* Correction notes that drift
    * Challenge: dated correction notes were meant to keep documentation history honest.
    * What happened: correction notes that described corrected results restated them, so each rewording created another copy to validate; two earlier correction notes rewritten in place instead of marked superseded were themselves listed as open findings (reranker-windows plan, Status at merge); CLAUDE.md's own evidence-rule bullet carries a chain of dated corrections.
    * Lesson (CLAUDE.md, RAG-14): keep correction history in commits and run logs and leave a pointer; this document follows that rule by referring to results instead of restating them.
    * Pointers: CLAUDE.md "Loop history" (recorded retrieval evidence and checked claims) and "Project specifics" (evidence rule); commits 08b7f60, bc29a48, 4fbd2bc.

## This plan: retrieval recall (RAG-15, RAG-18)

* Separating "did retrieval find it" from "did ranking put it high"
    * Challenge: an external review proposed several ranking changes; checked against committed evidence, the plan chose to measure candidate recall and a one-factor recall experiment, and to record what diversification removes, before any ranking change.
    * What happened: Milestone 1 (candidate recall from committed traces) passed Scrutiny and UT on the first round; amendment 1 added a fifth class for reranked questions outside hit@5, and reworded a Design constraints line the claims check read as a citation outside a block. Its low findings were fixed and passed a scoped Scrutiny check.
    * Lesson: none stated beyond amendment 1 itself.
    * Pointers: `src/main/java/documentation/plans/2026-09-14-retrieval-recall.md` (Why; Status, amendment 1 and Milestone 1 closed); commits 86a88d9, abe5ec5, ef61439; Follow_Ups RAG-18; `src/main/java/documentation/RAG.md` (Retrieval Evaluation, Candidate recall block).

* The one-factor rerun and a rerank fallback
    * Challenge: repeat E1 so each compared pair differs in one recorded property.
    * What happened: the evaluate endpoint takes only three per-call overrides, so amendment 2 gave each run its own application start. Run (c) recorded a rerank fallback and stays committed as the fallback run with no selection outcome; its one allowed repeat recorded none. Scrutiny failed round 1 on a rule row judged against a reranked configuration; amendment 3 fixed the reference to the default snapshot 598, and remediation passed. The repeat does not meet the selection rule against 598, so defaults stay. Low-findings wording fixes then failed scoped Scrutiny twice on change-log pointers, and at the cap Jay approved collapsing those bullets into one pointer.
    * Lesson (amendments 2 and 3): a selection outcome is judged only against the default reference, and a run with a fallback stays committed and labelled.
    * Pointers: `src/main/java/documentation/plans/2026-09-14-retrieval-recall.md` (Status, amendments 2 and 3, Milestone 2 closed); snapshots 931, 932, 933, 947; claim C-657 in `src/main/java/documentation/RAG.md` (One-factor recall experiment block); commits cd539f9, 32b100d, f98002f, 48db9cd; `src/main/java/documentation/live-runs/2026-09-14-recall-one-factor/run.log`; Follow_Ups RAG-15.

* Diversification removals and the move decision
    * Challenge: traces did not list what diversification removed, so it could not be ruled in or out for a recorded miss.
    * What happened: Milestone 3 recorded removals in the trace and passed Scrutiny and UT on the first round; at 598's settings no accepted chunk was removed (claim C-701), so under the milestone's rule the order stays and no amendment follows. Low findings left open: chunk-id-only lookups would misreport a repeated vector chunk, and the evidence `removed` source text names the empty case for every question.
    * Lesson (Follow_Ups RAG-15): the result records a count, not a cause of any miss.
    * Pointers: `src/main/java/documentation/plans/2026-09-14-retrieval-recall.md` (Status, Milestone 3 closed); commits dd7c9b5, 089e20e; `src/main/java/documentation/live-runs/2026-09-14-diversification-trace/run.log`; `src/main/java/documentation/RAG.md` (Diversification removals block).

* What remains
    * Challenge: Plan B (raw-score fusion, rerank depth, graded figure features, neighbour expansion and section-first retrieval) depends on Plan A's evidence.
    * What happened: its contracts are to be written and approved after Plan A closes; the vector-rank hypotheses remain untested.
    * Pointers: `src/main/java/documentation/plans/2026-09-14-retrieval-recall.md` (Plan B); Follow_Ups RAG-15.

* Change log — 2026-09-14: document created (plan `plans/2026-09-14-retrieval-recall.md`, Milestone 4) from the project's recorded history; no new analysis or measurement.
