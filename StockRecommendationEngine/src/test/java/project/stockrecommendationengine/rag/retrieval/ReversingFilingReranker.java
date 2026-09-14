package project.stockrecommendationengine.rag.retrieval;

import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Test-only reranker: reverses the candidates it is given and returns the first topK, recording its last input. */
class ReversingFilingReranker implements FilingReranker {
    volatile List<RetrievedFilingChunk> lastCandidates;
    volatile int calls;

    @Override
    public List<RetrievedFilingChunk> rerank(String query, List<RetrievedFilingChunk> candidates, int topK) {
        calls++;
        lastCandidates = candidates;
        List<RetrievedFilingChunk> reversed = new ArrayList<>(candidates);
        Collections.reverse(reversed);
        return List.copyOf(reversed.subList(0, Math.min(topK, reversed.size())));
    }
}
