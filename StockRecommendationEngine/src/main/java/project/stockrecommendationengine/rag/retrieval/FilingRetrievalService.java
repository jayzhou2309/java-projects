package project.stockrecommendationengine.rag.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import project.stockrecommendationengine.rag.dto.RetrievalRequest;
import project.stockrecommendationengine.rag.dto.RetrievalResponse;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.ingestion.FilingEmbeddingService;
import project.stockrecommendationengine.rag.repository.FilingRetrievalRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
public class FilingRetrievalService {
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

    public RetrievalResponse retrieve(RetrievalRequest request) {
        long retrievalStarted = System.nanoTime();
        String normalizedTicker = request.ticker().trim().toUpperCase(Locale.ROOT);
        String normalizedQuery = request.query().trim();
        int requestedResultCount = request.topK() == null ? retrievalProperties.getDefaultTopK() : request.topK();
        boolean latestFilingsOnly = request.latestFilingsOnly() != null
                ? request.latestFilingsOnly()
                : request.filingDateFrom() == null && request.filingDateTo() == null
                    && retrievalProperties.isLatestFilingsOnly();
        FilingRetrievalFilter retrievalFilter = new FilingRetrievalFilter(
                normalizedTicker, normalizeValues(request.filingTypes()),
                request.filingDateFrom(), request.filingDateTo(), normalizeValues(request.sectionKeys()),
                latestFilingsOnly);

        log.info("Retrieving filing evidence: ticker={}, topK={}, latestFilingsOnly={}, filingTypes={}, dateFrom={}, dateTo={}, sections={}",
                normalizedTicker, requestedResultCount, latestFilingsOnly, retrievalFilter.filingTypes(),
                retrievalFilter.filingDateFrom(), retrievalFilter.filingDateTo(), retrievalFilter.sectionKeys());
        try {
            long embeddingStarted = System.nanoTime();
            log.info("Embedding retrieval query: ticker={}, characters={}", normalizedTicker, normalizedQuery.length());
            float[] queryEmbedding = embeddingService.embed(normalizedQuery);
            log.info("Query embedding completed: ticker={}, elapsedMs={}", normalizedTicker, elapsedMillis(embeddingStarted));

            long searchStarted = System.nanoTime();
            log.info("Searching eligible filing chunks: ticker={}, candidateLimit={}",
                    normalizedTicker, retrievalProperties.getCandidateCount());
            List<RetrievedFilingChunk> candidates = retrievalRepository.findSimilarChunks(
                    queryEmbedding, retrievalFilter, retrievalProperties.getCandidateCount());
            log.info("Vector search completed: ticker={}, candidates={}, elapsedMs={}",
                    normalizedTicker, candidates.size(), elapsedMillis(searchStarted));

            List<RetrievedFilingChunk> diverseCandidates = diversify(candidates);
            List<RetrievedFilingChunk> selectedEvidence;
            String retrievalStrategy = "FILTERED_VECTOR";
            if (retrievalProperties.isRerankingEnabled() && !candidates.isEmpty()) {
                log.info("Reranking filing candidates: ticker={}, candidates={}", normalizedTicker, candidates.size());
                selectedEvidence = filingReranker.orElseThrow().rerank(
                        normalizedQuery, List.copyOf(diverseCandidates), requestedResultCount);
                validateRerankedEvidence(diverseCandidates, selectedEvidence, requestedResultCount);
                retrievalStrategy = "FILTERED_VECTOR_RERANKED";
            } else {
                log.info("Selecting evidence by vector similarity: ticker={}, rerankingEnabled={}",
                        normalizedTicker, retrievalProperties.isRerankingEnabled());
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
