# Outcome Tracking: Scoring Stored Recommendations

* Implementation Status
    * Deterministic scoring of every stored recommendation against stored daily bars at fixed horizons.
    * Consumes the [recommendation audit store](Agent_Harness.md) and the [price bars](Quant.md) the quant layer keeps; refreshes bars through the [broker](IBKR.md) when available.
    * Disabled by default; enabling requires the integration access token like the other integrations.
    * No model is involved in scoring. TrackRecordService (below) shows the manager the ticker's prior runs and outcomes; ConfidenceCalibrationService (below) maps a run's raw confidence to the realized hit rate of runs with similar confidence, once enough directional runs are scored.
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
| outcomes.calibration.bins | 5 | Equal-width raw-confidence bins over [0,1] |
| outcomes.calibration.min-samples | 30 | Scored directional runs required before a snapshot is READY and applied |
| outcomes.calibration.prior-weight | 10 | Pseudo-samples at the overall hit rate mixed into every bin; 0 uses raw bin hit rates |

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
    * Runs evaluateAll() and then ConfidenceCalibrationService.compute() on the cron schedule; a calibration failure is logged and does not undo the evaluation. Created only when outcomes.enabled=true.

* TrackRecordService
    * trackRecord(String ticker, int limit)
        * Newest limit stored runs for the ticker (any status) with their stored outcomes per horizon.
        * Per assessment: runs, scored (having the reference horizon, 20 days when configured), directionCorrect count, averageReturnPct.
        * Carries a fixed caveat so the consumer sees the sample-size warning with the numbers.
    * Consumer: the recommendation loop's look-back, documented in [Agent_Harness.md](Agent_Harness.md).

* Confidence Calibration
    * Why
        * The response's confidence is an input-coverage composite (cited-passage similarity, quote verification, price-history availability). It says how much evidence a run had, not how often runs like it were right. The PRD asks for a non-self-reported composite that includes realized accuracy; calibration is the deterministic bridge from the stored outcomes to that figure.
    * Definitions
        * Sample: a stored run with a raw confidence and a directionCorrect outcome at the reference horizon (20 days when configured). NEUTRAL runs and runs without a contract are never samples. The stored confidence is always the raw composite, so calibrating on it is not circular.
        * Bins: outcomes.calibration.bins equal-width intervals of raw confidence over [0,1]; 1.0 belongs to the last bin. Per bin: samples, mean raw confidence, hit rate, a 95% Wilson interval, and the calibrated value.
        * Calibrated value of a bin: (hits + priorWeight × baseRate) / (samples + priorWeight), the bin's hit rate shrunk toward the overall hit rate by priorWeight pseudo-samples; an empty bin equals the base rate, a thin bin moves little from it.
        * expectedCalibrationError: Σ (samples_b / N) × |hitRate_b − meanConfidence_b| over non-empty bins; brierScore: mean (confidence − hit)². Both describe the raw confidence, in-sample.
        * status: READY when N ≥ min-samples, else INSUFFICIENT_SAMPLE; only READY snapshots are applied. Snapshots are appended to confidence_calibrations (migration V7) with per-assessment and per-prompt-version counts and are never updated, so a response's calibrationId always resolves to the exact mapping used.
    * CalibrationCalculator.compute(samples, horizon, bins, minSamples, priorWeight, computedAt): pure arithmetic, tested against hand-computed values.
    * CalibrationRepository
        * samples(horizon): the join of recommendation_outcomes and recommendations described above, oldest first.
        * save(snapshot): append and return with its id. latest(horizon): the newest snapshot for the horizon, whatever its status.
    * ConfidenceCalibrationService
        * compute(): samples at OutcomeProperties.referenceHorizon(), calculator, save; logged with status, samples, base rate, ECE, and Brier.
        * apply(rawConfidence): NO_CALIBRATION when no snapshot exists, INSUFFICIENT_SAMPLE when the newest is not READY, else APPLIED with the bin's calibrated value.
        * Consumer: the recommendation loop, for BULLISH and BEARISH assessments only; see calibratedConfidence and calibration in [Agent_Harness.md](Agent_Harness.md). The raw confidence stays in the response and the audit row.
    * Endpoints: POST /api/outcomes/calibration computes and stores a snapshot now; GET /api/outcomes/calibration returns the newest one (404 before the first).

* Endpoints (integration access token required)
    * POST /api/outcomes/evaluate: run a pass now; returns the EvaluationRun.
    * POST /api/outcomes/evaluate/{runId}: evaluate one run now.
    * GET /api/outcomes/{runId}: stored outcomes for a run.
    * GET /api/outcomes/summary: the aggregate table.
    * POST /api/outcomes/calibration and GET /api/outcomes/calibration: see Confidence Calibration.

* Outcome Pipeline

```text
recommendations (run, assessment, levels, lastClose, barsAsOf)
    ↓ findPendingEvaluation
OutcomeEvaluationService ── bars stale and broker present? ──► BrokerReadService.getDailyBars → PriceBarRepository.upsert
    ↓ bars strictly after barsAsOf (contract and benchmark)
OutcomeCalculator: exit close at horizon, return, benchmark/excess, first touch, excursions, direction
    ↓
recommendation_outcomes (run_id, horizon_days) → GET /api/outcomes/{runId}, /summary
    ↓                                   ↓
TrackRecordService → manager       CalibrationRepository.samples (directional, with raw confidence, reference horizon)
evidence in the next run               ↓ CalibrationCalculator: bins, shrunk hit rates, ECE, Brier
                                   confidence_calibrations snapshot (READY / INSUFFICIENT_SAMPLE) → GET /api/outcomes/calibration
                                       ↓ ConfidenceCalibrationService.apply on the next directional run
                                   RecommendationResponse.calibratedConfidence + calibration (raw confidence unchanged)
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
./mvnw -Dtest=OutcomeCalculatorTests,OutcomeEvaluationServiceTests,OutcomeRepositoryTests,CalibrationCalculatorTests,ConfidenceCalibrationServiceTests,CalibrationRepositoryTests,IntegrationWiringTests test
```

    * OutcomeCalculatorTests check returns, benchmark and excess returns, take-profit and stop-loss first touch, BOTH_SAME_DAY, BEARISH inversion, excursions, NEUTRAL handling, exclusion of the as-of bar, and pending short series against hand-computed values.
    * OutcomeEvaluationServiceTests use mocks and a fixed clock to verify horizon bookkeeping, single bar refresh per contract, benchmark failure tolerance, use of fresh stored bars without the broker, operation without any broker, per-run failure isolation, and single-run evaluation.
    * OutcomeRepositoryTests run against PostgreSQL: upsert replacement, pending-run selection, aggregates, and the touch-value check.
    * IntegrationWiringTests: disabled by default; enabled with or without the broker; enabling without the access token fails at startup.
    * CalibrationCalculatorTests check bins, shrinkage toward the base rate, ECE, Brier, Wilson intervals, bin selection at the edges, the insufficient and empty cases, and zero prior weight against hand-computed values. ConfidenceCalibrationServiceTests cover the reference horizon, storing, and the three apply outcomes. CalibrationRepositoryTests run against PostgreSQL: the sample join excludes other horizons, NEUTRAL, and runs without a confidence; snapshots round-trip through JSONB and the newest per horizon is returned; the status check rejects unknown values.

* Live verification — 2026-09-12, 10:00 SGT
    * Application started with outcomes enabled (broker off, TWS closed); migration V6 applied. POST /api/outcomes/evaluate returned runsConsidered=0: all four stored runs were made with the broker disabled and carry no contract or entry price, so nothing was scorable. A missing token returned 401.
    * To exercise scoring on real prices, one clearly labelled synthetic BULLISH AAPL run (conid 265598, barsAsOf 2026-06-01, entry 306.31, take-profit 324.69, stop-loss 297.12) was inserted directly into the table, evaluated through POST /api/outcomes/evaluate/{runId} against the 70 stored AAPL bars after that date, and deleted afterwards (its outcomes were removed by the cascade; the table holds no synthetic rows).
    * Results: 5 days, exit 2026-06-08 at 301.54, return −1.56%, no level touched, direction wrong; 20 days, exit 2026-06-30 at 289.36, return −5.53%, stop-loss touched first on 2026-06-09 (day 6), max adverse −10.63%; 60 days, exit 2026-08-26 at 313.45, return +2.33%, direction right although the stop had been hit on day 6, max favourable +12.49%. Benchmark returns were null because no SPY bars are stored yet. The summary aggregated the three rows by assessment and horizon.
    * Evidence: [evaluation pass](live-runs/2026-09-12-outcomes/evaluate.json), [synthetic outcomes](live-runs/2026-09-12-outcomes/synthetic-outcomes.json), [synthetic summary](live-runs/2026-09-12-outcomes/synthetic-summary.json), [run log](live-runs/2026-09-12-outcomes/run.log). Full suite: 172 tests, 167 passed, 5 opt-in live tests skipped.
    * Real outcomes will begin accumulating once broker-enabled runs are stored and five trading days have passed; the first benchmark returns need SPY bars, which the nightly pass fetches when TWS is reachable.

* Live verification of calibration — 2026-09-12, 12:35 SGT
    * Application on port 8081 with outcomes enabled, broker and quant off, gpt-4.1; migration V7 already applied by the test run. Sequence and evidence in [live-runs/2026-09-12-calibration](live-runs/2026-09-12-calibration/):
    * Directional AAPL run `3b612b0a-49b8-494a-8749-36618e17eff1` before any snapshot existed: BULLISH, raw confidence 0.28, calibration.status NO_CALIBRATION, CONFIDENCE_UNCALIBRATED present ([response](live-runs/2026-09-12-calibration/response-no-calibration.json)). The critic returned REVISE twice on this run, so it also carries CRITIC_UNRESOLVED with both reviews recorded.
    * POST /api/outcomes/calibration on the real tables: snapshot 5, INSUFFICIENT_SAMPLE, 0 samples, no base rate, five empty bins ([snapshot](live-runs/2026-09-12-calibration/calibration-real.json)); GET returned it, and a missing token returned 401. Every stored run is NEUTRAL or brokerless, so there is nothing to calibrate on yet (Follow_Ups item 1).
    * To exercise the APPLIED path, a clearly labelled synthetic dataset was inserted directly into the tables: 40 directional runs for the impossible ticker ZZCALIB (prompt version synthetic-calibration-check) with raw confidence 0.30 ×10 (3 right), 0.55 ×15 (8 right), 0.90 ×15 (11 right) and 20-day outcomes. The snapshot computed from it (id 6) was READY with 40 samples, base rate 0.55, ECE 0.06875, Brier 0.229687, and bins [0.2,0.4) n=10 hit 0.3 calibrated 0.425, [0.4,0.6) n=15 hit 0.5333 calibrated 0.54, [0.8,1.0] n=15 hit 0.7333 calibrated 0.66, empty bins at the base rate, Wilson intervals attached ([snapshot](live-runs/2026-09-12-calibration/calibration-synthetic.json)); the arithmetic matches the hand computation in CalibrationCalculatorTests.
    * Directional AAPL run `20753998-0d12-4ca7-9908-42229adcb127` with that snapshot in place: BULLISH, raw confidence 0.29, calibratedConfidence 0.425 from bin [0.2,0.4) with binSamples 10 and binHitRate 0.3, calibration.status APPLIED, calibrationId 6, MANAGER:calibrateConfidence in 4 ms, CONFIDENCE_UNCALIBRATED absent; HTTP 200 in 6.2 seconds, critic ACCEPT, five model calls ([response](live-runs/2026-09-12-calibration/response-applied.json)). GET by run ID showed the audit row keeps the raw 0.29 in its confidence column and the calibrated 0.425 with calibrationId 6 inside the response JSON ([record](live-runs/2026-09-12-calibration/record-applied.json)).
    * Clean-up: the 40 synthetic runs (outcomes removed by the cascade), snapshot 6, and run 20753998 (a real request whose calibrated figure came from synthetic data) were deleted; a fresh real snapshot (id 7) is INSUFFICIENT_SAMPLE with 0 samples ([snapshot](live-runs/2026-09-12-calibration/calibration-after-cleanup.json)). The tables hold no synthetic rows. Sanitized log: [run.log](live-runs/2026-09-12-calibration/run.log).
    * Full suite after the change: 188 tests, 183 passed, 5 opt-in live tests skipped, no failures.

* Known Limitations
    * Daily bars only: intraday touch order is unknown, hence BOTH_SAME_DAY; gaps through a level count as touched at the bar's high/low, not at the level.
    * Horizons are trading days as stored bars count them; exchange holidays are handled implicitly, corporate actions are not, since TWS bars are unadjusted.
    * The benchmark conid default has not been verified against TWS discovery in this repository; confirm with GET /api/broker/instruments?symbol=SPY and set OUTCOMES_BENCHMARK_CONID if it differs.
    * Runs stored without a contract (broker disabled) or without levels are never scored for touch; NEUTRAL runs carry return statistics only.
    * Sample sizes will be small for months; the summary reports counts so that no rate is read without its denominator.
    * Calibration is pooled across assessments and versions and is in-sample; segmentation and an out-of-sample check are listed in [Follow_Ups.md](Follow_Ups.md). Until item 1 there (broker-enabled directional runs) produces scored outcomes, every snapshot is INSUFFICIENT_SAMPLE and no response carries a calibrated confidence.
