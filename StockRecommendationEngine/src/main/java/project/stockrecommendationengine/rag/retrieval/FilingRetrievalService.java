package project.stockrecommendationengine.rag.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import project.stockrecommendationengine.rag.dto.RetrievalRequest;
import project.stockrecommendationengine.rag.dto.RetrievalResponse;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.ingestion.FilingEmbeddingService;
import project.stockrecommendationengine.rag.repository.FilingRetrievalRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
public class FilingRetrievalService {
    /** Decimal places kept for a reciprocal rank term; equal rank multisets sum to identical values at this scale. */
    private static final int FUSION_SCALE = 18;

    private final FilingEmbeddingService embeddingService;
    private final FilingRetrievalRepository retrievalRepository;
    private final FilingRetrievalProperties retrievalProperties;
    private final Optional<FilingReranker> filingReranker;

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
        if (retrievalProperties.isRerankingEnabled() && filingReranker.isEmpty()) {
            throw new IllegalStateException("Reranking is enabled but no FilingReranker provider is configured");
        }
    }

    /**
     * Embeds the query, retrieves the vector candidates and, when hybrid retrieval resolves on (the request's
     * {@code hybrid} field when present, else {@code rag.retrieval.hybrid-enabled}) and the query yields at least
     * one keyword term, the full-text candidates as well; fuses the two rankings by reciprocal rank fusion, then
     * diversifies and cuts to topK. The strategy is {@code HYBRID_RRF} (or {@code HYBRID_RRF_RERANKED}) whenever
     * the keyword search ran and returned without error, an empty keyword result included: fusion ran over the
     * same input the caller asked for. It is {@code FILTERED_VECTOR} when hybrid is off, when the query has no
     * keyword terms (stopwords and punctuation only), or when the keyword search failed; a keyword failure is
     * logged at WARN with the exception class and never fails the retrieval.
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
        FilingRetrievalFilter retrievalFilter = new FilingRetrievalFilter(
                normalizedTicker, normalizeValues(request.filingTypes()),
                request.filingDateFrom(), request.filingDateTo(), normalizeValues(request.sectionKeys()),
                latestFilingsOnly);

        log.info("Retrieving filing evidence: ticker={}, topK={}, latestFilingsOnly={}, hybrid={}, filingTypes={}, dateFrom={}, dateTo={}, sections={}",
                normalizedTicker, requestedResultCount, latestFilingsOnly, hybridRequested, retrievalFilter.filingTypes(),
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
                    candidates = fuse(vectorCandidates, keywordCandidates, rrfK);
                    keywordContributed = true;
                    log.info("Reciprocal rank fusion completed: ticker={}, vectorCandidates={}, keywordCandidates={}, fused={}, k={}",
                            normalizedTicker, vectorCandidates.size(), keywordCandidates.size(), candidates.size(), rrfK);
                }
            }

            List<RetrievedFilingChunk> diverseCandidates = diversify(candidates);
            List<RetrievedFilingChunk> selectedEvidence;
            String retrievalStrategy = keywordContributed ? "HYBRID_RRF" : "FILTERED_VECTOR";
            if (retrievalProperties.isRerankingEnabled() && !candidates.isEmpty()) {
                log.info("Reranking filing candidates: ticker={}, candidates={}", normalizedTicker, candidates.size());
                selectedEvidence = filingReranker.orElseThrow().rerank(
                        normalizedQuery, List.copyOf(diverseCandidates), requestedResultCount);
                validateRerankedEvidence(diverseCandidates, selectedEvidence, requestedResultCount);
                retrievalStrategy = retrievalStrategy + "_RERANKED";
            } else {
                log.info("Selecting evidence by {} order: ticker={}, rerankingEnabled={}",
                        keywordContributed ? "fused" : "vector similarity", normalizedTicker,
                        retrievalProperties.isRerankingEnabled());
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
     * Reciprocal rank fusion of the two rankings: each chunk scores the sum over the lists containing it of
     * {@code 1 / (k + rank)}, rank being its 1-based position in that list; the fused list is ordered by that
     * score descending, then vector similarity descending, then chunk id ascending. A chunk found by only one
     * list is kept with the cosine similarity that list computed (both paths compute the same value), and a
     * chunk found by both keeps the vector list's instance. Package-private for tests.
     */
    static List<RetrievedFilingChunk> fuse(
            List<RetrievedFilingChunk> vectorCandidates,
            List<RetrievedFilingChunk> keywordCandidates,
            int k
    ) {
        Map<Long, RetrievedFilingChunk> chunksById = new LinkedHashMap<>();
        for (RetrievedFilingChunk candidate : vectorCandidates) chunksById.putIfAbsent(candidate.chunkId(), candidate);
        for (RetrievedFilingChunk candidate : keywordCandidates) chunksById.putIfAbsent(candidate.chunkId(), candidate);
        Map<Long, BigDecimal> fusedScores = reciprocalRankScores(List.of(vectorCandidates, keywordCandidates), k);
        List<RetrievedFilingChunk> fused = new ArrayList<>(chunksById.values());
        fused.sort(Comparator
                .comparing((RetrievedFilingChunk chunk) -> fusedScores.get(chunk.chunkId()), Comparator.reverseOrder())
                .thenComparing(RetrievedFilingChunk::similarityScore, Comparator.reverseOrder())
                .thenComparing(RetrievedFilingChunk::chunkId));
        return List.copyOf(fused);
    }

    /**
     * The reciprocal rank fusion score per chunk id over the given rankings, as exact decimals at a fixed scale
     * so that chunks holding the same ranks tie exactly. A chunk repeated within one ranking counts once, at its
     * first position, and does not shift the ranks of the chunks after it.
     */
    static Map<Long, BigDecimal> reciprocalRankScores(List<List<RetrievedFilingChunk>> rankings, int k) {
        Map<Long, BigDecimal> fusedScores = new LinkedHashMap<>();
        for (List<RetrievedFilingChunk> ranking : rankings) {
            Set<Long> seenInRanking = new HashSet<>();
            int rank = 0;
            for (RetrievedFilingChunk candidate : ranking) {
                if (!seenInRanking.add(candidate.chunkId())) continue;
                rank++;
                BigDecimal reciprocalRank = BigDecimal.ONE.divide(
                        BigDecimal.valueOf((long) k + rank), FUSION_SCALE, RoundingMode.HALF_EVEN);
                fusedScores.merge(candidate.chunkId(), reciprocalRank, BigDecimal::add);
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
