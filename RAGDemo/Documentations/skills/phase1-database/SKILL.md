---
name: phase1-database
description: Implement and validate the Phase 1 relational foundation for RAGDemo, including additive migrations and core JPA entities.
---
# Phase 1 database task
Read AGENTS.md and Documentations/PRD_Stock_Recommendation_Engine_v3.md. Scope is infrastructure, not providers, agents, market bars, or model training.
Own only V6 migration, new domain classes/repositories under stock/ and ingestion/, their tests, and Documentations/Phase1Database.MD.
Preserve V1–V5 bytes. Add TimescaleDB extension in V6; do not recreate existing stocks/filings. Stock identifiers must accommodate ticker changes via a separate identifier table without claiming full historical resolution now. Add ingestion-run provenance with timestamps and bounded status values; no invented provider results.
Use Java 21, constructor injection, JPA, explicit mappings and database constraints. Keep generated boilerplate small. Use existing stocks schema; avoid turning every field into a new abstraction.
Coordinate test infrastructure with parent before integration tests. Parent owns pom.xml, Compose, CI and application.yaml; do not edit those.
Document exact methods, inputs/outputs, schema decisions, adjustable settings, and validation actually performed. Separate implemented foundations from future time-dependent provider behavior.
