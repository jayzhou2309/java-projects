package project.stockrecommendationengine.rag.retrieval;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;
import project.stockrecommendationengine.rag.dto.RetrievalRequest;
import project.stockrecommendationengine.rag.dto.RetrievalResponse;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.ingestion.FilingEmbeddingService;
import project.stockrecommendationengine.rag.repository.FilingRetrievalRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class FilingRetrievalService {
    /** Decimal places kept for a reciprocal rank term; equal rank multisets sum to identical values at this scale. */
    private static final int FUSION_SCALE = 18;

    private final FilingEmbeddingService embeddingService;
    private final FilingRetrievalRepository retrievalRepository;
    private final FilingRetrievalProperties retrievalProperties;
    private final Optional<FilingReranker> filingReranker;
    /** Runs reranker calls so each can be bounded by {@code rerank-timeout-ms}; null when no reranker bean exists. */
    private final ExecutorService rerankExecutor;

    public FilingRetrievalService(
            FilingEmbeddingService embeddingService,
            FilingRetrievalRepository retrievalRepository,
            FilingRetrievalProperties retrievalProperties,
            Optional<FilingReranker> filingReranker
    ) {
        this.embeddingService = embeddingService;
        this.retrievalRepository = retrievalRepository;
        this.retrievalProperties = retrievalProperties;
        this.filingReranker = filingReranker;
        this.rerankExecutor = filingReranker.isPresent() ? newRerankExecutor() : null;
        if (retrievalProperties.isRerankingEnabled() && filingReranker.isEmpty()) {
            throw new IllegalStateException("Reranking is enabled but no FilingReranker provider is configured");
        }
    }

    /**
     * A small bounded pool of daemon threads for reranker calls: two threads that time out when idle and a queue of 16;
     * a call rejected because the pool is saturated falls back like any other reranker failure.
     */
    private static ExecutorService newRerankExecutor() {
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
            Thread thread = new Thread(task, "filing-reranker-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @PreDestroy
    public void shutdownRerankExecutor() {
        if (rerankExecutor != null) rerankExecutor.shutdownNow();
    }

    /**
     * The configured reranker's simple class name, taken from the user class so a CGLIB or other proxy suffix never appears,
     * or empty when no {@link FilingReranker} bean exists.
     */
    public Optional<String> rerankerName() {
        return filingReranker.map(reranker -> ClassUtils.getUserClass(reranker).getSimpleName());
    }

    /** The configured reranker's {@link FilingReranker#version()}, or empty when there is no reranker or it reports none. */
    public Optional<String> rerankerVersion() {
        return filingReranker.map(FilingReranker::version);
    }

    /**
     * Embeds the query, retrieves the vector candidates and, when hybrid retrieval resolves on (the request's
     * {@code hybrid} field when present, else {@code rag.retrieval.hybrid-enabled}) and the query yields at least
     * one keyword term, the full-text candidates as well; when {@code rag.retrieval.rrf-figure-weight} is above 0
     * and the query holds a figure (see {@code figureTerms}), the figure candidates too; fuses the rankings by
     * weighted reciprocal rank fusion, then diversifies and cuts to topK. The strategy is {@code HYBRID_RRF} (or
     * {@code HYBRID_RRF_RERANKED}) whenever the keyword search ran and returned without error, an empty keyword
     * result included and whether or not the figure leg ran: fusion ran over the same input the caller asked for.
     * It is {@code FILTERED_VECTOR} when hybrid is off, when the query has no keyword terms (stopwords and
     * punctuation only), or when the keyword search failed; a keyword failure is logged at WARN with the exception
     * class and never fails the retrieval. A figure-leg failure is logged the same way and fusion proceeds over the
     * vector and keyword legs.
     * <p>
     * Reranking runs when the request's {@code rerank} field is true, or it is absent and
     * {@code rag.retrieval.reranking-enabled} is on; {@code rerank: true} with no reranker bean throws
     * {@link RerankerUnavailableException} before any search (HTTP 400). The reranker receives the first
     * max({@code rerank-candidates}, topK) of the fused, diversified list, so a reranked response never holds fewer chunks
     * than the fused one would, and waits at most {@code rerank-timeout-ms}; its result is validated against that input (topK
     * at most, no duplicate, altered, or invented chunk). A timeout, a reranker {@link RuntimeException} (including a
     * rejected submission), an interruption, or a result failing validation is logged at WARN with the exception class only,
     * and retrieval returns the fused order cut to topK with the strategy not suffixed {@code _RERANKED}. An {@link Error}
     * thrown by the reranker (for example {@link OutOfMemoryError} or a native library {@link LinkageError}) is not a
     * fallback case: it propagates and fails the retrieval.
     */
    public RetrievalResponse retrieve(RetrievalRequest request) {
        long retrievalStarted = System.nanoTime();
        String normalizedTicker = request.ticker().trim().toUpperCase(Locale.ROOT);
        String normalizedQuery = request.query().trim();
        int requestedResultCount = request.topK() == null ? retrievalProperties.getDefaultTopK() : request.topK();
        boolean latestFilingsOnly = request.latestFilingsOnly() != null
                ? request.latestFilingsOnly()
                : request.filingDateFrom() == null && request.filingDateTo() == null
                    && retrievalProperties.isLatestFilingsOnly();
        boolean hybridRequested = request.hybrid() != null ? request.hybrid() : retrievalProperties.isHybridEnabled();
        boolean rerankRequested = request.rerank() != null ? request.rerank() : retrievalProperties.isRerankingEnabled();
        if (rerankRequested && filingReranker.isEmpty()) {
            throw new RerankerUnavailableException();
        }
        FilingRetrievalFilter retrievalFilter = new FilingRetrievalFilter(
                normalizedTicker, normalizeValues(request.filingTypes()),
                request.filingDateFrom(), request.filingDateTo(), normalizeValues(request.sectionKeys()),
                latestFilingsOnly);

        log.info("Retrieving filing evidence: ticker={}, topK={}, latestFilingsOnly={}, hybrid={}, rerank={}, filingTypes={}, dateFrom={}, dateTo={}, sections={}",
                normalizedTicker, requestedResultCount, latestFilingsOnly, hybridRequested, rerankRequested, retrievalFilter.filingTypes(),
                retrievalFilter.filingDateFrom(), retrievalFilter.filingDateTo(), retrievalFilter.sectionKeys());
        try {
            long embeddingStarted = System.nanoTime();
            log.info("Embedding retrieval query: ticker={}, characters={}", normalizedTicker, normalizedQuery.length());
            float[] queryEmbedding = embeddingService.embed(normalizedQuery);
            log.info("Query embedding completed: ticker={}, elapsedMs={}", normalizedTicker, elapsedMillis(embeddingStarted));

            long searchStarted = System.nanoTime();
            log.info("Searching eligible filing chunks: ticker={}, candidateLimit={}",
                    normalizedTicker, retrievalProperties.getCandidateCount());
            List<RetrievedFilingChunk> vectorCandidates = retrievalRepository.findSimilarChunks(
                    queryEmbedding, retrievalFilter, retrievalProperties.getCandidateCount());
            log.info("Vector search completed: ticker={}, candidates={}, elapsedMs={}",
                    normalizedTicker, vectorCandidates.size(), elapsedMillis(searchStarted));

            List<RetrievedFilingChunk> candidates = vectorCandidates;
            boolean keywordContributed = false;
            if (hybridRequested) {
                List<RetrievedFilingChunk> keywordCandidates = searchKeywords(
                        normalizedTicker, normalizedQuery, queryEmbedding, retrievalFilter);
                if (keywordCandidates != null) {
                    int rrfK = retrievalProperties.getRrfK();
                    List<FusionLeg> legs = new ArrayList<>(List.of(
                            new FusionLeg(vectorCandidates, retrievalProperties.getRrfVectorWeight()),
                            new FusionLeg(keywordCandidates, retrievalProperties.getRrfKeywordWeight())));
                    List<RetrievedFilingChunk> figureCandidates = searchFigures(
                            normalizedTicker, normalizedQuery, queryEmbedding, retrievalFilter);
                    if (figureCandidates != null) {
                        legs.add(new FusionLeg(figureCandidates, retrievalProperties.getRrfFigureWeight()));
                    }
                    candidates = fuse(legs, rrfK);
                    keywordContributed = true;
                    log.info("Reciprocal rank fusion completed: ticker={}, vectorCandidates={}, keywordCandidates={}, figureCandidates={}, fused={}, k={}, weights={}/{}/{}",
                            normalizedTicker, vectorCandidates.size(), keywordCandidates.size(),
                            figureCandidates == null ? "off" : figureCandidates.size(), candidates.size(), rrfK,
                            retrievalProperties.getRrfVectorWeight(), retrievalProperties.getRrfKeywordWeight(),
                            retrievalProperties.getRrfFigureWeight());
                }
            }

            List<RetrievedFilingChunk> diverseCandidates = diversify(candidates);
            List<RetrievedFilingChunk> selectedEvidence = null;
            String retrievalStrategy = keywordContributed ? "HYBRID_RRF" : "FILTERED_VECTOR";
            if (rerankRequested && !diverseCandidates.isEmpty()) {
                List<RetrievedFilingChunk> rerankInput = List.copyOf(diverseCandidates.subList(
                        0, Math.min(diverseCandidates.size(),
                                Math.max(retrievalProperties.getRerankCandidates(), requestedResultCount))));
                selectedEvidence = rerank(normalizedTicker, normalizedQuery, rerankInput, requestedResultCount);
                if (selectedEvidence != null) retrievalStrategy = retrievalStrategy + "_RERANKED";
            }
            if (selectedEvidence == null) {
                log.info("Selecting evidence by {} order: ticker={}, rerank={}",
                        keywordContributed ? "fused" : "vector similarity", normalizedTicker, rerankRequested);
                selectedEvidence = diverseCandidates.stream().limit(requestedResultCount).toList();
            }
            log.info("Retrieval completed: ticker={}, results={}, strategy={}, elapsedMs={}",
                    normalizedTicker, selectedEvidence.size(), retrievalStrategy, elapsedMillis(retrievalStarted));
            return new RetrievalResponse(normalizedTicker, normalizedQuery, retrievalStrategy,
                    latestFilingsOnly, requestedResultCount, candidates.size(), List.copyOf(selectedEvidence));
        } catch (RuntimeException retrievalFailure) {
            log.error("Retrieval failed: ticker={}, elapsedMs={}",
                    normalizedTicker, elapsedMillis(retrievalStarted), retrievalFailure);
            throw retrievalFailure;
        }
    }

    /**
     * The reranker's validated order over {@code rerankInput}, or {@code null} when it must not be used: the call timed out
     * (the future is cancelled), threw, was rejected by the saturated pool, or returned evidence failing
     * {@link #validateRerankedEvidence}. Each case logs one WARN naming the exception class only, never its message.
     */
    private List<RetrievedFilingChunk> rerank(
            String normalizedTicker,
            String normalizedQuery,
            List<RetrievedFilingChunk> rerankInput,
            int requestedResultCount
    ) {
        long rerankStarted = System.nanoTime();
        long timeoutMs = retrievalProperties.getRerankTimeoutMs();
        FilingReranker reranker = filingReranker.orElseThrow();
        log.info("Reranking filing candidates: ticker={}, candidates={}, timeoutMs={}", normalizedTicker, rerankInput.size(), timeoutMs);
        Future<List<RetrievedFilingChunk>> pending = null;
        String reason;
        Throwable failure;
        try {
            pending = rerankExecutor.submit(() -> reranker.rerank(normalizedQuery, rerankInput, requestedResultCount));
            List<RetrievedFilingChunk> reranked = pending.get(timeoutMs, TimeUnit.MILLISECONDS);
            try {
                validateRerankedEvidence(rerankInput, reranked, requestedResultCount);
            } catch (IllegalStateException invalid) {
                warnRerankFallback(normalizedTicker, "invalidEvidence", invalid, rerankStarted);
                return null;
            }
            log.info("Reranking completed: ticker={}, results={}, elapsedMs={}", normalizedTicker, reranked.size(), elapsedMillis(rerankStarted));
            return List.copyOf(reranked);
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            reason = "timeout";
            failure = timeout;
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            reason = "interrupted";
            failure = interrupted;
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause() == null ? execution : execution.getCause();
            if (cause instanceof Error error) throw error;
            reason = "failure";
            failure = cause;
        } catch (RuntimeException rejected) {
            reason = "failure";
            failure = rejected;
        }
        warnRerankFallback(normalizedTicker, reason, failure, rerankStarted);
        return null;
    }

    private void warnRerankFallback(String normalizedTicker, String reason, Throwable failure, long rerankStarted) {
        log.warn("Reranking failed, keeping the retrieval order: ticker={}, reason={}, error={}, elapsedMs={}",
                normalizedTicker, reason, failure.getClass().getSimpleName(), elapsedMillis(rerankStarted));
    }

    /**
     * The keyword candidates for the query, or {@code null} when the keyword path did not run: no keyword term
     * remains after tokenising (the repository is not called) or the search threw (logged at WARN with the
     * exception class only, so a failing index never fails the retrieval).
     */
    private List<RetrievedFilingChunk> searchKeywords(
            String normalizedTicker,
            String normalizedQuery,
            float[] queryEmbedding,
            FilingRetrievalFilter retrievalFilter
    ) {
        if (FilingRetrievalRepository.keywordTerms(normalizedQuery).isEmpty()) {
            log.info("Skipping keyword search: ticker={}, reason=noKeywordTerms", normalizedTicker);
            return null;
        }
        long keywordStarted = System.nanoTime();
        int keywordCandidateCount = retrievalProperties.getKeywordCandidateCount();
        log.info("Searching filing chunks by keyword: ticker={}, candidateLimit={}", normalizedTicker, keywordCandidateCount);
        try {
            List<RetrievedFilingChunk> keywordCandidates = retrievalRepository.findKeywordChunks(
                    normalizedQuery, queryEmbedding, retrievalFilter, keywordCandidateCount);
            log.info("Keyword search completed: ticker={}, candidates={}, elapsedMs={}",
                    normalizedTicker, keywordCandidates.size(), elapsedMillis(keywordStarted));
            return keywordCandidates;
        } catch (RuntimeException keywordFailure) {
            log.warn("Keyword search failed, falling back to vector candidates only: ticker={}, error={}, elapsedMs={}",
                    normalizedTicker, keywordFailure.getClass().getSimpleName(), elapsedMillis(keywordStarted));
            return null;
        }
    }

    /**
     * The figure candidates for the query, or {@code null} when the figure leg did not run: the figure weight is 0
     * (the leg is off, the default), the query holds no figure (no numeric token, or years only; the repository is
     * not called), or the search threw (logged at WARN with the exception class only; fusion then proceeds over
     * the vector and keyword legs). Called only once the keyword leg has succeeded: a non-empty figure string
     * implies a non-empty keyword string, so the figure leg never runs on its own.
     */
    private List<RetrievedFilingChunk> searchFigures(
            String normalizedTicker,
            String normalizedQuery,
            float[] queryEmbedding,
            FilingRetrievalFilter retrievalFilter
    ) {
        if (retrievalProperties.getRrfFigureWeight() <= 0) {
            return null;
        }
        if (FilingRetrievalRepository.figureTerms(normalizedQuery).isEmpty()) {
            log.info("Skipping figure search: ticker={}, reason=noFigureTerms", normalizedTicker);
            return null;
        }
        long figureStarted = System.nanoTime();
        int figureCandidateCount = retrievalProperties.getKeywordCandidateCount();
        log.info("Searching filing chunks by figure: ticker={}, candidateLimit={}", normalizedTicker, figureCandidateCount);
        try {
            List<RetrievedFilingChunk> figureCandidates = retrievalRepository.findFigureChunks(
                    normalizedQuery, queryEmbedding, retrievalFilter, figureCandidateCount);
            log.info("Figure search completed: ticker={}, candidates={}, elapsedMs={}",
                    normalizedTicker, figureCandidates.size(), elapsedMillis(figureStarted));
            return figureCandidates;
        } catch (RuntimeException figureFailure) {
            log.warn("Figure search failed, fusing vector and keyword candidates only: ticker={}, error={}, elapsedMs={}",
                    normalizedTicker, figureFailure.getClass().getSimpleName(), elapsedMillis(figureStarted));
            return null;
        }
    }

    /** One ranking entering reciprocal rank fusion with its weight: a chunk at rank r in it scores weight / (k + r). */
    record FusionLeg(List<RetrievedFilingChunk> ranking, double weight) {}

    /** Unweighted fusion of the vector and keyword rankings: {@link #fuse(List, int)} with both weights 1.0. */
    static List<RetrievedFilingChunk> fuse(
            List<RetrievedFilingChunk> vectorCandidates,
            List<RetrievedFilingChunk> keywordCandidates,
            int k
    ) {
        return fuse(List.of(new FusionLeg(vectorCandidates, 1.0), new FusionLeg(keywordCandidates, 1.0)), k);
    }

    /**
     * Weighted reciprocal rank fusion of the legs: each chunk scores the sum over the legs containing it of
     * {@code weight / (k + rank)}, rank being its 1-based position in that leg; the fused list is ordered by that
     * score descending, then vector similarity descending, then chunk id ascending. A chunk found by only one
     * leg is kept with the cosine similarity that leg computed (every path computes the same value), and a chunk
     * found by several keeps the instance from the first leg listing it (the vector leg comes first). With every
     * weight 1.0 the result equals the unweighted fusion exactly. Package-private for tests.
     */
    static List<RetrievedFilingChunk> fuse(List<FusionLeg> legs, int k) {
        Map<Long, RetrievedFilingChunk> chunksById = new LinkedHashMap<>();
        for (FusionLeg leg : legs) {
            for (RetrievedFilingChunk candidate : leg.ranking()) chunksById.putIfAbsent(candidate.chunkId(), candidate);
        }
        Map<Long, BigDecimal> fusedScores = reciprocalRankScores(
                legs.stream().map(FusionLeg::ranking).toList(), legs.stream().map(FusionLeg::weight).toList(), k);
        List<RetrievedFilingChunk> fused = new ArrayList<>(chunksById.values());
        fused.sort(Comparator
                .comparing((RetrievedFilingChunk chunk) -> fusedScores.get(chunk.chunkId()), Comparator.reverseOrder())
                .thenComparing(RetrievedFilingChunk::similarityScore, Comparator.reverseOrder())
                .thenComparing(RetrievedFilingChunk::chunkId));
        return List.copyOf(fused);
    }

    /** The unweighted scores: {@link #reciprocalRankScores(List, List, int)} with every weight 1.0. */
    static Map<Long, BigDecimal> reciprocalRankScores(List<List<RetrievedFilingChunk>> rankings, int k) {
        return reciprocalRankScores(rankings, Collections.nCopies(rankings.size(), 1.0), k);
    }

    /**
     * The weighted reciprocal rank fusion score per chunk id over the given rankings, one weight per ranking:
     * each term is {@code weight / (k + rank)} as one division at a fixed scale, so chunks holding the same
     * ranks under the same weights tie exactly, and a weight of 1.0 yields the unweighted term digit for digit.
     * A chunk repeated within one ranking counts once, at its first position, and does not shift the ranks of
     * the chunks after it.
     */
    static Map<Long, BigDecimal> reciprocalRankScores(
            List<List<RetrievedFilingChunk>> rankings,
            List<Double> weights,
            int k
    ) {
        if (weights.size() != rankings.size()) {
            throw new IllegalArgumentException("Reciprocal rank fusion needs one weight per ranking");
        }
        Map<Long, BigDecimal> fusedScores = new LinkedHashMap<>();
        for (int index = 0; index < rankings.size(); index++) {
            BigDecimal weight = BigDecimal.valueOf(weights.get(index));
            Set<Long> seenInRanking = new HashSet<>();
            int rank = 0;
            for (RetrievedFilingChunk candidate : rankings.get(index)) {
                if (!seenInRanking.add(candidate.chunkId())) continue;
                rank++;
                BigDecimal weightedReciprocalRank = weight.divide(
                        BigDecimal.valueOf((long) k + rank), FUSION_SCALE, RoundingMode.HALF_EVEN);
                fusedScores.merge(candidate.chunkId(), weightedReciprocalRank, BigDecimal::add);
            }
        }
        return fusedScores;
    }

    private List<RetrievedFilingChunk> diversify(List<RetrievedFilingChunk> candidates) {
        List<RetrievedFilingChunk> selected = new java.util.ArrayList<>();
        for (var candidate : candidates) {
            boolean redundant = selected.stream().anyMatch(existing ->
                    existing.filingId().equals(candidate.filingId())
                    && java.util.Objects.equals(existing.sectionKey(), candidate.sectionKey())
                    && java.util.Objects.equals(existing.sectionTitle(), candidate.sectionTitle())
                    && redundantText(existing.content(), candidate.content()));
            if (!redundant) selected.add(candidate);
        }
        return selected;
    }

    private boolean redundantText(String first, String second) {
        String a = first.replaceAll("\\s+", " ").trim();
        String b = second.replaceAll("\\s+", " ").trim();
        if (a.equals(b)) return true;
        // Preserve ordinary 500-character context overlap; suppress only substantial
        // overlap covering at least half the shorter passage (minimum 200 chars).
        int minimum = Math.max(200, (Math.min(a.length(), b.length()) + 1) / 2);
        if (Math.min(a.length(), b.length()) < minimum) return false;
        if (a.contains(b) || b.contains(a)) return true;
        for (int size = Math.min(a.length(), b.length()); size >= minimum; size--) {
            if (a.regionMatches(a.length() - size, b, 0, size)
                    || b.regionMatches(b.length() - size, a, 0, size)) return true;
        }
        return false;
    }

    private List<String> normalizeValues(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(value -> value.trim().toUpperCase(Locale.ROOT)).distinct().toList();
    }

    private void validateRerankedEvidence(
            List<RetrievedFilingChunk> candidates,
            List<RetrievedFilingChunk> selectedEvidence,
            int requestedResultCount
    ) {
        if (selectedEvidence == null || selectedEvidence.size() > requestedResultCount) {
            throw new IllegalStateException("Reranker returned an invalid result count");
        }
        Set<RetrievedFilingChunk> allowedEvidence = new HashSet<>(candidates);
        Set<Long> returnedChunkIds = new HashSet<>();
        for (RetrievedFilingChunk evidence : selectedEvidence) {
            if (!allowedEvidence.contains(evidence) || !returnedChunkIds.add(evidence.chunkId())) {
                throw new IllegalStateException("Reranker returned duplicate or altered evidence");
            }
        }
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
