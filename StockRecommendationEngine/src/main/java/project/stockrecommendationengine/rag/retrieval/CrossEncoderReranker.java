package project.stockrecommendationengine.rag.retrieval;

import lombok.extern.slf4j.Slf4j;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Reranks retrieval candidates with a cross-encoder: every candidate's content is scored against the query by the
 * {@link PairScorer}, the candidates are ordered by score descending with ties kept in their input (fused) order, and the
 * first topK are returned as the same record instances, never altered. The scorer reads a chunk only as text to score, so a
 * chunk has no instruction channel here. A scorer exception, a score count that does not match the candidates, or a NaN
 * score is thrown as a {@link RuntimeException}, which {@link FilingRetrievalService} turns into the fused-order fallback.
 */
@Slf4j
public class CrossEncoderReranker implements FilingReranker {
    private final PairScorer scorer;
    private final String version;

    /** {@code version} names the exact model (the first 12 hex characters of its SHA-256); may be null. */
    public CrossEncoderReranker(PairScorer scorer, String version) {
        this.scorer = scorer;
        this.version = version;
    }

    @Override
    public List<RetrievedFilingChunk> rerank(String query, List<RetrievedFilingChunk> candidates, int topK) {
        if (candidates.isEmpty() || topK <= 0) return List.of();
        long started = System.nanoTime();
        List<String> passages = candidates.stream().map(c -> c.content() == null ? "" : c.content()).toList();
        float[] scores = scorer.score(query, passages);
        if (scores == null || scores.length != candidates.size()) {
            throw new IllegalStateException("Cross-encoder returned " + (scores == null ? "no" : scores.length)
                    + " scores for " + candidates.size() + " candidates");
        }
        for (float score : scores) {
            if (Float.isNaN(score)) throw new IllegalStateException("Cross-encoder returned a NaN score");
        }
        List<RetrievedFilingChunk> ordered = IntStream.range(0, candidates.size()).boxed()
                .sorted(Comparator.<Integer>comparingDouble(index -> scores[index]).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .limit(topK)
                .map(candidates::get)
                .toList();
        log.info("Cross-encoder scoring completed: candidates={}, topK={}, elapsedMs={}",
                candidates.size(), ordered.size(), (System.nanoTime() - started) / 1_000_000);
        return ordered;
    }

    @Override
    public String version() {
        return version;
    }
}
