# Architecture assessment and Phase 1 scope

> Historical Phase 1 assessment. From 2026-09-09, [v3](PRD_Stock_Recommendation_Engine_v3.md) governs new work; see [alignment review](V3Alignment.MD). Its mandatory Python/5D-forecast decisions below do not automatically carry forward.
Date: 2026-09-09
Status: Assessment accepted by user; Phase 1 implementation recorded in Phase1Checkpoint.MD.

## Starting checkout
PRD.MD defines a new nine-phase product: quantitative ML generates numerical forecasts; specialist LLMs interpret evidence and challenge the model. The PRD remains at src/main/java/project/ragdemo/PRD.MD.

At inspection the checkout retained Maven, application configuration, V1–V5 migrations, the main application class, old pipeline tests and monitor frontend. Most earlier production classes and old documentation were already absent, with staged deletions. RagDemoApplication imported missing IBKRProperties. The previous "Phase 3 complete" claim did not describe this checkout.

Existing stocks/filings migrations and useful behavioral test cases were reusable. Java 17, mixed MySQL/PostgreSQL dependencies, automatic Spring AI schema initialization and missing provider imports conflicted with the new foundation.

## Accepted boundary and sequence
Java owns application/API/security, providers, persisted evidence, retrieval, deterministic versioned features and orchestration. A future Python service owns training, walk-forward evaluation, calibration, model artifacts and inference. No Python service is created in Phase 1.

Capabilities will be added in PRD phase order: infrastructure, market data, SEC RAG, ML baseline, specialists, orchestration, critic/synthesis, memory, evaluation platform. Evaluation and temporal correctness tests start in their relevant phases, not only in the final phase.

Phase 1 restores a coherent build, secure minimal HTTP startup, PostgreSQL/pgvector/TimescaleDB, Flyway ownership and core security/ingestion-provenance entities. It does not revive the old recommendation pipeline wholesale.

## Architectural decisions carried forward
- Default scope is daily US equities and a future five-trading-session horizon. The market calendar, price-adjustment policy and prediction cutoff still need Phase 2/4 implementation.
- Each temporal capability must distinguish effective time, source availability and recording time.
- Numerical forecasts must come from a trained model; agents may not overwrite them.
- Use shared versioned features in training and inference, avoiding independently implemented formulas.
- Preserve V1–V5 and add migrations. Do not infer publication timestamps for date-only old filings.
- Use an isolated database for validation instead of upgrading the user's older local database in place.
- Start with one service boundary for ML and interfaces at genuine provider/reranker/embedding boundaries.
- Persistent memory and application-level agent execution remain deferred.
