package project.stockrecommendationengine.rag.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import project.stockrecommendationengine.rag.dto.RetrievalRequest;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.ingestion.FilingEmbeddingService;
import project.stockrecommendationengine.rag.repository.FilingRetrievalRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class FilingRetrievalServiceTests {
    private FilingEmbeddingService embeddings;
    private FilingRetrievalRepository repository;
    private FilingRetrievalProperties properties;
    private FilingRetrievalService service;

    @BeforeEach
    void setUp() {
        embeddings = mock(FilingEmbeddingService.class);
        repository = mock(FilingRetrievalRepository.class);
        properties = new FilingRetrievalProperties();
        service = new FilingRetrievalService(embeddings, repository, properties, Optional.empty());
        when(embeddings.embed(anyString())).thenReturn(new float[1536]);
        when(repository.findSimilarChunks(any(), any(), anyInt())).thenReturn(List.of(evidence(1L), evidence(2L)));
    }

    @Test
    void hybridRetrievalIsOnByDefaultSinceTheMeasuredComparison() {
        // Milestone 3 decision (RAG.md, Hybrid Retrieval): snapshots 35 (vector only, hit@5 0.600000) and 34 (hybrid, 0.633333),
        // no ticker's hit@5 lower, so the property default is on; the yaml carries the same value.
        assertThat(new FilingRetrievalProperties().isHybridEnabled()).isTrue();
        when(repository.findKeywordChunks(any(), any(), any(), anyInt())).thenReturn(List.of(evidence(3L)));
        assertThat(service.retrieve(request()).retrievalStrategy()).isEqualTo("HYBRID_RRF");
    }

    @Test
    void normalizesInputsAndReturnsLimitedEvidenceWithExplicitStrategy() {
        properties.setHybridEnabled(false);
        var request = new RetrievalRequest(" aapl ", " risks? ", List.of("10-k"), null, null, List.of("item_1a"), 1, null, null);
        var response = service.retrieve(request);
        assertThat(response.ticker()).isEqualTo("AAPL");
        assertThat(response.query()).isEqualTo("risks?");
        assertThat(response.retrievalStrategy()).isEqualTo("FILTERED_VECTOR");
        assertThat(response.latestFilingsOnly()).isTrue();
        assertThat(response.results()).containsExactly(evidence(1L));
        assertThat(response.candidatesRetrieved()).isEqualTo(2);
        var filterCaptor = ArgumentCaptor.forClass(FilingRetrievalFilter.class);
        verify(repository).findSimilarChunks(any(), filterCaptor.capture(), eq(40));
        assertThat(filterCaptor.getValue().filingTypes()).containsExactly("10-K");
        assertThat(filterCaptor.getValue().sectionKeys()).containsExactly("ITEM_1A");
        verify(embeddings).embed("risks?");
        verify(repository, never()).findKeywordChunks(any(), any(), any(), anyInt());
    }

    @Test
    void explicitDateRangeSearchesAllEligibleFilingsUnlessLatestIsRequested() {
        var cutoff = LocalDate.parse("2025-12-31");
        var response = service.retrieve(new RetrievalRequest("AAPL", "risks", null, null, cutoff, null, null, null, null));
        assertThat(response.latestFilingsOnly()).isFalse();
        assertThat(response.topK()).isEqualTo(5);
        var latestResponse = service.retrieve(new RetrievalRequest("AAPL", "risks", null, null, cutoff, null, null, true, null));
        assertThat(latestResponse.latestFilingsOnly()).isTrue();
    }

    @Test
    void emptyCandidatesReturnAnEmptyEvidenceList() {
        when(repository.findSimilarChunks(any(), any(), anyInt())).thenReturn(List.of());
        assertThat(service.retrieve(request()).results()).isEmpty();
    }

    @Test
    void refusesEnabledRerankingWithoutAnAdapter() {
        properties.setRerankingEnabled(true);
        assertThatThrownBy(() -> new FilingRetrievalService(embeddings, repository, properties, Optional.empty()))
                .hasMessageContaining("no FilingReranker provider");
    }

    @Test
    void usesConfiguredRerankerWithoutChangingCitationsOrSimilarityScores() {
        FilingReranker reranker = mock(FilingReranker.class);
        properties.setHybridEnabled(false);
        properties.setRerankingEnabled(true);
        service = new FilingRetrievalService(embeddings, repository, properties, Optional.of(reranker));
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(evidence(2L), evidence(1L)));
        var response = service.retrieve(request());
        assertThat(response.retrievalStrategy()).isEqualTo("FILTERED_VECTOR_RERANKED");
        assertThat(response.results()).containsExactly(evidence(2L), evidence(1L));
    }

    @Test
    void rejectsRerankerEvidenceOutsideRetrievedCandidates() {
        FilingReranker reranker = mock(FilingReranker.class);
        properties.setRerankingEnabled(true);
        service = new FilingRetrievalService(embeddings, repository, properties, Optional.of(reranker));
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(evidence(99L)));
        assertThatThrownBy(() -> service.retrieve(request())).hasMessageContaining("altered evidence");
    }

    @Test
    void removesRedundantPassagesButPreservesDifferentPeriodsAndSections() {
        String shared = "Shared risk disclosure. ".repeat(30);
        var first = passage(1L, 1L, "ITEM_1A", shared);
        var duplicate = passage(2L, 1L, "ITEM_1A", shared);
        var overlap = passage(3L, 1L, "ITEM_1A", shared + " Additional detail.");
        var otherPeriod = passage(4L, 2L, "ITEM_1A", shared);
        var otherSection = passage(5L, 1L, "ITEM_7", shared);
        var distinct = passage(6L, 1L, "ITEM_1A", "Different supporting evidence.");
        when(repository.findSimilarChunks(any(), any(), anyInt()))
                .thenReturn(List.of(first, duplicate, overlap, otherPeriod, otherSection, distinct));
        assertThat(service.retrieve(request()).results()).containsExactly(first, otherPeriod, otherSection, distinct);
    }

    @Test
    void preservesNormalChunkOverlap() {
        String overlap = "x".repeat(500);
        var first = passage(1L, 1L, "ITEM_1A", "a".repeat(3000) + overlap);
        var next = passage(2L, 1L, "ITEM_1A", overlap + "b".repeat(3000));
        when(repository.findSimilarChunks(any(), any(), anyInt())).thenReturn(List.of(first, next));
        assertThat(service.retrieve(request()).results()).containsExactly(first, next);
    }

    // Milestone 2, C1: reciprocal rank fusion arithmetic and tie-breaking.

    @Test
    void fusesVectorAndKeywordRankingsByReciprocalRankWithVectorSimilarityThenChunkIdTieBreaks() {
        var a = evidence(1L, 0.90);
        var b = evidence(2L, 0.80);
        var c = evidence(3L, 0.95);
        var d = evidence(4L, 0.80);
        when(repository.findSimilarChunks(any(), any(), anyInt())).thenReturn(List.of(a, b, c));
        when(repository.findKeywordChunks(any(), any(), any(), anyInt())).thenReturn(List.of(c, d, a));

        var scores = FilingRetrievalService.reciprocalRankScores(List.of(List.of(a, b, c), List.of(c, d, a)), 60);
        assertThat(scores.get(1L)).isEqualByComparingTo(reciprocal(61).add(reciprocal(63)));
        assertThat(scores.get(3L)).isEqualByComparingTo(reciprocal(63).add(reciprocal(61)));
        assertThat(scores.get(1L)).isEqualByComparingTo(scores.get(3L));
        assertThat(scores.get(2L)).isEqualByComparingTo(reciprocal(62));
        assertThat(scores.get(4L)).isEqualByComparingTo(reciprocal(62));
        assertThat(scores.get(1L)).isGreaterThan(scores.get(2L));

        // A repeated id within one list counts once at its first position and does not shift the ranks after it:
        // [a, a, b] scores a = 1/61 and b = 1/62, not 1/63.
        var repeated = FilingRetrievalService.reciprocalRankScores(List.of(List.of(a, a, b)), 60);
        assertThat(repeated.get(1L)).isEqualByComparingTo(reciprocal(61));
        assertThat(repeated.get(2L)).isEqualByComparingTo(reciprocal(62));

        var response = service.retrieve(request("risks", 3, true));
        assertThat(response.retrievalStrategy()).isEqualTo("HYBRID_RRF");
        // A and C tie: C wins on vector similarity; B and D tie on similarity too, so the lower chunk id wins.
        assertThat(response.results()).containsExactly(c, a, b);
        assertThat(response.candidatesRetrieved()).isEqualTo(4);

        var widerResponse = service.retrieve(request("risks", 4, true));
        assertThat(widerResponse.results()).containsExactly(c, a, b, d);
        assertThat(widerResponse.results().get(3).similarityScore()).isEqualTo(0.80);
    }

    @Test
    void fusionTieOnScoreAndSimilarityFallsBackToTheLowerChunkIdAndKeepsTheVectorInstance() {
        var a = evidence(1L, 0.90);
        var b = evidence(2L, 0.90);
        var keywordCopyOfB = new RetrievedFilingChunk(2L, 1L, "AAPL", "0000320193", "accession", "10-K",
                LocalDate.parse("2025-10-31"), null, "ITEM_1A", "Risk Factors", 2, "Evidence 2",
                "https://example.invalid/filing", 0.90);
        assertThat(FilingRetrievalService.fuse(List.of(a, b), List.of(keywordCopyOfB, a), 60)).containsExactly(a, b);
        assertThat(FilingRetrievalService.fuse(List.of(b, a), List.of(a, b), 60)).containsExactly(a, b);
        assertThat(FilingRetrievalService.fuse(List.of(), List.of(b, a), 60)).containsExactly(b, a);
        assertThat(FilingRetrievalService.fuse(List.of(b, a), List.of(), 60)).containsExactly(b, a);
    }

    @Test
    void fusedCandidatesAreDiversifiedBeforeTheTopKCut() {
        String shared = "Shared risk disclosure. ".repeat(30);
        var first = passage(1L, 1L, "ITEM_1A", shared);
        var distinct = passage(2L, 1L, "ITEM_1A", "Different supporting evidence.");
        var keywordDuplicate = passage(3L, 1L, "ITEM_1A", shared);
        var keywordOnly = passage(4L, 1L, "ITEM_7", "Keyword only passage.");
        when(repository.findSimilarChunks(any(), any(), anyInt())).thenReturn(List.of(first, distinct));
        when(repository.findKeywordChunks(any(), any(), any(), anyInt())).thenReturn(List.of(keywordDuplicate, keywordOnly));

        var response = service.retrieve(request("risks", 2, true));
        // Fused order: first (1/61) ties keywordDuplicate (1/61) and wins on lower id, then distinct and keywordOnly (1/62 each).
        assertThat(response.candidatesRetrieved()).isEqualTo(4);
        assertThat(response.results()).containsExactly(first, distinct);
        assertThat(service.retrieve(request("risks", 3, true)).results()).containsExactly(first, distinct, keywordOnly);
    }

    // Milestone 2, C2: how the hybrid flag resolves and when the keyword path is skipped.

    @Test
    void requestHybridTrueForcesTheKeywordPathEvenWhenThePropertyIsOff() {
        properties.setHybridEnabled(false);
        properties.setKeywordCandidateCount(25);
        when(repository.findKeywordChunks(any(), any(), any(), anyInt())).thenReturn(List.of(evidence(3L)));
        var response = service.retrieve(request("risks", null, true));
        assertThat(response.retrievalStrategy()).isEqualTo("HYBRID_RRF");
        assertThat(response.candidatesRetrieved()).isEqualTo(3);
        assertThat(response.results()).containsExactly(evidence(1L), evidence(3L), evidence(2L));
        var filterCaptor = ArgumentCaptor.forClass(FilingRetrievalFilter.class);
        verify(repository).findKeywordChunks(eq("risks"), any(), filterCaptor.capture(), eq(25));
        assertThat(filterCaptor.getValue().ticker()).isEqualTo("AAPL");
        assertThat(filterCaptor.getValue().latestFilingsOnly()).isTrue();
    }

    @Test
    void requestHybridFalseForcesVectorOnlyEvenWhenThePropertyIsOn() {
        properties.setHybridEnabled(true);
        var response = service.retrieve(request("risks", null, false));
        assertThat(response.retrievalStrategy()).isEqualTo("FILTERED_VECTOR");
        assertThat(response.results()).containsExactly(evidence(1L), evidence(2L));
        verify(repository, never()).findKeywordChunks(any(), any(), any(), anyInt());
    }

    @Test
    void absentHybridFollowsTheProperty() {
        when(repository.findKeywordChunks(any(), any(), any(), anyInt())).thenReturn(List.of(evidence(2L)));
        properties.setHybridEnabled(false);
        assertThat(service.retrieve(request("risks", null, null)).retrievalStrategy()).isEqualTo("FILTERED_VECTOR");
        verify(repository, never()).findKeywordChunks(any(), any(), any(), anyInt());

        properties.setHybridEnabled(true);
        assertThat(service.retrieve(request("risks", null, null)).retrievalStrategy()).isEqualTo("HYBRID_RRF");
        verify(repository).findKeywordChunks(eq("risks"), any(), any(), eq(40));
    }

    @Test
    void stopwordOnlyQuerySkipsTheKeywordPathAndReportsVectorOnly() {
        properties.setHybridEnabled(true);
        var response = service.retrieve(request("the and of", null, true));
        assertThat(response.retrievalStrategy()).isEqualTo("FILTERED_VECTOR");
        assertThat(response.results()).containsExactly(evidence(1L), evidence(2L));
        assertThat(response.candidatesRetrieved()).isEqualTo(2);
        verify(repository, never()).findKeywordChunks(any(), any(), any(), anyInt());
        verify(embeddings).embed("the and of");
    }

    @Test
    void anEmptyKeywordResultStillCountsAsHybridBecauseFusionRan() {
        when(repository.findKeywordChunks(any(), any(), any(), anyInt())).thenReturn(List.of());
        var response = service.retrieve(request("risks", null, true));
        assertThat(response.retrievalStrategy()).isEqualTo("HYBRID_RRF");
        assertThat(response.results()).containsExactly(evidence(1L), evidence(2L));
        assertThat(response.candidatesRetrieved()).isEqualTo(2);
    }

    // Milestone 2, C3: a keyword-path failure never propagates.

    @Test
    void keywordSearchFailureFallsBackToVectorOnlyWithAWarning(CapturedOutput output) {
        when(repository.findKeywordChunks(any(), any(), any(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("gin index unavailable"));
        var response = service.retrieve(request("risks", null, true));
        assertThat(response.retrievalStrategy()).isEqualTo("FILTERED_VECTOR");
        assertThat(response.results()).containsExactly(evidence(1L), evidence(2L));
        assertThat(response.candidatesRetrieved()).isEqualTo(2);
        assertThat(output.getOut() + output.getErr())
                .contains("WARN")
                .contains("Keyword search failed, falling back to vector candidates only")
                .contains("error=DataAccessResourceFailureException")
                .doesNotContain("gin index unavailable")
                .doesNotContain("Retrieval failed");

        doThrow(new IllegalStateException("driver")).when(repository).findKeywordChunks(any(), any(), any(), anyInt());
        assertThat(service.retrieve(request("risks", null, true)).retrievalStrategy()).isEqualTo("FILTERED_VECTOR");
        assertThat(output.getOut() + output.getErr()).contains("error=IllegalStateException");
    }

    // Milestone 2, C5: the reranker sees the fused, diversified candidates and is validated against them.

    @Test
    void rerankerReceivesTheFusedDiversifiedCandidatesAndIsValidatedAgainstThem() {
        FilingReranker reranker = mock(FilingReranker.class);
        properties.setRerankingEnabled(true);
        service = new FilingRetrievalService(embeddings, repository, properties, Optional.of(reranker));
        when(repository.findKeywordChunks(any(), any(), any(), anyInt())).thenReturn(List.of(evidence(3L)));
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(evidence(3L), evidence(1L)));

        var response = service.retrieve(request("risks", 2, true));
        assertThat(response.retrievalStrategy()).isEqualTo("HYBRID_RRF_RERANKED");
        assertThat(response.results()).containsExactly(evidence(3L), evidence(1L));
        assertThat(response.candidatesRetrieved()).isEqualTo(3);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RetrievedFilingChunk>> rerankInput = ArgumentCaptor.forClass(List.class);
        verify(reranker).rerank(eq("risks"), rerankInput.capture(), eq(2));
        assertThat(rerankInput.getValue()).containsExactly(evidence(1L), evidence(3L), evidence(2L));

        when(reranker.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(evidence(99L)));
        assertThatThrownBy(() -> service.retrieve(request("risks", 2, true))).hasMessageContaining("altered evidence");
    }

    private static BigDecimal reciprocal(int denominator) {
        return BigDecimal.ONE.divide(BigDecimal.valueOf(denominator), 18, RoundingMode.HALF_EVEN);
    }

    private RetrievedFilingChunk passage(Long id, Long filing, String section, String text) {
        return new RetrievedFilingChunk(id, filing, "AAPL", "0000320193", "accession" + filing, "10-K",
                LocalDate.parse("2025-10-31"), null, section, "Section", id.intValue(), text,
                "https://example.invalid/filing/" + filing, 0.9);
    }

    private RetrievalRequest request() {
        return request("risks", null, null);
    }

    private RetrievalRequest request(String query, Integer topK, Boolean hybrid) {
        return new RetrievalRequest("AAPL", query, null, null, null, null, topK, null, hybrid);
    }

    private RetrievedFilingChunk evidence(Long chunkId) {
        return evidence(chunkId, 0.9);
    }

    private RetrievedFilingChunk evidence(Long chunkId, double similarity) {
        return new RetrievedFilingChunk(chunkId, 1L, "AAPL", "0000320193", "accession", "10-K",
                LocalDate.parse("2025-10-31"), null, "ITEM_1A", "Risk Factors", chunkId.intValue(),
                "Evidence " + chunkId, "https://example.invalid/filing", similarity);
    }
}
