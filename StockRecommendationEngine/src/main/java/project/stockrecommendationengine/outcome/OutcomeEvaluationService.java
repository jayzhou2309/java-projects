package project.stockrecommendationengine.outcome;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import project.stockrecommendationengine.broker.BrokerException;
import project.stockrecommendationengine.broker.BrokerReadService;
import project.stockrecommendationengine.broker.BrokerData.DailyBar;
import project.stockrecommendationengine.broker.BrokerData.PriceHistory;
import project.stockrecommendationengine.quant.PriceBarRepository;
import project.stockrecommendationengine.recommendation.RecommendationRecord;
import project.stockrecommendationengine.recommendation.RecommendationRepository;

/**
 * Scores stored recommendations against stored daily bars. Bars are refreshed through the broker when it is
 * available and the stored series is old; without a broker, evaluation uses whatever bars are stored.
 * No model is involved; only bars dated after a recommendation's bars_as_of are ever used to score it.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "outcomes.enabled", havingValue = "true")
public class OutcomeEvaluationService {
    private final RecommendationRepository recommendations;
    private final OutcomeRepository outcomes;
    private final PriceBarRepository bars;
    private final BrokerReadService broker;
    private final OutcomeProperties properties;
    private final Clock clock;

    @Autowired
    public OutcomeEvaluationService(RecommendationRepository recommendations, OutcomeRepository outcomes, PriceBarRepository bars,
            ObjectProvider<BrokerReadService> brokers, OutcomeProperties properties) {
        this(recommendations, outcomes, bars, brokers.getIfAvailable(), properties, Clock.systemUTC());
    }
    OutcomeEvaluationService(RecommendationRepository recommendations, OutcomeRepository outcomes, PriceBarRepository bars,
            BrokerReadService broker, OutcomeProperties properties, Clock clock) {
        this.recommendations = recommendations;
        this.outcomes = outcomes;
        this.bars = bars;
        this.broker = broker;
        this.properties = properties;
        this.clock = clock;
    }

    /** Result of one evaluation pass. pendingHorizons counts run/horizon pairs still waiting for enough bars. */
    public record EvaluationRun(Instant startedAt, long elapsedMs, int runsConsidered, int outcomesWritten, int pendingHorizons,
            List<String> barRefreshFailures, List<String> failedRuns) { }

    public EvaluationRun evaluateAll() {
        Instant started = clock.instant();
        var horizons = properties.getHorizons();
        var pending = recommendations.findPendingEvaluation(horizons.size(), properties.getMaxRunsPerEvaluation());
        var refreshFailures = new ArrayList<String>();
        var failedRuns = new ArrayList<String>();
        var barCache = new HashMap<Long, List<DailyBar>>();
        List<DailyBar> benchmark = history(properties.getBenchmarkConid(), refreshFailures, barCache);
        int written = 0, pendingHorizons = 0;
        for (RecommendationRecord rec : pending) {
            try {
                var done = new HashSet<>(outcomes.evaluatedHorizons(rec.runId()));
                List<DailyBar> series = history(rec.conid(), refreshFailures, barCache);
                for (int horizon : horizons) {
                    if (done.contains(horizon)) continue;
                    var outcome = OutcomeCalculator.evaluate(rec, series, benchmark, properties.getBenchmarkConid(), horizon, clock.instant());
                    if (outcome.isPresent()) { outcomes.upsert(outcome.get()); written++; }
                    else pendingHorizons++;
                }
            } catch (RuntimeException ex) {
                failedRuns.add(rec.runId());
                log.warn("Outcome evaluation failed: run={} cause={}", rec.runId(), ex.getClass().getSimpleName());
            }
        }
        long elapsed = Duration.between(started, clock.instant()).toMillis();
        log.info("Outcome evaluation: runs={} written={} pending={} refreshFailures={} failedRuns={} elapsedMs={}",
                pending.size(), written, pendingHorizons, refreshFailures.size(), failedRuns.size(), elapsed);
        return new EvaluationRun(started, elapsed, pending.size(), written, pendingHorizons, List.copyOf(refreshFailures), List.copyOf(failedRuns));
    }

    /** Evaluate one run now; returns the outcomes stored for it afterwards. */
    public List<OutcomeRecord> evaluate(String runId) {
        var rec = recommendations.findByRunId(runId).orElseThrow(() -> new NoSuchElementException(runId));
        var refreshFailures = new ArrayList<String>();
        var cache = new HashMap<Long, List<DailyBar>>();
        if (rec.conid() != null && rec.barsAsOf() != null && rec.lastClose() != null) {
            List<DailyBar> benchmark = history(properties.getBenchmarkConid(), refreshFailures, cache);
            List<DailyBar> series = history(rec.conid(), refreshFailures, cache);
            for (int horizon : properties.getHorizons()) {
                OutcomeCalculator.evaluate(rec, series, benchmark, properties.getBenchmarkConid(), horizon, clock.instant())
                        .ifPresent(outcomes::upsert);
            }
        }
        return outcomes.findByRunId(runId);
    }

    private List<DailyBar> history(Long conid, List<String> refreshFailures, Map<Long, List<DailyBar>> cache) {
        if (conid == null) return List.of();
        return cache.computeIfAbsent(conid, id -> {
            var stored = bars.findLatest(id, 400);
            boolean fresh = stored.isPresent()
                    && stored.get().observedAt().isAfter(clock.instant().minus(Duration.ofHours(properties.getRefreshHours())));
            if (broker != null && !fresh) {
                try {
                    PriceHistory history = broker.getDailyBars(id, properties.getHistoryDays());
                    if (history == null || history.bars().isEmpty()) throw new BrokerException(BrokerException.Code.INVALID_RESPONSE);
                    bars.upsert(history);
                    return bars.findLatest(id, 400).map(PriceHistory::bars).orElse(history.bars());
                } catch (BrokerException ex) {
                    refreshFailures.add(id + ":" + ex.code());
                }
            }
            return stored.map(PriceHistory::bars).orElse(List.of());
        });
    }
}
