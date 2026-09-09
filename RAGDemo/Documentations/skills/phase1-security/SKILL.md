---
name: phase1-security
description: Implement RAGDemo Phase 1 application configuration, minimal health endpoint, and tested HTTP security without external provider calls.
---
# Phase 1 security task
Read AGENTS.md and Documentations/PRD_Stock_Recommendation_Engine_v3.md. Own RagDemoApplication.java, config/, a minimal health controller, application.yaml, security/config tests, and Documentations/Phase1Security.MD.
Remove imports of missing provider classes. No agents or model/provider calls in Phase 1.
Default startup must need only database and application auth settings, not OpenAI, SEC, or IBKR credentials. Parent will remove provider dependencies for this phase. Flyway owns schema, Hibernate validates it, and automatic Compose startup is disabled.
Provide explicit local/test profiles as useful; use environment variables for credentials, separate auth username/password, avoid logging credentials. Default auth must fail clearly if application credentials are missing; tests set explicit non-production credentials. Public minimal liveness endpoint may return no sensitive state; all other routes require authentication. Preserve CSRF unless there is a concrete implemented API flow requiring a different policy.
Coordinate database settings with parent: default isolated Phase 1 PostgreSQL port 5433, database/user ragdemo, password required. Document all effective variables and overrides. Do not edit pom.xml, Compose, migrations or stock/ingestion entities.
Document exact methods, inputs/outputs, startup flow, configuration, actual tests and omissions.
