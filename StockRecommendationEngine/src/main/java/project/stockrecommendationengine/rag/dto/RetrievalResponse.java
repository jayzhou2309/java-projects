package project.stockrecommendationengine.rag.dto;

import java.util.List;

public record RetrievalResponse(
        String ticker,
        String query,
        String retrievalStrategy,
        boolean latestFilingsOnly,
        int topK,
        int candidatesRetrieved,
        List<RetrievedFilingChunk> results
) {
}
