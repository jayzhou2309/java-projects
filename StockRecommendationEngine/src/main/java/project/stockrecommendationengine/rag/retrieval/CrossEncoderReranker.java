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
 * The completion log line carries {@code windows=}, the model rows the scorer ran for the call (the candidate count under
 * head scoring, more under windowed scoring).
 * <p>
 * {@link #rerank} and {@link #rerankScored} run the same single scoring call and the same ordering, so their results are
 * identical for the same input; the scored form, the one retrieval calls, also returns every candidate's score and row scores in
 * reranked order.
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
        return rank(query, candidates, topK).results();
    }

    /**
     * {@link #rerank} plus, for every candidate in reranked order, its index in the input, its score, and its row scores as
     * the scorer reported them ({@link PairScorer.Scored#windowScores}; null for every candidate when the scorer reports none or
     * reports a count that does not match the candidates). Empty for no candidates or a topK of 0 or less, without scoring.
     */
    @Override
    public ScoredReranking rerankScored(String query, List<RetrievedFilingChunk> candidates, int topK) {
        return rank(query, candidates, topK);
    }

    private ScoredReranking rank(String query, List<RetrievedFilingChunk> candidates, int topK) {
        if (candidates.isEmpty() || topK <= 0) return new ScoredReranking(List.of(), List.of());
        long started = System.nanoTime();
        List<String> passages = candidates.stream().map(c -> c.content() == null ? "" : c.content()).toList();
        PairScorer.Scored scored = scorer.scoreWithWindows(query, passages);
        float[] scores = scored == null ? null : scored.scores();
        if (scores == null || scores.length != candidates.size()) {
            throw new IllegalStateException("Cross-encoder returned " + (scores == null ? "no" : scores.length)
                    + " scores for " + candidates.size() + " candidates");
        }
        for (float score : scores) {
            if (Float.isNaN(score)) throw new IllegalStateException("Cross-encoder returned a NaN score");
        }
        float[][] windowScores = scored.windowScores() != null && scored.windowScores().length == candidates.size() ? scored.windowScores() : null;
        List<ScoredCandidate> order = IntStream.range(0, candidates.size()).boxed()
                .sorted(Comparator.<Integer>comparingDouble(index -> scores[index]).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .map(index -> new ScoredCandidate(index, scores[index], windowScores == null ? null : windowScores[index]))
                .toList();
        List<RetrievedFilingChunk> ordered = order.stream().limit(topK).map(candidate -> candidates.get(candidate.inputIndex())).toList();
        log.info("Cross-encoder scoring completed: candidates={}, windows={}, topK={}, elapsedMs={}",
                candidates.size(), scored.windows(), ordered.size(), (System.nanoTime() - started) / 1_000_000);
        return new ScoredReranking(ordered, order);
    }

    @Override
    public String version() {
        return version;
    }

    /** The scorer's {@link PairScorer#scoring()}. */
    @Override
    public String scoring() {
        return scorer.scoring();
    }
}
