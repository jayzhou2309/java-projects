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
| 2 | Market data ingestion | Planned |
| 3 | SEC RAG pipeline | Planned |
| 4 | ML baseline / confidence scoring | Planned |
| 5 | Specialist agents | Planned |
| 6 | Orchestration | Planned |
| 7 | Critic and synthesis | Planned |
| 8 | Memory | Planned |
| 9 | Evaluation platform | Planned |
| — | Trade staging, Guard Pipeline, UTA, execution (v2 scope) | Planned — sequence after core phases above |

## 9. Open Questions
- Which LLM provider(s) for production vs. fallback?
- Real-time vs. end-of-day granularity for quant features?
- Regulatory/compliance requirements for automated financial recommendations, and how execution mode changes that calculus jurisdiction-by-jurisdiction?
- Should git-based staging be per-user-repo, or a single shared repo with per-user branches?
- Does execution mode require its own compliance sign-off separate from recommend-only mode?
