# PRD: AI-Powered Trading Decision & Execution Platform (v3 — Consolidated)

## 1. Overview
An AI-powered stock recommendation engine that generates explainable BUY/SELL/HOLD calls with confidence scores and algorithmic TP/SL levels, grounded in SEC filings (RAG) and quantitative market data. The system supports two operating modes:

- **Recommend-only** (original scope): pure decision support, no execution, no live trading.
- **Recommend + execute** (v2 addition): the same recommendations can optionally be staged, human-approved, and routed to a real broker, with every step version-controlled and reviewable.

Mode is selectable per user/account, and execution mode is opt-in — the recommendation core is unchanged either way.

## 2. Goals
- Ground every recommendation in actual filings and market data, with a non-self-reported, composite confidence score.
- Never let the LLM freehand numeric outputs — TP/SL and confidence come from deterministic tool calls, not model guesses.
- Keep the system auditable end-to-end: every recommendation → decision → (optional) execution step must be traceable.
- Support optional, human-approved trade execution across multiple brokers without becoming an autonomous trading bot.
- Scale on a proven enterprise backbone (Kafka, Postgres, Redis, TimescaleDB) while adopting a lightweight, git-based staging/approval UX for the human review step.
- Feed real outcomes (recommendation accuracy AND execution fidelity) back into a supervised tuning loop.

## 3. Non-Goals
- Fully autonomous trading with no human approval step (the guard pipeline + approval gate are mandatory whenever execution mode is on).
- High-frequency trading (sub-second latency).
- Continuous/online retraining of the LLM itself.
- Replacing brokers — the system routes to brokers, it does not become one.

## 4. System Architecture

### 4.1 High-Level Flow
```
Market Data Feed → Kafka → Quant Engine → Feature Store (Redis)
                                              ↓
Filing Ingestion → RAG Pipeline → Vector Store (PGVector)
                                              ↓
User Request → Spring AI Orchestrator → [Retrieval + Quant Tools] → LLM → Structured Recommendation
                                              ↓
                    Recommendation written to Postgres (system-of-record audit)
                                              ↓
                         [Recommend-only mode] → END: shown to user
                         [Execute mode, opt-in] → staged as a file-based "trade
                         proposal" in a per-user git workspace
                                              ↓
                        User reviews diff → approves (git commit) → Guard Pipeline
                        (position size / cooldown / symbol whitelist checks)
                                              ↓
                              Unified Trading Account (UTA) → Broker Adapter
                              (CCXT / Alpaca / Interactive Brokers) → Order Execution
                                              ↓
                                    Outcome Tracker → Batch Evaluation → Prompt/Weight Tuning
```

### 4.2 Core Components

| Component | Technology | Purpose | Mode |
|---|---|---|---|
| API/Orchestration | Spring Boot, Spring AI (ChatClient, Advisors, Function Calling) | Request handling, prompt orchestration, tool calling | Both |
| LLM Provider | Claude / GPT-4-class via Spring AI model abstraction | Reasoning, synthesis, explanation generation | Both |
| Vector Store | PGVector (start) → Qdrant/Milvus (scale) | Filing embeddings for RAG | Both |
| Relational DB | PostgreSQL | Recommendations, audit logs, outcomes, user data | Both |
| Time-Series DB | TimescaleDB | Price history, backtesting data | Both |
| Cache/Feature Store | Redis | Hot quant features, session state, rate limiting | Both |
| Streaming/Event Bus | Apache Kafka | Market data ingestion, filing/recommendation/outcome events | Both |
| Batch Processing | Spring Batch | Nightly ingestion, backtesting, evaluation jobs | Both |
| Document Parsing | Apache Tika / PDFBox | Parsing SEC filings | Both |
| Embeddings | `text-embedding-3-large` or finance-tuned model | Vectorizing filing chunks | Both |
| Market Data | Polygon.io / Alpha Vantage / IEX Cloud | Price, volume, technicals | Both |
| Filings Source | SEC EDGAR API | 10-K, 10-Q, 8-K ingestion | Both |
| Observability | Micrometer + Prometheus + Grafana | Latency, LLM token usage, error rates | Both |
| Containerization | Docker + Kubernetes | Deployment, scaling | Both |
| **Trade Staging Layer** | Per-user git repo, file-based proposals | Human-reviewable diff of every proposed trade before approval | Execute only |
| **Unified Trading Account (UTA)** | Broker abstraction over CCXT / Alpaca / Interactive Brokers | Single interface so the LLM/system never talks to a broker directly | Execute only |
| **Guard Pipeline** | Rule engine (max position size, cooldown, symbol whitelist) | Deterministic, non-LLM pre-execution safety checks | Execute only |

## 5. Functional Requirements

### 5.1 Recommendation Generation (core — both modes)
- Input: ticker symbol, optional user risk profile.
- Output (structured JSON): `recommendation`, `confidence`, `take_profit`, `stop_loss`, `reasoning`, `sources`.
- Confidence is composite and non-self-reported: ensemble agreement, retrieval quality, backtest accuracy, data recency.
- TP/SL computed via quant tool calls, never freehand LLM numbers.

### 5.2 RAG Pipeline
- Section-aware chunking of filings (MD&A, Risk Factors, Financials).
- Kafka-driven re-ingestion keeps the vector store current.

### 5.3 Trade Staging & Approval (execute mode only)
- Every recommendation the user opts to act on is written as a proposal file (ticker, side, size, TP/SL, rationale, source citations) in that user's git workspace.
- User reviews the proposal as a diff — same posture as reviewing a pull request.
- Approval = git commit; rejection = discard, logged with optional reason.
- Approved proposals pass to the Guard Pipeline before reaching the UTA.

### 5.4 Guard Pipeline (execute mode only)
- Deterministic checks: max position size, per-symbol cooldown, symbol whitelist/blacklist, account-level exposure limits.
- Runs after human approval, before broker submission — a second, non-LLM gate.
- A failed check blocks execution and surfaces the specific rule violated.

### 5.5 Execution (execute mode only)
- UTA routes approved, guard-passed orders to the appropriate broker adapter (CCXT exchange, Alpaca, or IBKR).
- Opt-in per account; accounts can remain recommend-only indefinitely.
- Start with paper/demo/testnet accounts until execution reliability is proven.

### 5.6 Feedback Loop (both modes, extended)
- Batch evaluation tunes prompts/weights against real outcomes, gated by human review before auto-adjustment.
- In execute mode, the outcome tracker also logs execution-layer events (fills, slippage, guard-pipeline rejections), so the loop evaluates execution fidelity as well as recommendation quality.

## 6. Non-Functional Requirements
- **Latency**: p95 recommendation generation < 5s. Execution path latency is a separate, looser budget since it's approval-gated and not time-critical.
- **Throughput**: async Spring WebFlux + Kafka consumer scaling.
- **Auditability**: Postgres is the compliance system-of-record in both modes; in execute mode, git history adds a per-user human-readable review trail. Every executed trade must be traceable through both.
- **Reliability**: LLM provider fallback; broker adapter fallback/retry for transient execution failures.
- **Security**: broker credentials sealed at rest, either centrally or locally per user's custody preference; execution mode requires explicit account-level opt-in.
- **Data freshness**: RAG store and market features stay current via Kafka-driven ingestion; no stated hard SLA beyond that.

## 7. Risks
- LLM hallucination on numeric reasoning → mitigated by mandatory tool calls, and in execute mode by the added Guard Pipeline.
- Vector store staleness → mitigated by Kafka-driven re-ingestion.
- Overfitting the self-learning loop → human review threshold before auto-adjustment.
- Execution layer introduces real financial/operational risk → mitigated by opt-in execution mode, paper-trading-first rollout, guard pipeline, mandatory approval-as-commit step.
- Dual audit trails (Postgres + git, execute mode) could drift out of sync → needs a reconciliation job to guarantee agreement on final trade state.

## 8. Delivery Plan & Status
Implementation is phased; work may be delegated to sub-agents under skill files, with a parent process responsible for integration, legacy reconciliation, and CI.

| Phase | Scope | Status |
|---|---|---|
| 1 | Infrastructure (build, DB/extensions, core entities, security, migration/upgrade tests) | Implemented, locally validated; remote CI pending |
| 2 | Market data ingestion | Partial: TWS delayed quotes and stored daily bars ([IBKR.md](documentation/IBKR.md)); no Kafka/TimescaleDB |
| 3 | SEC RAG pipeline | Implemented: ingestion, hybrid keyword plus vector retrieval (PostgreSQL full-text and pgvector candidates fused by reciprocal rank, on by default since 2026-09-12: hit@5 0.633333 against 0.600000 vector-only on the 30-question set, snapshots 34 and 35; fusion tuned the same day to weights vector 1.0 / keyword 0.5 / figure 1.0, snapshot 51: hit@5 0.633333, MRR 0.463373, every FIGURE question either baseline had in the top 5 kept there), a local cross-encoder reranker with windowed passage scoring behind `FilingReranker`, off by default because no configuration met the selection rule on set v2 on 2026-09-13 (head-only snapshots 248 to 250, windowed snapshots 296 to 299; Follow_Ups RAG-1, RAG-14 to RAG-16), rebuild workflow ([RAG.md](documentation/RAG.md)); chunk size measured 2026-09-17 on a store rebuilt at 1,000 / 125 and rolled back after the R1 gate failed, defaults 4,000 / 500 unchanged (RAG.md, Retrieval Evaluation, Chunk size measurement; Follow_Ups RAG-26, RAG-27); a second size, 1,650 / 250, measured the same day with a candidate-pool grid (40 to 250) and reranking off and on, its held-out test FAIL in both reranker states, rolled back, defaults unchanged (RAG.md, Chunk size and candidate pool; Follow_Ups RAG-30) |
| 4 | ML baseline / confidence scoring | Partial: deterministic ATR levels, an input-coverage confidence ([Quant.md](documentation/Quant.md)), and histogram calibration of that confidence against realized 20-day direction hits, applied once 30 directional runs are scored ([Outcomes.md](documentation/Outcomes.md)); no scored directional runs exist yet, no backtest or ensemble terms |
| 5 | Specialist agents | Partial: RAG and broker specialists under a manager ([Agent_Harness.md](documentation/Agent_Harness.md)) |
| 6 | Orchestration | Partial: bounded explicit tool loop, no MCP or multi-user |
| 7 | Critic and synthesis | Partial: a tool-less critic reviews the manager's answer against the run's own evidence and sends it back for one bounded revision; a deterministic numeral check feeds the critic ([Agent_Harness.md](documentation/Agent_Harness.md)); no ensemble of models or calibration of the critic's verdicts |
| 8 | Memory | Partial: the manager is shown the ticker's prior stored runs and realized outcomes ([Agent_Harness.md](documentation/Agent_Harness.md)); no cross-request conversational memory |
| 9 | Evaluation platform | Partial: stored runs scored at 5/20/60-day horizons with benchmark and level touches, nightly calibration snapshots with ECE and Brier ([Outcomes.md](documentation/Outcomes.md)); versioned retrieval evaluation set (`POST /api/rag/evaluate`; set v1 of 30 filing questions, baseline snapshot 13 on 2026-09-12: hit@5 0.60, MRR 0.44; set v2 of 42 questions with figure-bearing questions, the default since 2026-09-13, baseline snapshot 69: hit@5 0.785714, MRR 0.655187, not comparable with v1) with an opt-in regression floor test (aggregate hit@5 floor 0.65, 28 of 42; since 2026-09-13 a second floor on the questions without a figure, non-figure hit@5 0.60, 18 of 30, from snapshot 91) ([RAG.md](documentation/RAG.md), Retrieval Evaluation); since 2026-09-14 per-question retrieval traces (`?trace=true`), a per-question evidence report (`GET /api/rag/evaluate/{id}/evidence`), and a claims check in `verify` that renders measurement write-ups from committed evidence (Follow_Ups RAG-14); candidate recall reported beside hit@5 and MRR, a one-factor candidate-count experiment, and diversification removals recorded in traces (RAG-18, RAG-15; plan `plans/2026-09-14-retrieval-recall.md`; defaults unchanged); a rerank-blend simulation recorded as no gain (RAG-21); a second reranker model (gte-reranker-modernbert-base) beside the current one, compared at identical settings, awaiting a decision (RAG-22, RAG-23; defaults unchanged); answer-level evaluation still open (AGENT-10); open items in [Follow_Ups.md](documentation/Follow_Ups.md) |
| — | Trade staging, Guard Pipeline, UTA, execution (v2 scope) | Planned — sequence after core phases above |

## 9. Open Questions

See also [documentation/Target_State_Trading_Desk.md](documentation/Target_State_Trading_Desk.md) for a longer-range
vision (specialist agents, a multi-agent Debate Desk, portfolio/risk committee, AI CIO, self-improvement loop) that
is not yet part of this delivery plan.

See [documentation/Project_Challenges.md](documentation/Project_Challenges.md) for the problems met during delivery so
far and what was learned, each with a pointer to its source.

- Which LLM provider(s) for production vs. fallback?
- Real-time vs. end-of-day granularity for quant features?
- Regulatory/compliance requirements for automated financial recommendations, and how execution mode changes that calculus jurisdiction-by-jurisdiction?
- Should git-based staging be per-user-repo, or a single shared repo with per-user branches?
- Does execution mode require its own compliance sign-off separate from recommend-only mode?
