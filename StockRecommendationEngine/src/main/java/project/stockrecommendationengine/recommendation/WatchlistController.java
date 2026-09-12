package project.stockrecommendationengine.recommendation;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** Inspect and trigger the watchlist; protected by the integration access token. */
@RestController
@RequestMapping("/api/recommendations/watchlist")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "recommendation.schedule.enabled", havingValue = "true")
public class WatchlistController {
    private final WatchlistScheduler scheduler;
    private final RecommendationProperties properties;

    public record WatchlistView(List<String> tickers, String cron, String zone, String question, int pauseMs, boolean includePortfolio,
            WatchlistScheduler.WatchlistRun lastRun) { }

    @GetMapping
    public WatchlistView view() {
        var s = properties.getSchedule();
        return new WatchlistView(s.getTickers(), s.getCron(), s.getZone(), s.getQuestion(), s.getPauseMs(), s.isIncludePortfolio(),
                scheduler.lastRun().orElse(null));
    }

    /** Run the whole watchlist now, sequentially; returns when the last ticker has finished. */
    @PostMapping("/run")
    public WatchlistScheduler.WatchlistRun run() { return scheduler.run("MANUAL"); }

    @GetMapping("/last")
    public Map<String, Object> last() {
        return Map.of("lastRun", scheduler.lastRun().map(Object.class::cast).orElse(Map.of()));
    }
}
