package project.stockrecommendationengine.recommendation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** One stored run. Version tags identify the prompt, model, quant parameters, and filing processing in use. */
public record RecommendationRecord(String runId, String ticker, Long conid, Instant requestedAt, Instant completedAt,
        String question, String status, String assessment, BigDecimal takeProfit, BigDecimal stopLoss,
        BigDecimal confidence, BigDecimal lastClose, LocalDate barsAsOf, String quoteAvailability,
        List<Long> citedChunkIds, List<String> limitations, int modelCalls, int observedTokens,
        String promptVersion, String model, String quantVersion, String processingVersion, String responseJson) { }
