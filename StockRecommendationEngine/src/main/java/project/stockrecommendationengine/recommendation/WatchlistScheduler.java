package project.stockrecommendationengine.recommendation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Runs the configured watchlist through the recommendation loop on a schedule, one ticker at a time, with a
 * directional question, so scored directional outcomes accumulate without anyone sending requests by hand.
 * Every run is an ordinary run: same validation, budgets, audit row, and outcome scoring. One ticker's failure
 * never stops the others; the last run's summary is kept in memory and logged.
 */
@Component
@EnableScheduling
@Slf4j
@ConditionalOnProperty(name = "recommendation.schedule.enabled", havingValue = "true")
public class WatchlistScheduler {
    private final RecommendationService service;
    private final RecommendationProperties properties;
    private final Clock clock;
    private volatile WatchlistRun lastRun;

    @Autowired
    public WatchlistScheduler(RecommendationService service, RecommendationProperties properties) {
        this(service, properties, Clock.systemUTC());
    }
    WatchlistScheduler(RecommendationService service, RecommendationProperties properties, Clock clock) {
        this.service = service;
        this.properties = properties;
        this.clock = clock;
    }

    /** One ticker's result: the stored run, or the error class when no run was made (for example capacity, HTTP 429). */
    public record TickerResult(String ticker, String runId, String status, String assessment, BigDecimal confidence,
            BigDecimal calibratedConfidence, String critic, String error) { }
    public record WatchlistRun(String trigger, Instant startedAt, long elapsedMs, int tickers, int completed, int failed,
            List<TickerResult> results) { }

    // Read from the properties bean so contexts without application.yaml (tests) still start.
    @Scheduled(cron = "#{@recommendationProperties.schedule.cron}", zone = "#{@recommendationProperties.schedule.zone}")
    public void scheduled() { run("SCHEDULE"); }

    /** Sequential runs with the configured pause between tickers; a concurrent trigger waits for the current pass. */
    public synchronized WatchlistRun run(String trigger) {
        var schedule = properties.getSchedule();
        Instant started = clock.instant();
        var results = new ArrayList<TickerResult>();
        int failed = 0;
        List<String> tickers = schedule.getTickers();
        for (int i = 0; i < tickers.size(); i++) {
            String ticker = tickers.get(i).toUpperCase(java.util.Locale.ROOT);
            try {
                var response = service.recommend(new RecommendationRequest(ticker, schedule.getQuestion().replace("{ticker}", ticker),
                        null, schedule.isIncludePortfolio()));
                results.add(new TickerResult(ticker, response.runId(), response.status(), response.assessment(), response.confidence(),
                        response.calibratedConfidence(), response.critique() == null ? null : response.critique().verdict(), null));
                log.info("Watchlist {} ticker={} run={} status={} assessment={} confidence={} calibrated={}", trigger, ticker,
                        response.runId(), response.status(), response.assessment(), response.confidence(), response.calibratedConfidence());
            } catch (RuntimeException ex) {
                failed++;
                String error = ex instanceof ResponseStatusException status ? "HTTP_" + status.getStatusCode().value() : ex.getClass().getSimpleName();
                results.add(new TickerResult(ticker, null, null, null, null, null, null, error));
                log.warn("Watchlist {} ticker={} failed: {}", trigger, ticker, error);
            }
            if (i < tickers.size() - 1 && schedule.getPauseMs() > 0) {
                try { Thread.sleep(schedule.getPauseMs()); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
            }
        }
        var run = new WatchlistRun(trigger, started, Duration.between(started, clock.instant()).toMillis(), tickers.size(),
                results.size() - failed, failed, List.copyOf(results));
        lastRun = run;
        log.info("Watchlist {} finished: tickers={} completed={} failed={} elapsedMs={}", trigger, run.tickers(), run.completed(),
                run.failed(), run.elapsedMs());
        return run;
    }

    public Optional<WatchlistRun> lastRun() { return Optional.ofNullable(lastRun); }
}
