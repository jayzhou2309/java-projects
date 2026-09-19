package project.stockrecommendationengine.recommendation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One stored run. Version tags identify the prompt, model, quant parameters, and filing processing in use. The purpose
 * says whether the run is product data (USER) or a measurement of the loop (EVALUATION).
 */
public record RecommendationRecord(String runId, String ticker, Long conid, Instant requestedAt, Instant completedAt,
        String question, String status, String assessment, BigDecimal takeProfit, BigDecimal stopLoss,
        BigDecimal confidence, BigDecimal lastClose, LocalDate barsAsOf, String quoteAvailability,
        List<Long> citedChunkIds, List<String> limitations, int modelCalls, int observedTokens,
        String promptVersion, String model, String quantVersion, String processingVersion, String responseJson,
        RunPurpose purpose) {
    public RecommendationRecord {
        java.util.Objects.requireNonNull(purpose, "purpose");
    }

    /** A user run; evaluation runs are only built by the service with an explicit purpose. */
    public RecommendationRecord(String runId, String ticker, Long conid, Instant requestedAt, Instant completedAt,
            String question, String status, String assessment, BigDecimal takeProfit, BigDecimal stopLoss,
            BigDecimal confidence, BigDecimal lastClose, LocalDate barsAsOf, String quoteAvailability,
            List<Long> citedChunkIds, List<String> limitations, int modelCalls, int observedTokens,
            String promptVersion, String model, String quantVersion, String processingVersion, String responseJson) {
        this(runId, ticker, conid, requestedAt, completedAt, question, status, assessment, takeProfit, stopLoss, confidence,
                lastClose, barsAsOf, quoteAvailability, citedChunkIds, limitations, modelCalls, observedTokens, promptVersion,
                model, quantVersion, processingVersion, responseJson, RunPurpose.USER);
    }
}
