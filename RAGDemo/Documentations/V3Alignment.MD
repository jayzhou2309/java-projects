# Stock Recommendation Engine v3 alignment
Date: 2026-09-09
Status: Governing documentation updated; implementation gaps identified. No application code changed.

## Authority and retained foundation
The user directed all development to follow PRD_Stock_Recommendation_Engine_v3.md. V3 is now referenced by AGENTS.md, the delivery plan and the two existing task skills. The older PRD is explicitly marked superseded, and the historical architecture assessment points here. The v3 document itself was not rewritten.

Phase 1 remains implemented as a foundation: Java 21, PostgreSQL/pgvector/TimescaleDB, Flyway, core stock/identifier/ingestion entities and basic local security. V3's own delivery table recognizes Phase 1 as implemented. That does not mean its full enterprise backbone, multi-user security or execution controls exist.

## Changes needed for subsequent implementation
| Area | Current state | V3 direction / proposed next action |
| --- | --- | --- |
| Product contract | No recommendation endpoint yet | BUY/SELL/HOLD, confidence, take_profit, stop_loss, reasoning and sources; explicit unavailable/partial states when inputs are missing |
| Quant outputs | No quant service | Deterministic TP/SL calculation and versioned composite confidence; preserve input data/time/calculation provenance and validate before assembling final response |
| Forecast emphasis | Historical assessment prescribed Python ML and 5D price/return forecasts | V3's primary output is a recommendation. Phase 4 includes ML baseline/confidence, but a separate Python service and fixed 5D horizon are not mandatory in v3; decide after quant/model scope is defined |
| Streaming/cache | No Kafka or Redis | Introduce them with Phase 2 ingestion/events and feature serving, using freshness/version metadata and replay-safe processing |
| HTTP concurrency | Spring MVC with blocking JPA | V3 specifies WebFlux. Plan a deliberate reactive HTTP boundary; never execute blocking JPA/JDBC/provider operations on event-loop threads. Retest security when replacing MVC |
| Batch work | No batch jobs | Add Spring Batch for scheduled ingestion/evaluation as those pipelines are implemented |
| RAG | Extension only, no active ingestion/retrieval | Section-aware SEC ingestion, versioned embeddings, event-driven updates, citations and independently measured retrieval quality |
| Identity/audit | One in-memory user; no recommendation audit | Add durable user/account ownership, recommend-only default and recommendation/run/tool-output audit before shared recommendation use |
| Execution | None | After core phases: opt-in account mode, per-user proposals, explicit authenticated approval, deterministic guards, UTA adapter and paper-only reliability validation |
| Feedback | No outcomes/jobs | Persist recommendation outcomes; batch prompt/weight proposals require human review before promotion |
| Observability | Basic logs and liveness | Micrometer metrics and correlation IDs before latency measurement; Prometheus/Grafana for operational visibility |

The existing tables need additive migrations as capabilities arrive, not a Phase 1 schema reset. Prices/history and fundamentals need provenance; Redis is not the audit source of truth. Postgres remains authoritative for recommendations, approvals and outcomes.

## Important design decisions still open
- Choose and verify a current market-data provider contract and historical/live granularity. The PRD provider list is a candidate list, not proof of availability or existing integrations.
- Define the exact deterministic TP/SL policy, supported position sides, required entry anchor and missing-data policy.
- Define composite confidence components, calibration, versioned weights and missing-component behavior. It must not silently imply a calibrated probability when it is a score.
- Define p95 <5s measurement boundaries and workload. Precompute features and ingest filings outside the interactive request; measure LLM and retrieval latency rather than promising the target from architecture alone.
- Git commit approval needs binding to an authenticated user/account and immutable proposal version/hash. An arbitrary commit alone must not become execution authorization.
- Execution retries must use idempotency and order reconciliation. An ambiguous broker timeout must not cause automatic duplicate submission or blind failover to another broker.
- Retain point-in-time/no-look-ahead validation for credible backtesting. V3 does not explicitly remove that correctness requirement.
- Clarify memory and specialist contracts as their phases approach; v3 lists these phases but gives fewer detailed contracts than the older PRD.

## Proposed near-term order
1. Agree the Phase 2 market-data/granularity and event contracts.
2. Introduce Kafka/Redis with replay-safe ingestion, historical storage and freshness-aware features.
3. Add metrics and the WebFlux/JPA execution boundary with focused security/concurrency tests.
4. Continue SEC RAG, quant/confidence baseline, agents and orchestration in the v3 delivery order.
5. Deliver and evaluate recommend-only before implementing the optional paper execution path.

No broker orders, account-mode changes or execution work are authorized merely by adopting v3.

## Changed documentation and validation
Changed AGENTS.md, MultiAgentPlan.MD, ArchitectureAssessment.MD, the older src/main/java/project/ragdemo/PRD.MD header, both existing SKILL.md references, and this review. Methods added/changed: none. No runtime configuration, dependencies, entities or migrations changed. Compared v3 against current pom.xml, Phase 1 records and delivery plan. Application tests were not run for this documentation-only alignment. Phase 1's recorded validation remains historical validation of that implementation, not verification of proposed v3 capabilities.
