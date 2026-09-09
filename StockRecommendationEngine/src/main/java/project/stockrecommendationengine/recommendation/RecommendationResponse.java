package project.stockrecommendationengine.recommendation;

import java.math.BigDecimal;
import java.util.List;
import project.stockrecommendationengine.broker.BrokerData.Quote;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;

public record RecommendationResponse(String runId, String ticker, String status, String assessment,
        String reasoning, List<RetrievedFilingChunk> sources, List<Quote> quotes,
        List<String> limitations, List<ToolTrace> toolTrace, int modelCalls, int observedTokens,
        BigDecimal takeProfit, BigDecimal stopLoss, BigDecimal confidence) {
    public record ToolTrace(String tool, String outcome, long elapsedMs) { }
}
