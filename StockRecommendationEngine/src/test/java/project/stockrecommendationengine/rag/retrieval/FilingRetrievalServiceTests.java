package project.stockrecommendationengine.rag.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import project.stockrecommendationengine.rag.dto.RetrievalRequest;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.ingestion.FilingEmbeddingService;
import project.stockrecommendationengine.rag.repository.FilingRetrievalRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

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
    void normalizesInputsAndReturnsLimitedEvidenceWithExplicitStrategy() {
        var request = new RetrievalRequest(" aapl ", " risks? ", List.of("10-k"), null, null, List.of("item_1a"), 1, null);
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
    }

    @Test
    void explicitDateRangeSearchesAllEligibleFilingsUnlessLatestIsRequested() {
        var cutoff = LocalDate.parse("2025-12-31");
        var response = service.retrieve(new RetrievalRequest("AAPL", "risks", null, null, cutoff, null, null, null));
        assertThat(response.latestFilingsOnly()).isFalse();
        assertThat(response.topK()).isEqualTo(5);
        var latestResponse = service.retrieve(new RetrievalRequest("AAPL", "risks", null, null, cutoff, null, null, true));
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

    private RetrievalRequest request() {
        return new RetrievalRequest("AAPL", "risks", null, null, null, null, null, null);
    }

    private RetrievedFilingChunk evidence(Long chunkId) {
        return new RetrievedFilingChunk(chunkId, 1L, "AAPL", "0000320193", "accession", "10-K",
                LocalDate.parse("2025-10-31"), null, "ITEM_1A", "Risk Factors", chunkId.intValue(),
                "Evidence " + chunkId, "https://example.invalid/filing", 0.9);
    }
}
