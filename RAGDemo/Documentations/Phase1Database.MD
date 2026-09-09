# Phase 1 database foundation
Date: 2026-09-09
Status: Implemented; fresh migration, upgrade, schema validation and persistence tests passed on Java 21.

## Purpose, files and schema

- compose.yaml: isolated project ragdemo-phase1, service database, named volume phase1_data, loopback port 5433. Official PostgreSQL 16 Timescale HA image is pinned by digest, not merely a floating tag. Verified available extensions: timescaledb 2.29.2, vector 0.8.6, pgcrypto 1.3. Its "HA" image name does not mean this single-container deployment provides high availability.
- V1–V5 were left unchanged. Their stocks/filings tables and enum migrations remain history.
- src/main/resources/db/migration/V6__infrastructure_foundation.sql: installs TimescaleDB if absent; creates stock_identifiers and ingestion_runs with indexes and constraints. Timescale image already installs TimescaleDB in its fresh default database, so V6 tolerates that.
- src/main/java/project/ragdemo/stock/: Stock, StockRepository, StockIdentifier, StockIdentifierRepository.
- src/main/java/project/ragdemo/ingestion/: IngestionRun, IngestionRunRepository, IngestionStatus.
- src/test/java/project/ragdemo/ingestion/IngestionRunTest.java.
- src/test/java/project/ragdemo/stock/FoundationPersistenceIntegrationTest.java and MigrationUpgradeIntegrationTest.java.
- src/test/java/project/ragdemo/RagDemoApplicationTests.java now asserts extensions and migration state rather than having an empty assertion body.

stock_identifiers stores a stock foreign key, identifier type/value, provenance source, effective interval [valid_from, valid_to), and recorded_at. The lookup requires both effective validity and recorded_at <= asOf. It returns a list so unresolved ambiguity is not silently collapsed. Uniqueness prevents duplicate stock/type/value/source/start observations. Blank identifiers/sources and inverted intervals are rejected by database constraints.

ingestion_runs records provider, dataset, optional stock, RUNNING/SUCCEEDED/FAILED status, start/completion times, count and a bounded error summary. Database constraints reject unknown statuses, negative counts, missing/early terminal completion and error summaries on non-failed runs. No ingestion service executes yet. Caller-supplied error summaries must be sanitized; no automatic sanitizer is claimed.

Stock maps the existing stocks table. No automatic initialization or external stock lookup runs. Its current symbol lookup is not suitable for historical resolution; use the temporal identifier repository where applicable.

## Application methods (all added relative to the starting checkout)

| Class / method | Inputs → output | Function |
| --- | --- | --- |
| Stock.Stock() | None → entity | Protected JPA constructor |
| Stock.Stock(symbol, companyName, cik, recordedAt) | Strings, Instant → entity | Initialize current security and explicit audit timestamps |
| Stock.getId(), getSymbol(), getCompanyName(), getCik() | None → Long/String/String/String | Expose identity and current descriptive fields |
| Stock.isActive(), getCreatedAt(), getUpdatedAt() | None → boolean/Instant/Instant | Expose current status and audit times |
| StockRepository.findBySymbol(symbol) | String → Optional<Stock> | Current-only exact symbol lookup |
| StockIdentifier.StockIdentifier() | None → entity | Protected JPA constructor |
| StockIdentifier.StockIdentifier(stockId, identifierType, identifierValue, source, validFrom, validTo, recordedAt) | Long, strings, instants → entity | Generate UUID, retain provenance/interval and reject inverted interval |
| StockIdentifier.getId(), getStockId(), getIdentifierType(), getIdentifierValue(), getSource() | None → UUID/Long/String/String/String | Expose identifier identity and provenance |
| StockIdentifier.getValidFrom(), getValidTo(), getRecordedAt() | None → Instant each; validTo may be null | Expose effective interval and knowledge cutoff |
| StockIdentifierRepository.findKnownAt(type, value, asOf) | Strings, Instant → List<StockIdentifier> | Filter effective validity and future knowledge in the database |
| IngestionRun.IngestionRun() | None → entity | Protected JPA constructor |
| IngestionRun.IngestionRun(provider, dataset, stockId, startedAt) | Strings, nullable Long, Instant → entity | Generate run UUID in RUNNING state |
| IngestionRun.succeed(completedAt, recordsProcessed) | Instant, long → void | Complete once with successful count |
| IngestionRun.fail(completedAt, recordsProcessed, errorSummary) | Instant, long, String → void | Complete once with sanitized failure summary |
| IngestionRun.finish(next, completedAt, recordsProcessed, errorSummary) | Status, Instant, long, String → void | Private transition guard, time/count/length validation |
| IngestionRun.getId(), getProvider(), getDataset(), getStockId(), getStatus() | None → UUID/String/String/Long/IngestionStatus | Expose run identity and state |
| IngestionRun.getStartedAt(), getCompletedAt(), getRecordsProcessed(), getErrorSummary() | None → Instant/Instant/long/String | Expose outcome/provenance fields |

IngestionStatus is an enum with RUNNING, SUCCEEDED and FAILED; no custom methods. IngestionRunRepository adds no custom methods; all repositories inherit Spring Data persistence operations.

## Tests and validation

All following test methods take no arguments and return void with assertions.

| Class.method | Purpose |
| --- | --- |
| IngestionRunTest.successCompletesRunExactlyOnce | Successful transition/count and reject second completion |
| IngestionRunTest.failureRetainsBoundedSafeSummaryAndCount | Persistable failure fields |
| IngestionRunTest.invalidCompletionDoesNotChangeRunningState | Time/count/summary errors leave run state unchanged |
| FoundationPersistenceIntegrationTest.roundTripsStockAndIngestionProvenance | Actual JPA writes, repository lookup and SQL row assertions |
| FoundationPersistenceIntegrationTest.identifierLookupExcludesFutureKnowledgeAndRespectsHalfOpenIntervals | Future recordedAt exclusion and interval boundary checks |
| FoundationPersistenceIntegrationTest.databaseRejectsInvalidStatusEvenWhenBypassingJava | SQL status constraint |
| FoundationPersistenceIntegrationTest.databaseRejectsDuplicateIdentifiers | SQL uniqueness constraint |
| FoundationPersistenceIntegrationTest.databaseRejectsNegativeCounts | Negative count rejected independently of run state |
| FoundationPersistenceIntegrationTest.databaseRejectsIncompleteTerminalRuns | Terminal run without completion rejected independently of count |
| MigrationUpgradeIntegrationTest.upgradesV5SchemaWithoutLosingExistingStocksOrFilings | Migrate a random isolated schema to V5, seed rows, upgrade only V6, verify rows and no-op repeat migration; drop only that test schema |
| RagDemoApplicationTests.contextLoadsWithValidatedMigrationsAndAllRequiredExtensions | Real application context, Flyway validation/version/pending state, extension presence, no automatic vector_store creation |

Eleven database/domain tests passed in the final complete 17-test Java 21 run. Actual fresh public-schema V1–V6 migration passed on the isolated database; the later Java 21 run validated the already-migrated schema and exercised the V5 upgrade test.

## Limits and next phase

No price bars, hypertables, fundamentals, ingestion jobs, embeddings, semantic memory or forecast tables exist yet. V1–V5 history still includes a public.vector_store-specific metadata backfill; no claim is made that it supports custom vector tables.

Current stocks.symbol and stocks.cik uniqueness is inherited. Multiple share classes, ticker changes, delistings and point-in-time corporate actions need deliberate Phase 2 modeling. Identifier intervals are a foundation, not a complete historical security resolver; cross-source conflicts and overlapping intervals are not automatically resolved.

See [Phase 1 checkpoint](Phase1Checkpoint.MD) for startup commands and all adjustable environment variables.

Image provenance: [official Timescale Docker repository](https://github.com/timescale/timescaledb-docker-ha). Local image extensions were verified directly using pg_available_extensions.
