package project.stockrecommendationengine.rag.freshness;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly SEC index comparison for every stored ticker. One ticker's failure never stops the others. */
@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "rag.refresh.enabled", havingValue = "true")
public class FilingRefreshScheduler {
    private final FilingFreshnessService freshness;

    @Scheduled(cron = "${rag.refresh.cron}", zone = "${rag.refresh.zone}")
    public void nightly() { refreshAll(); }

    public List<FilingRefreshResult> refreshAll() {
        var tickers = freshness.storedTickers();
        long started = System.nanoTime();
        var results = new ArrayList<FilingRefreshResult>();
        int failures = 0;
        for (String ticker : tickers) {
            try { results.add(freshness.refresh(ticker)); }
            catch (RuntimeException ex) {
                failures++;
                log.warn("Scheduled filing refresh failed: ticker={} cause={}", ticker, ex.getClass().getSimpleName());
            }
        }
        log.info("Scheduled filing refresh finished: tickers={} refreshed={} failed={} elapsedMs={}",
                tickers.size(), results.size(), failures, (System.nanoTime() - started) / 1_000_000);
        return results;
    }
}
