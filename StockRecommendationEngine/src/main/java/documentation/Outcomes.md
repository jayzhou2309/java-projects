# Outcome Tracking: Scoring Stored Recommendations

* Implementation Status
    * Deterministic scoring of every stored recommendation against stored daily bars at fixed horizons.
    * Consumes the [recommendation audit store](Agent_Harness.md) and the [price bars](Quant.md) the quant layer keeps; refreshes bars through the [broker](IBKR.md) when available.
    * Disabled by default; enabling requires the integration access token like the other integrations.
    * No model is involved. Calibration of confidence and a look-back tool for the agent are the next consumers of this table and are not implemented.
    * Every stored run so far is NEUTRAL, so direction and level statistics have no data yet; the pipeline is verified, the numbers are not.

* Why This Exists
    * The PRD's feedback loop needs realized outcomes per recommendation to calibrate confidence and tune deterministic parameters under human review.
    * Without scoring, stored runs are an audit trail only. With it, every run made during testing becomes evaluation data.

* Definitions
    * Entry: the recommendation's stored lastClose, the close of its barsAsOf date.
    * Horizon exit: the close of the h-th trading day strictly after barsAsOf, using stored bars only.
    * returnPct: exit / entry − 1, a fraction. benchmarkReturnPct: the same for the benchmark contract between the same two dates; excessReturnPct: their difference. Both null when a benchmark bar is missing for either date.
    * directionCorrect: BULLISH and returnPct > 0, or BEARISH and returnPct < 0; null for NEUTRAL, which is not scored for direction.
    * firstTouch: walking the bars up to the horizon, the first of the stored takeProfit/stopLoss reached by the high/low (inverted for BEARISH); BOTH_SAME_DAY when one daily bar spans both, because daily bars cannot order intraday events; NONE when neither was reached; NO_LEVELS when the run stored none.
    * maxFavorablePct / maxAdversePct: the largest move for and against the recommendation's direction within the window (long direction for NEUTRAL).
    * Point-in-time rule: only bars dated after barsAsOf are used, so a later evaluation with more bars can never change what a horizon's exit was.

* OutcomeProperties
    * Configuration prefix: outcomes.

| Property | Default | Meaning |
|---|---|---|
| outcomes.enabled | false | Opt-in scheduler, service, and endpoints (OUTCOMES_ENABLED) |
| outcomes.cron | 0 30 7 * * * | After the 07:00 filing refresh |
| outcomes.zone | Asia/Singapore | Cron time zone |
| outcomes.horizons | 5,20,60 | Trading-day horizons evaluated per run |
| outcomes.benchmark-conid | 756733 | Benchmark contract for excess return; IBKR's SPY (ARCA) unless configured (OUTCOMES_BENCHMARK_CONID) |
| outcomes.history-days | 250 | Bars requested when refreshing a contract through the broker |
| outcomes.refresh-hours | 12 | Stored bars newer than this are used without a broker request |
| outcomes.max-runs-per-evaluation | 500 | Runs considered per pass, oldest first |

* OutcomeRepository
    * upsert(OutcomeRecord): insert or replace one run/horizon row.
    * findByRunId(String runId): stored outcomes for a run, by horizon.
    * evaluatedHorizons(String runId): horizons already stored.
    * summary(): per assessment and horizon, count, average return, average excess return, direction hit rate, take-profit-first rate, stop-loss-first rate.
    * RecommendationRepository.findPendingEvaluation(horizonCount, limit): scorable runs (contract, bar date, entry price, BULLISH/BEARISH/NEUTRAL) with fewer stored outcomes than horizons, oldest first.
    * Schema: migration V6 recommendation_outcomes, primary key (run_id, horizon_days), foreign key to recommendations, a check on first_touch values.

* OutcomeCalculator
    * evaluate(record, barsAfterAsOf, benchmarkBars, benchmarkConid, horizon, evaluatedAt)
        * Filter bars strictly after barsAsOf, sort, require at least horizon bars, else empty.
        * Compute return, touch walk, excursions, benchmark and excess return as defined above.

* OutcomeEvaluationService
    * evaluateAll()
        * Load pending runs; load the benchmark series once; for each run, load its bars (refreshing through the broker when stored bars are older than refresh-hours and a broker is present), then evaluate every horizon not yet stored.
        * Horizons without enough bars count as pending; a run whose evaluation throws is recorded in failedRuns and the others continue; a contract whose bar refresh fails is recorded in barRefreshFailures and stored bars are used.
        * Returns EvaluationRun with counts and elapsed time.
    * evaluate(String runId)
        * Evaluate one run now and return its stored outcomes; unknown run IDs raise NoSuchElementException (HTTP 404).
    * Bar refresh uses BrokerReadService.getDailyBars on the recommendation's conid; without a broker, evaluation uses stored bars only.

* OutcomeScheduler
    * Runs evaluateAll() on the cron schedule; created only when outcomes.enabled=true.

* Endpoints (integration access token required)
    * POST /api/outcomes/evaluate: run a pass now; returns the EvaluationRun.
    * POST /api/outcomes/evaluate/{runId}: evaluate one run now.
    * GET /api/outcomes/{runId}: stored outcomes for a run.
    * GET /api/outcomes/summary: the aggregate table.

* Outcome Pipeline

```text
recommendations (run, assessment, levels, lastClose, barsAsOf)
    ↓ findPendingEvaluation
OutcomeEvaluationService ── bars stale and broker present? ──► BrokerReadService.getDailyBars → PriceBarRepository.upsert
    ↓ bars strictly after barsAsOf (contract and benchmark)
OutcomeCalculator: exit close at horizon, return, benchmark/excess, first touch, excursions, direction
    ↓
recommendation_outcomes (run_id, horizon_days) → GET /api/outcomes/{runId}, /summary
    ↓
(next) confidence calibration and the agent's look-back tool
```

* Enabling

```bash
export OUTCOMES_ENABLED=true
./mvnw spring-boot:run

curl -X POST -H "Authorization: Bearer $INTEGRATION_ACCESS_TOKEN" http://localhost:8080/api/outcomes/evaluate
curl -H "Authorization: Bearer $INTEGRATION_ACCESS_TOKEN" http://localhost:8080/api/outcomes/summary
```

* Verification

```bash
./mvnw -Dtest=OutcomeCalculatorTests,OutcomeEvaluationServiceTests,OutcomeRepositoryTests,IntegrationWiringTests test
```

    * OutcomeCalculatorTests check returns, benchmark and excess returns, take-profit and stop-loss first touch, BOTH_SAME_DAY, BEARISH inversion, excursions, NEUTRAL handling, exclusion of the as-of bar, and pending short series against hand-computed values.
    * OutcomeEvaluationServiceTests use mocks and a fixed clock to verify horizon bookkeeping, single bar refresh per contract, benchmark failure tolerance, use of fresh stored bars without the broker, operation without any broker, per-run failure isolation, and single-run evaluation.
    * OutcomeRepositoryTests run against PostgreSQL: upsert replacement, pending-run selection, aggregates, and the touch-value check.
    * IntegrationWiringTests: disabled by default; enabled with or without the broker; enabling without the access token fails at startup.

* Live verification — 2026-09-12, 10:00 SGT
    * Application started with outcomes enabled (broker off, TWS closed); migration V6 applied. POST /api/outcomes/evaluate returned runsConsidered=0: all four stored runs were made with the broker disabled and carry no contract or entry price, so nothing was scorable. A missing token returned 401.
    * To exercise scoring on real prices, one clearly labelled synthetic BULLISH AAPL run (conid 265598, barsAsOf 2026-06-01, entry 306.31, take-profit 324.69, stop-loss 297.12) was inserted directly into the table, evaluated through POST /api/outcomes/evaluate/{runId} against the 70 stored AAPL bars after that date, and deleted afterwards (its outcomes were removed by the cascade; the table holds no synthetic rows).
    * Results: 5 days, exit 2026-06-08 at 301.54, return −1.56%, no level touched, direction wrong; 20 days, exit 2026-06-30 at 289.36, return −5.53%, stop-loss touched first on 2026-06-09 (day 6), max adverse −10.63%; 60 days, exit 2026-08-26 at 313.45, return +2.33%, direction right although the stop had been hit on day 6, max favourable +12.49%. Benchmark returns were null because no SPY bars are stored yet. The summary aggregated the three rows by assessment and horizon.
    * Evidence: [evaluation pass](live-runs/2026-09-12-outcomes/evaluate.json), [synthetic outcomes](live-runs/2026-09-12-outcomes/synthetic-outcomes.json), [synthetic summary](live-runs/2026-09-12-outcomes/synthetic-summary.json), [run log](live-runs/2026-09-12-outcomes/run.log). Full suite: 172 tests, 167 passed, 5 opt-in live tests skipped.
    * Real outcomes will begin accumulating once broker-enabled runs are stored and five trading days have passed; the first benchmark returns need SPY bars, which the nightly pass fetches when TWS is reachable.

* Known Limitations
    * Daily bars only: intraday touch order is unknown, hence BOTH_SAME_DAY; gaps through a level count as touched at the bar's high/low, not at the level.
    * Horizons are trading days as stored bars count them; exchange holidays are handled implicitly, corporate actions are not, since TWS bars are unadjusted.
    * The benchmark conid default has not been verified against TWS discovery in this repository; confirm with GET /api/broker/instruments?symbol=SPY and set OUTCOMES_BENCHMARK_CONID if it differs.
    * Runs stored without a contract (broker disabled) or without levels are never scored for touch; NEUTRAL runs carry return statistics only.
    * Sample sizes will be small for months; the summary reports counts so that no rate is read without its denominator.
