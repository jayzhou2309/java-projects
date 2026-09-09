package project.stockrecommendationengine.rag.retrieval;

import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import java.util.List;

/** Optional model adapter. Return a ranked subset without changing evidence or cosine scores. */
public interface FilingReranker {
    List<RetrievedFilingChunk> rerank(String query, List<RetrievedFilingChunk> candidates, int topK);
}
