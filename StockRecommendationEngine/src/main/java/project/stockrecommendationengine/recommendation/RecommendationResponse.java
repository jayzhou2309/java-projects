package project.stockrecommendationengine.recommendation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import project.stockrecommendationengine.broker.BrokerData.Quote;
import project.stockrecommendationengine.outcome.TrackRecord;
import project.stockrecommendationengine.quant.QuantAnalysis;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;

public record RecommendationResponse(String runId, String ticker, String status, String assessment,
        String reasoning, List<RetrievedFilingChunk> sources, List<Quote> quotes,
        List<String> limitations, List<ToolTrace> toolTrace, int modelCalls, int observedTokens,
        BigDecimal takeProfit, BigDecimal stopLoss, BigDecimal confidence, QuantAnalysis priceAnalysis,
        DataFreshness dataFreshness, TrackRecord trackRecord, Critique critique) {
    public record ToolTrace(String tool, String outcome, long elapsedMs) { }
    /**
     * The critic's final word on the answer returned: verdict ACCEPT, REVISE (issues stand; see CRITIC_UNRESOLVED),
     * or UNAVAILABLE; the issues of that last valid review; every review in order with the assessment it judged;
     * whether the manager revised; numerals in the reasoning not found in the run's evidence. Null when the critic
     * is disabled or the run stopped before an answer.
     */
    public record Critique(String verdict, List<String> issues, List<Review> reviews, boolean revised, List<String> unsupportedNumerals) { }
    /** One critic review: the draft assessment it judged and what it returned. */
    public record Review(String assessment, String verdict, List<String> issues) { }
    /** What the run actually saw: filing dates per type, the bar date behind the levels, and the quote timestamp. */
    public record DataFreshness(Map<String, LocalDate> latestFilingDates, Instant filingsVerifiedAt,
            boolean filingsMayBeStale, LocalDate barsAsOf, Instant quoteUpdatedAt, String quoteAvailability) { }
}
