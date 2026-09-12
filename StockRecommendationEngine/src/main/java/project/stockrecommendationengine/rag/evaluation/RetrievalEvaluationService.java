package project.stockrecommendationengine.rag.evaluation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import project.stockrecommendationengine.rag.dto.RetrievalRequest;
import project.stockrecommendationengine.rag.dto.RetrievalResponse;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.Miss;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.QuestionResult;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.TopChunk;
import project.stockrecommendationengine.rag.retrieval.FilingRetrievalProperties;
import project.stockrecommendationengine.rag.retrieval.FilingRetrievalService;

/**
 * Runs the bundled evaluation set through retrieval and stores a snapshot. Each question is retrieved once with the
 * ticker, the question text as the query, the latest filings only, and a fixed window; the only external call is the
 * query embedding. A question's rank is the 1-based position of the first chunk that matches any expected passage
 * (same accession and section, content containing the phrase); a failed retrieval counts as a miss with the error
 * recorded, so one failure never voids the run. Nothing here reaches a chat model.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetrievalEvaluationService {
    static final int MISS_CHUNKS = 3;
    private static final int SCALE = 6;
    private final RetrievalEvaluationSetLoader loader;
    private final FilingRetrievalService retrieval;
    private final RetrievalEvaluationRepository repository;
    private final RetrievalEvaluationProperties properties;
    private final FilingRetrievalProperties retrievalProperties;

    /** Evaluate every question now with the configured retrieval default and store the snapshot; returns it with its id. */
    public RetrievalEvaluation evaluate() {
        return evaluate(null);
    }

    /**
     * Evaluate every question now and store the snapshot; returns it with its id. {@code hybrid} is passed unchanged on
     * every retrieval request (true or false forces the keyword plus vector path on or off for the whole run; null lets
     * each request follow {@code rag.retrieval.hybrid-enabled}) and recorded as {@code properties.hybrid}, null when absent.
     */
    public RetrievalEvaluation evaluate(Boolean hybrid) {
        RetrievalEvaluationSet set = loader.load();
        int window = properties.getWindow();
        List<QuestionResult> results = new ArrayList<>();
        List<Miss> misses = new ArrayList<>();
        String strategy = null;
        log.info("Evaluating retrieval: set={}, questions={}, window={}, hybrid={}", set.version(), set.questions().size(), window, hybrid);
        for (RetrievalEvaluationQuestion question : set.questions()) {
            RetrievalResponse response;
            try {
                response = retrieval.retrieve(new RetrievalRequest(question.ticker(), question.question(), null, null, null, null, window, true, hybrid));
            } catch (RuntimeException failure) {
                String error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                log.warn("Retrieval failed for evaluation question {}: {}", question.id(), error);
                results.add(new QuestionResult(question.id(), question.ticker(), question.kind(), null, null, error));
                misses.add(new Miss(question.id(), List.of(), error));
                continue;
            }
            if (strategy == null) strategy = response.retrievalStrategy();
            RetrievedFilingChunk matched = firstMatch(question, response.results(), window);
            Integer rank = matched == null ? null : response.results().indexOf(matched) + 1;
            results.add(new QuestionResult(question.id(), question.ticker(), question.kind(), rank, matched == null ? null : matched.chunkId(), null));
            if (matched == null) misses.add(new Miss(question.id(), response.results().stream().limit(MISS_CHUNKS).map(RetrievalEvaluationService::top).toList(), null));
        }
        RetrievalEvaluation evaluation = new RetrievalEvaluation(null, Instant.now(), set.version(), results.size(),
                hitAt(results, 1), hitAt(results, 3), hitAt(results, 5), mrr(results), window, strategy == null ? "UNAVAILABLE" : strategy,
                runProperties(set, window, hybrid), List.copyOf(results), tickerHitAt5(results), List.copyOf(misses));
        RetrievalEvaluation stored = repository.save(evaluation);
        log.info("Retrieval evaluation stored: id={}, hitAt1={}, hitAt3={}, hitAt5={}, mrr={}, misses={}",
                stored.id(), stored.hitAt1(), stored.hitAt3(), stored.hitAt5(), stored.mrr(), stored.misses().size());
        return stored;
    }

    /** The first chunk within the window matching any expected passage, or null. Package-private for tests. */
    static RetrievedFilingChunk firstMatch(RetrievalEvaluationQuestion question, List<RetrievedFilingChunk> chunks, int window) {
        for (RetrievedFilingChunk chunk : chunks.stream().limit(window).toList()) {
            for (ExpectedPassage passage : question.expected()) {
                if (matches(chunk, passage)) return chunk;
            }
        }
        return null;
    }

    /** Same accession and section, and the chunk text contains the phrase case-insensitively with whitespace collapsed. */
    static boolean matches(RetrievedFilingChunk chunk, ExpectedPassage passage) {
        return Objects.equals(chunk.accessionNo(), passage.accessionNo()) && Objects.equals(chunk.sectionKey(), passage.sectionKey())
                && chunk.content() != null && normalise(chunk.content()).contains(normalise(passage.phrase()));
    }

    static String normalise(String text) {
        return text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static BigDecimal hitAt(List<QuestionResult> results, int k) {
        long hits = results.stream().filter(r -> r.rank() != null && r.rank() <= k).count();
        return fraction(hits, results.size());
    }

    private static BigDecimal mrr(List<QuestionResult> results) {
        if (results.isEmpty()) return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal sum = BigDecimal.ZERO;
        for (QuestionResult result : results) {
            if (result.rank() != null) sum = sum.add(BigDecimal.ONE.divide(BigDecimal.valueOf(result.rank()), 12, RoundingMode.HALF_UP));
        }
        return sum.divide(BigDecimal.valueOf(results.size()), SCALE, RoundingMode.HALF_UP);
    }

    private static Map<String, BigDecimal> tickerHitAt5(List<QuestionResult> results) {
        Map<String, List<QuestionResult>> byTicker = new TreeMap<>();
        for (QuestionResult result : results) byTicker.computeIfAbsent(result.ticker(), t -> new ArrayList<>()).add(result);
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        byTicker.forEach((ticker, group) -> out.put(ticker, hitAt(group, 5)));
        return out;
    }

    private static BigDecimal fraction(long numerator, int denominator) {
        if (denominator == 0) return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), SCALE, RoundingMode.HALF_UP);
    }

    private static TopChunk top(RetrievedFilingChunk chunk) {
        return new TopChunk(chunk.chunkId(), chunk.accessionNo(), chunk.sectionKey(),
                BigDecimal.valueOf(chunk.similarityScore()).setScale(SCALE, RoundingMode.HALF_UP));
    }

    private Map<String, Object> runProperties(RetrievalEvaluationSet set, int window, Boolean hybrid) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window", window);
        out.put("latestFilingsOnly", true);
        out.put("setCreatedOn", set.createdOn().toString());
        out.put("candidateCount", retrievalProperties.getCandidateCount());
        out.put("rerankingEnabled", retrievalProperties.isRerankingEnabled());
        out.put("hybridEnabled", retrievalProperties.isHybridEnabled());
        out.put("keywordCandidateCount", retrievalProperties.getKeywordCandidateCount());
        out.put("rrfK", retrievalProperties.getRrfK());
        out.put("rrfVectorWeight", retrievalProperties.getRrfVectorWeight());
        out.put("rrfKeywordWeight", retrievalProperties.getRrfKeywordWeight());
        out.put("rrfFigureWeight", retrievalProperties.getRrfFigureWeight());
        out.put("hybrid", hybrid);
        return out;
    }
}
