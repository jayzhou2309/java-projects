# Follow-Ups

Open items that need a person, a market session, accumulated data, or a decision before they can be closed, grouped by
component. IDs are stable (category prefix plus number) so docs and commits can cite them. Status: OPEN, BLOCKED (on
what), DECISION (needs Jay's call), or DONE (date, evidence). When a feature lands, add its follow-ups here and mark
closed items instead of deleting them.

## Data collection and evaluation (Outcomes.md)

| ID | Item | Why it matters | What unblocks it | Status |
|---|---|---|---|---|
| DATA-1 | Run broker-enabled recommendations during US regular hours (21:30–04:00 SGT) with directional questions, on several tickers, for several weeks | Every stored run so far is NEUTRAL or brokerless, so there are no scored directional outcomes; calibration needs 30 of them at 20 trading days (outcomes.calibration.min-samples), the track record has no hit rates, and the critic's effect cannot be measured | The watchlist scheduler (2026-09-12, Agent_Harness.md) fires directional runs at 10:45 New York time on weekdays once WATCHLIST_ENABLED=true; what remains is keeping the application, TWS (logged in), IBKR_ENABLED, QUANT_ENABLED, and OUTCOMES_ENABLED up over those hours from Monday 2026-09-14 | OPEN (operations only) |
| DATA-2 | Verify the outcome benchmark contract (default conid 756733, expected SPY on ARCA) against TWS discovery and store SPY bars | Excess returns are null until benchmark bars exist; a wrong conid would score every run against the wrong index | GET /api/broker/instruments?symbol=SPY with TWS up; set OUTCOMES_BENCHMARK_CONID if it differs | OPEN |
| DATA-3 | End-to-end pipeline test on real data: recommendation with broker on → nightly outcome evaluation → calibration snapshot READY → calibrated confidence APPLIED on the next run | The pipeline is verified only with scripted tests and a clearly labelled synthetic dataset that was deleted afterwards; real numbers have never flowed through it | DATA-1, then roughly a month of trading days | BLOCKED on DATA-1 |
| DATA-4 | Segment calibration by assessment (BULLISH vs BEARISH) and by prompt/quant version | One pooled curve hides a systematically worse direction or a regression after a prompt change; snapshots already record per-segment counts | Segment counts above min-samples in GET /api/outcomes/calibration | BLOCKED on data |
| DATA-5 | Out-of-sample check of the calibration (time split or leave-one-out) and a calibrated-vs-raw Brier comparison | Stored ECE and Brier are in-sample; a curve judged on the runs it was fitted to is optimistic | About 100 scored directional runs | BLOCKED on data |
| DATA-6 | Score NEUTRAL runs against something | NEUTRAL is never scored for direction, so most current runs contribute nothing to hit rates or calibration | Decide a flat-return band (for example |20-day return| below one ATR counts as correct) or leave NEUTRAL unscored and rely on AGENT-4 | DECISION |
| DATA-7 | Corporate-action adjustment of stored bars | TWS bars are unadjusted; a split inside a horizon window would score as a huge move and a false level touch | An adjusted-bar source or a split table applied before scoring | OPEN |
| DATA-8 | Human review gate before any automatic prompt or parameter tuning from outcomes | The PRD requires human review before the self-learning loop adjusts anything; nothing tunes automatically yet, so the gate must exist before the first such job | Design the review step when the first tuning job is proposed | OPEN |

## Agents: manager, specialists, critic (Agent_Harness.md)

| ID | Item | Why it matters | What unblocks it | Status |
|---|---|---|---|---|
| AGENT-1 | Second critic on a different model or provider | The critic shares the manager's model, so a blind spot common to both is never caught; a second model makes the review an ensemble rather than a self-check | A second tool-capable provider key; a `recommendation.critic-model` (and provider) property; decide how two verdicts combine (any REVISE wins is the simplest) | OPEN |
| AGENT-2 | Measure the critic against outcomes: do runs it sent back do better after revision, and do its REVISE verdicts predict wrong direction? | critique.reviews is stored per run for exactly this; nothing reads it yet | DATA-1 | BLOCKED on DATA-1 |
| AGENT-3 | Versioned retrieval and answer evaluation set (filing questions with expected passages and expected assessments) | Prompt and retrieval changes so far were judged by single live runs; the harness doc lists this as a prerequisite for further prompt changes | An afternoon of question writing against the stored AAPL, MSFT, and NVDA filings; a test that runs the set with a scripted or live model and reports citation precision | OPEN |
| AGENT-4 | Direction policy: risk questions reliably produce NEUTRAL | If most runs stay NEUTRAL, DATA-1, DATA-3, DATA-4, DATA-5, and AGENT-2 starve regardless of broker uptime | Scheduled runs now ask a directional question by construction (recommendation.schedule.question), which covers the data need without a prompt change; still open is whether manual risk questions should also be pushed toward a direction | DECISION (narrowed) |
| AGENT-5 | Provider fallback for the chat model | The PRD lists LLM provider fallback under reliability; today one OpenAI outage fails every run | A second Spring AI model bean and a retry-on-alternate policy inside the harness, keeping one execution owner | OPEN |
| AGENT-6 | Cross-request conversational memory (PRD phase 8) | Every request starts a new run; a follow-up question cannot refer to a prior run | Decide the memory boundary (per run ID, per user) before AGENT-8 multi-user work | OPEN |
| AGENT-7 | MCP tool boundary | A future MCP server must pass the same timeout, payload, argument, permission, and provenance checks as the built-in callbacks | Select a server, configure client and authentication, map approved tools into the allowlist explicitly | OPEN |
| AGENT-8 | Prompt regression check on PROMPT_VERSION bumps | Each prompt change is a new version tag, but nothing compares the new version's outcomes or citation precision to the old | AGENT-3 for offline checks; DATA-4 for outcome-based checks | BLOCKED on AGENT-3 |
| AGENT-9 | Provider token-per-minute allowance: the OpenAI organisation is limited to 30,000 TPM for gpt-4.1, and one run with a critic revision uses up to about 30,000 tokens | Back-to-back runs (the watchlist, or a manual request right after a scheduled one) hit HTTP 429; found live on 2026-09-12 when the second watchlist ticker's critic call was rejected. The single retry, the 60-second watchlist pause, and the lean profile (about 7,000 tokens per run instead of 15,000 to 29,000) work around it; they do not remove the ceiling for default-configuration runs, and a third concurrent caller would still fail | Request a rate-limit increase from OpenAI, or route the critic to a second model/provider (AGENT-1); find the passage length and count that keep INSUFFICIENT_EVIDENCE rare (the lean run lost AAPL's direction) once AGENT-3's evaluation set exists | OPEN |

## Filings and retrieval (RAG.md)

| ID | Item | Why it matters | What unblocks it | Status |
|---|---|---|---|---|
| RAG-1 | Model-based reranker behind the FilingReranker boundary | Retrieval is filtered vector search with diversity filtering; the extension point exists but no implementation, and the RAG specialist is told not to claim one | Provider or local model selection and a benchmark on AGENT-3's evaluation set | BLOCKED on AGENT-3 |
| RAG-2 | Hybrid keyword plus vector retrieval | Exact figures and defined terms (segment names, line items) are where pure embeddings miss; the directional live runs asked for exact numbers | Postgres full-text index on chunk content and a fused ranking | OPEN |
| RAG-3 | Track amended filings (10-K/A, 10-Q/A) | The refresh compares original forms only; an amendment that restates figures is never ingested | Extend the SEC index comparison to /A forms and decide whether they replace or sit beside the original | OPEN |
| RAG-4 | HTTP cache for the SEC ticker map and submissions JSON | Every index comparison re-reads both through SECClient; the nightly refresh over many tickers will hit SEC rate limits | Conditional requests or a short TTL cache in SECClient | OPEN |
| RAG-5 | Contextual deduplication of overlapping chunks and unlinked contents tables | Overlapping chunks can appear together in one result; contents layouts without Item links are still chunked | Parser and retrieval changes measured against AGENT-3 | BLOCKED on AGENT-3 |
| RAG-6 | Filing coverage beyond 10-K, 10-Q, and three 8-Ks | Proxy statements and older 8-Ks are absent; the cadence limits are configuration | Decide the retention and coverage policy per form type | DECISION |

## Broker and market data (IBKR.md)

| ID | Item | Why it matters | What unblocks it | Status |
|---|---|---|---|---|
| BROKER-1 | A COMPLETE-status run during regular hours with a realtime quote | Every live run so far is PARTIAL; the realtime freshness gate has only been exercised by scripted tests | TWS up during 21:30–04:00 SGT and a realtime data entitlement, or accept delayed data as the operating mode and document it | OPEN |
| BROKER-2 | Market-data entitlement decision | Delayed quotes arrive only on primary-exchange requests and never satisfy COMPLETE; a paid entitlement changes both | Decide whether to subscribe for the paper account | DECISION |
| BROKER-3 | IBKR pacing-limit protection for historical requests | Only the bar cache stands between the nightly outcome pass and pacing violations once many contracts are evaluated | A request budget or spacing in TwsClient, and a test | OPEN |
| BROKER-4 | Session health and automatic reconnect | A stale TWS session fails silently until the next request; live runs earlier showed SESSION_NOT_READY streaks | A heartbeat with reconnect and a health endpoint | OPEN |
| BROKER-5 | Second broker adapter behind BrokerReadService | The PRD names Alpaca and CCXT; one adapter means one point of failure and no comparison of quotes | Pick the second adapter and a paper account | OPEN |

## Quant layer (Quant.md)

| ID | Item | Why it matters | What unblocks it | Status |
|---|---|---|---|---|
| QUANT-1 | Validate the ATR multiples against outcomes | Take-profit 2×ATR and stop-loss 1×ATR are a transparent baseline, not a tested strategy; first-touch statistics per horizon will show whether the stop is hit far more often than the target | DATA-1 and the outcomes summary | BLOCKED on DATA-1 |
| QUANT-2 | Levels from the current quote rather than the last close | Levels are computed from the last daily close, so intraday moves after the close are not reflected | Decide whether a realtime quote should re-anchor levels, and how that interacts with BROKER-1 | DECISION |
| QUANT-3 | Backtest harness over stored bars | The PRD's confidence terms include backtest accuracy; none exists | A deterministic replay of the level rules over stored history per contract, reported like outcomes | OPEN |
| QUANT-4 | Position sizing | POSITION_SIZING_NOT_IMPLEMENTED is on every response; sizing belongs with the guard pipeline in execution mode | EXEC-1 | BLOCKED on EXEC-1 |
| QUANT-5 | Multi-listing bar series | Bars are keyed by conid, so a ticker with several listings has separate series; preferred-currency selection makes this rare but not impossible | Decide whether foreign listings are ever analysed | DECISION |

## Platform and operations

| ID | Item | Why it matters | What unblocks it | Status |
|---|---|---|---|---|
| PLAT-1 | Remote CI running the full suite against disposable PostgreSQL with pgvector | Phase 1 says "remote CI pending"; the suite needs a database and the .env variables, so today it runs only on Jay's machine | A GitHub Actions workflow with a postgres service, the env variables as secrets, and the five live tests kept opt-in | OPEN |
| PLAT-2 | Durable traces | Tool traces are returned and logged, not stored beyond the response JSON; debugging a run after a restart means reading the audit row | A traces table or structured log shipping, decided with PLAT-4 | OPEN |
| PLAT-3 | Latency against the PRD target (p95 under 5 seconds) | Live runs take 6 to 15 seconds, more with a revision; the critic added one to three model calls | Decide whether the target still stands for a multi-agent research run, then profile: parallel critic prefetch, smaller passages to the critic, streaming | DECISION |
| PLAT-4 | Observability: Micrometer metrics for model calls, tokens, tool outcomes, and run status | The PRD lists Prometheus and Grafana; nothing is exported | Add Micrometer counters and timers at the trace points | OPEN |
| PLAT-5 | Kafka, Redis, TimescaleDB per the PRD architecture | The implementation uses Postgres and schedulers throughout; the PRD's event bus, feature store, and time-series store are absent | Decide whether they are needed before multi-user scale or remain aspirational | DECISION |
| PLAT-6 | Environment loading for the test suite and local runs | The DB-backed tests need the .env variables exported by hand; a missing SEC_USER_AGENT fails 13 context loads with an unhelpful message | A Maven profile or a small script that exports .env, and a clearer startup message | OPEN |
| PLAT-7 | Retention policy for the audit tables and live-run evidence | recommendations, outcomes, and snapshots grow without bound, and live-run JSON is committed to the repo | Decide retention windows and whether evidence moves out of src | DECISION |

## Security and multi-user

| ID | Item | Why it matters | What unblocks it | Status |
|---|---|---|---|---|
| SEC-1 | User identities, account bindings, per-user quotas | Everything is single-owner behind one integration token; the PRD's per-user mode and per-user risk profile need identities first | Decide the auth provider before any multi-user endpoint | OPEN |
| SEC-2 | Broker credentials sealed at rest | TWS credentials live in the TWS login, not the app, today; execution mode and a second adapter will need stored credentials | EXEC-1 or BROKER-5, whichever comes first | BLOCKED |
| SEC-3 | Portfolio data leaving the machine | includePortfolio sends positions to the model provider; the opt-in is per request but there is no redaction or provider allowlist | Decide whether positions are ever sent, or only derived exposure | DECISION |
| SEC-4 | Prompt-injection regression tests with adversarial filing text | The prompts label filings as untrusted; no test injects instructions into a passage and asserts they are ignored | A fixture passage with embedded instructions in RecommendationServiceTests (scripted model) and one live check | OPEN |

## Execution mode (PRD v2 scope)

| ID | Item | Why it matters | What unblocks it | Status |
|---|---|---|---|---|
| EXEC-1 | Trade staging: proposal files in a per-user git workspace, approval as commit | The first execution-mode step; nothing exists | SEC-1 and a decision on per-user repos versus per-user branches (PRD open question) | BLOCKED on SEC-1 |
| EXEC-2 | Guard pipeline: position size, cooldown, whitelist, exposure limits | Deterministic non-LLM gate required before any broker order | EXEC-1 | BLOCKED |
| EXEC-3 | Unified Trading Account and order routing, paper first | The broker boundary is read-only by design; order methods are deliberately absent from the transport | EXEC-2 and a compliance decision (PRD open question) | BLOCKED |
| EXEC-4 | Reconciliation job between Postgres and git trails | The PRD names drift between the two audit trails as a risk | EXEC-1 | BLOCKED |

## Delivery

| ID | Item | Why it matters | What unblocks it | Status |
|---|---|---|---|---|
| DELIV-1 | Push and merge `critic-synthesis`, then `confidence-calibration` (stacked) | Both phases were committed locally only | Merged as [PR #6](https://github.com/jayzhou2309/java-projects/pull/6) and [PR #7](https://github.com/jayzhou2309/java-projects/pull/7) | DONE (2026-09-12) |
| DELIV-2 | Update the PRD phase table and this log at the end of each phase | The table is the only place the overall status is summarised | Habit; the memory note records it | OPEN |
