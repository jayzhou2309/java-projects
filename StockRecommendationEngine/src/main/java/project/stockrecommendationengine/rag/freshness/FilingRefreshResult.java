package project.stockrecommendationengine.rag.freshness;

import java.time.Instant;
import java.util.List;

/**
 * Outcome of one SEC index comparison. newAccessions were absent or incomplete before; each was ingested on its
 * own, so ingestedAccessions and failedAccessions partition them.
 */
public record FilingRefreshResult(String ticker, Instant checkedAt, int indexFilings, List<String> newAccessions,
        List<String> ingestedAccessions, List<String> failedAccessions) {
    public boolean succeeded() { return failedAccessions.isEmpty(); }
}
