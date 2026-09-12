package project.stockrecommendationengine.rag.freshness;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * What the store holds for a ticker and whether it should be trusted as current.
 * mayBeStale is true when the newest quarterly filing is past its cadence and no SEC index comparison
 * has confirmed the store within the recheck window.
 */
public record FilingFreshness(String ticker, Map<String, LocalDate> latestFilingDates, LocalDate newestQuarterly,
        boolean cadenceExceeded, Instant lastVerifiedAt, boolean mayBeStale) { }
