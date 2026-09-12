# Quant Layer: Deterministic Price Statistics and Levels

* Implementation Status
    * Deterministic take-profit, stop-loss, volatility, trend, and momentum statistics from broker daily bars.
    * Consumes the [TWS broker integration](IBKR.md) for history and feeds the [agent harness](Agent_Harness.md) through one tool.
    * Disabled by default; requires the broker integration when enabled.
    * No model call is involved in any number this layer produces.
    * Confidence in recommendation responses is an input-coverage composite, not a probability; a separate calibratedConfidence is attached to directional runs once stored outcomes support a calibration snapshot ([Outcomes.md](Outcomes.md)).
    * Position sizing, backtesting, ensemble agreement, and outcome calibration are not implemented.
    * This is the first deterministic numeric module required by the PRD, not completion of PRD phase 4.

* Why This Exists
    * The PRD requires that price levels and confidence never come from model free text.
    * Before this layer, every recommendation carried QUANT_MODULE_NOT_IMPLEMENTED and null numeric fields.
    * TWS delivers regular-hours daily bars without a market-data subscription, so no additional data vendor is required to start.

* QuantProperties
    * Configuration prefix
        * quant

| Property | Default | Meaning |
|---|---|---|
| quant.enabled | false | Opt-in bean creation; startup fails clearly if the broker integration is disabled |
| quant.history-days | 120 | Daily bars requested per broker refresh; IBKR counts trading days for a day-unit duration (30–365) |
| quant.refresh-hours | 12 | Stored bars newer than this are reused without a broker request |
| quant.min-bars | 60 | Fewer bars produce INSUFFICIENT_BARS instead of weak statistics; must exceed every indicator period |
| quant.max-bar-age-days | 5 | A newest bar older than this marks STALE_BARS and suppresses levels |
| quant.atr-period | 14 | Wilder ATR period |
| quant.volatility-period | 20 | Daily log-return window for annualized realized volatility and momentum |
| quant.short-sma-period | 20 | Short simple moving average |
| quant.long-sma-period | 50 | Long simple moving average used for the trend label |
| quant.take-profit-atr-multiple | 2.0 | Distance from last close to take-profit, in ATRs |
| quant.stop-loss-atr-multiple | 1.0 | Distance from last close to stop-loss, in ATRs |

* PriceBarRepository
    * Purpose
        * Persist broker daily bars so repeated analyses do not repeat historical-data requests.
        * Keep the series auditable: one row per contract and date, with retrieval time and source.
    * Methods
        * findLatest(long conid, int limit)
            * Return the newest limit stored bars for a contract, oldest first.
            * observedAt is the most recent retrieval time among the returned rows.
        * upsert(PriceHistory history)
            * Insert or update each bar by (conid, bar_date) in one transaction.
            * Dates absent from the new series are retained; nothing is deleted.
    * Schema
        * Migration V4 creates price_bars with primary key (conid, bar_date).
        * Check constraints reject nonpositive prices, high below low, and negative volume.
        * source records TWS_DAILY_TRADES; a different provider must use a different source value.

* QuantIndicators
    * Purpose
        * Pure functions over ascending daily bars; each returns null when the series is too short.
    * Methods
        * atr(bars, period)
            * True range = max(high − low, |high − previous close|, |low − previous close|).
            * Wilder smoothing: mean of the first period true ranges, then (previous × (period − 1) + current) / period.
        * realizedVolatility(bars, period)
            * Sample standard deviation of the last period daily log returns, multiplied by √252.
        * sma(bars, period)
            * Mean of the last period closes.
        * momentum(bars, period)
            * Last close divided by the close period bars earlier, minus one.

* QuantAnalysisService
    * Purpose
        * Produce one QuantAnalysis for a contract from stored or freshly retrieved bars.
    * Methods
        * analyze(long conid)
            * Reject nonpositive contract IDs before any lookup.
            * Reuse stored bars when their retrieval time is within refresh-hours.
            * Otherwise request history-days of bars from the broker and upsert them.
            * When the refresh fails and stored bars exist, use the stored bars and add HISTORY_REFRESH_FAILED:<code>.
            * When the refresh fails with no stored bars, propagate the BrokerException.
            * Throw INSUFFICIENT_BARS below min-bars.
            * Mark STALE_BARS when the newest bar is older than max-bar-age-days; statistics are still returned, levels are not.
            * Compute ATR, realized volatility, short and long SMA, momentum, and the trend label.
            * Compute long levels (close + tp × ATR, close − sl × ATR) and short levels (close − tp × ATR, close + sl × ATR), rounded to cents.
            * Suppress both level pairs with LEVELS_OUT_OF_RANGE if any level would be nonpositive.
    * Freshness semantics
        * observedAt is when bars were retrieved from the broker, not a market timestamp.
        * asOf is the date of the newest bar; the bar for the current session appears only after the session closes in TWS's regular-hours series.
        * fromCache=true means no broker request was made for this call.

* QuantAnalysis
    * Fields
        * conid, symbol, currency: contract identity from contract details, never from the request.
        * source, fromCache, observedAt, asOf, barCount: provenance.
        * lastClose, atr, atrPeriod, realizedVolatility, volatilityPeriod, shortSma, shortSmaPeriod, longSma, longSmaPeriod, momentum.
        * trend: ABOVE_LONG_SMA, BELOW_LONG_SMA, or UNAVAILABLE.
        * longLevels and shortLevels: takeProfit and stopLoss for each direction, or null when stale or out of range.
        * levelMethod: ATR_MULTIPLE_OF_LAST_CLOSE.
        * limitations: STALE_BARS, LEVELS_OUT_OF_RANGE, HISTORY_REFRESH_FAILED:<code>.

* QuantController
    * Purpose
        * Read-only inspection of the same analysis the recommendation loop attaches.
        * Protected by the integration access token, like the broker and recommendation endpoints.
    * Methods
        * GET /api/quant/analysis/{conid}
            * Return QuantAnalysis as JSON.
            * INSUFFICIENT_BARS returns HTTP 422; broker failures use the existing sanitized broker codes and statuses.

* Harness Integration
    * The broker specialist receives one additional tool when the module is enabled.

| Tool | Arguments | Availability | Behavior |
|---|---|---|---|
| analyzePriceHistory | conid | Broker and quant enabled | Same implicit-discovery, explicit-selection, preferred-currency, and ambiguity rules as getQuote; returns QuantAnalysis. The harness prefetches it before the broker specialist model runs. |

    * The specialist report carries the analysis under priceAnalysis alongside quotes and portfolio.
    * The manager is instructed to report returned statistics verbatim and remains forbidden from producing numbers.
    * finish(...) selects levels from application state by assessment: BULLISH uses longLevels, BEARISH uses shortLevels, NEUTRAL and INSUFFICIENT_EVIDENCE carry none.
    * Analysis limitations are copied into response limitations with the analyzePriceHistory: prefix.
    * NO_PRICE_HISTORY is added when the module is enabled but no analysis was produced during the run.
    * QUANT_DISABLED replaces the former QUANT_MODULE_NOT_IMPLEMENTED when the module is off.
    * POSITION_SIZING_NOT_IMPLEMENTED is always present; CONFIDENCE_UNCALIBRATED until a calibration snapshot applies.

* Confidence
    * Computed in RecommendationService from application state only; null for INSUFFICIENT_EVIDENCE.
    * Score = 0.50 × mean vector similarity of cited passages + 0.25 × quote term + 0.25 × history term, rounded to two decimals.
    * Quote term: 1.0 for a verified current realtime quote, 0.5 for any quote with a price, 0.0 otherwise.
    * History term: 1.0 when levels were computed, 0.5 when an analysis exists without levels, 0.0 without an analysis.
    * Vector similarity measures passage nearness to the specialist's query, not claim support; the term is a coverage proxy.
    * The PRD's ensemble-agreement and backtest-accuracy terms are absent; the score must not be presented as a probability of a correct call.

* Quant Pipeline

```text
Broker specialist → analyzePriceHistory(conid)
    ↓
Contract discovery / selection guard (shared with getQuote)
    ↓
QuantAnalysisService.analyze
    ↓
PriceBarRepository.findLatest ── fresh? ──► stored bars
    ↓ stale or absent
BrokerReadService.getDailyBars (TWS reqHistoricalData, 1 day TRADES, RTH)
    ↓
PriceBarRepository.upsert
    ↓
QuantIndicators (ATR, volatility, SMA, momentum) → ATR-multiple levels
    ↓
QuantAnalysis → specialist report → manager
    ↓
finish(...): levels by assessment + input-coverage confidence → RecommendationResponse
```

* Enabling the Module
    * Configure and start the broker integration as described in [IBKR.md](IBKR.md).
    * Restart the application to apply migration V4 before enabling.

```bash
export QUANT_ENABLED=true
./mvnw spring-boot:run

curl -H "Authorization: Bearer $INTEGRATION_ACCESS_TOKEN" http://localhost:8080/api/quant/analysis/265598
```

* Verification

```bash
./mvnw -Dtest=QuantIndicatorsTests,QuantAnalysisServiceTests,PriceBarRepositoryTests,IbkrBrokerAdapterTests,RecommendationServiceTests,IntegrationWiringTests test

# Opt-in: real TWS bars through the real repository; rows are rolled back.
IBKR_CLIENT_ID=73 ./mvnw -Dquant.live=true -Dtest=IbkrQuantLiveTests test
```

    * QuantIndicatorsTests check Wilder ATR, annualized volatility, SMA, and momentum against hand-computed values.
    * QuantAnalysisServiceTests use a fixed clock and mocked broker/repository to verify fetch-and-store, cache reuse, failed-refresh fallback, insufficient bars, stale suppression, and level arithmetic.
    * PriceBarRepositoryTests run against PostgreSQL and verify upsert replacement, ordering, retrieval-time selection, and schema constraints.
    * IbkrBrokerAdapterTests add simulated historical callbacks: ordering, contract metadata, invalid bars, missing volume, timeouts, cancellation, bounded arguments, and request-scoped warnings.
    * RecommendationServiceTests add directional levels, confidence arithmetic, neutral/insufficient handling, discovery guards, analysis limitations, and the disabled-module path.
    * Verification on 2026-09-11 against the local PostgreSQL/pgvector container: 138 tests discovered, 133 passed, 5 opt-in live tests skipped, no failures or errors. The opt-in IbkrQuantLiveTests and IbkrLiveTests daily-bar checks passed separately against paper TWS.
    * These tests establish arithmetic and control flow. They do not establish that ATR multiples are good trading levels for any instrument.

* Known Limitations
    * ATR multiples are a transparent baseline, not a validated strategy; multiples are configuration, not learned parameters.
    * Levels use the last daily close, not the current quote; intraday moves after the close are not reflected.
    * Historical requests count against IBKR pacing limits; the database cache is the only protection.
    * Overnight sessions and splits are not adjusted; TWS supplies unadjusted TRADES bars.
    * Bars for a contract are keyed by conid; a ticker that maps to several listings has separate series.

* Change log — 2026-09-11: quant layer
    * Added BrokerReadService.getDailyBars and GET /api/broker/history/{conid}?days=120 over TWS reqHistoricalData with daily TRADES bars in regular hours. The transport boundary gains history and cancelHistory; no order methods.
    * TwsClient validates bar dates and prices, treats invalid volume as null, sorts by date, cancels incomplete requests, and now ignores request-scoped IBKR warning codes 2100–2199 instead of failing the request. Live AAPL history returned warning 2188 before the bars; the earlier handler would have failed the read.
    * Added quant package: QuantProperties, PriceBarRepository, QuantIndicators, QuantAnalysis, QuantAnalysisService, QuantController, and migration V4 price_bars.
    * Harness: analyzePriceHistory tool for the broker specialist, priceAnalysis in specialist reports and RecommendationResponse, takeProfit/stopLoss selected by assessment, input-coverage confidence, and renamed limitations.
    * Live verification against paper TWS port 7497: 120 AAPL daily bars (2026-03-20 to 2026-09-10) in under one second; the analysis reported last close 326.57, ATR 7.8907, realized volatility 0.2335, trend ABOVE_LONG_SMA, long levels 342.35 / 318.68, short levels 310.79 / 334.46; the second call was served from stored bars. Logs: [quant run](live-runs/2026-09-11-quant/quant-live-run.log), [history run](live-runs/2026-09-11-quant/tws-history-live.log).
    * No live model run was performed with the new tool; RecommendationServiceTests cover the harness path with scripted responses.

* Change log — 2026-09-11: implicit discovery and live end-to-end run
    * A live run with an explicit request conid showed the broker specialist skipping findInstrument, which made analyzePriceHistory fail with CONTRACT_NOT_DISCOVERED. Per-contract tools now discover implicitly when no discovery has happened in the run; the explicit-selection and ambiguity rules are unchanged.
    * Live end-to-end recommendation on 2026-09-11 attached priceAnalysis from stored bars in 22 ms and raised confidence from 0.32 to 0.59; details and evidence are in [Agent_Harness.md](Agent_Harness.md). GET /api/quant/analysis/265598 through the running application returned the same statistics as the opt-in test ([saved output](live-runs/2026-09-11-quant-e2e/quant-analysis.json)).
