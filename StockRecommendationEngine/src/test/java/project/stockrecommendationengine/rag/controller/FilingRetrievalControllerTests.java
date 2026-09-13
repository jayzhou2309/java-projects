package project.stockrecommendationengine.rag.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import project.stockrecommendationengine.rag.dto.RetrievalResponse;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationRepository;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationService;
import project.stockrecommendationengine.rag.ingestion.FilingEmbeddingService;
import project.stockrecommendationengine.rag.repository.FilingRetrievalRepository;
import project.stockrecommendationengine.rag.retrieval.FilingRetrievalProperties;
import project.stockrecommendationengine.rag.retrieval.FilingRetrievalService;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FilingRetrievalControllerTests {
    private FilingRetrievalService retrievalService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        retrievalService = mock(FilingRetrievalService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new FilingRetrievalController(retrievalService)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"ticker\":\" \",\"query\":\"risks\"}",
            "{\"ticker\":\"AAPL\",\"query\":\" \"}",
            "{\"ticker\":\"AAPL\",\"query\":\"risks\",\"topK\":0}",
            "{\"ticker\":\"AAPL\",\"query\":\"risks\",\"topK\":21}",
            "{\"ticker\":\"AAPL\",\"query\":\"risks\",\"filingTypes\":[]}",
            "{\"ticker\":\"AAPL\",\"query\":\"risks\",\"filingTypes\":[null]}",
            "{\"ticker\":\"AAPL\",\"query\":\"risks\",\"sectionKeys\":[\" \"]}",
            "{\"ticker\":\"AAPL\",\"query\":\"risks\",\"filingDateFrom\":\"2026-01-01\",\"filingDateTo\":\"2025-01-01\"}",
            "{\"ticker\":\"AAPL\",\"query\":\"risks\",\"filingDateTo\":\"not-a-date\"}"
    })
    void rejectsInvalidRequestsWithoutCallingRetrieval(String requestBody) throws Exception {
        mockMvc.perform(post("/api/rag/retrieve").contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(retrievalService);
    }

    @Test
    void returnsStructuredJsonForAnEmptyEvidenceSet() throws Exception {
        when(retrievalService.retrieve(any())).thenReturn(new RetrievalResponse("AAPL", "risks", "FILTERED_VECTOR",
                true, 5, 0, List.of()));
        mockMvc.perform(post("/api/rag/retrieve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticker\":\"AAPL\",\"query\":\"risks\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.retrievalStrategy").value("FILTERED_VECTOR"))
                .andExpect(jsonPath("$.results").isEmpty());
    }

    // Reranker milestone 1 (RAG-1), C1: rerank true with no reranker bean is a 400 naming the reason, not a silent skip.

    @Test
    void rerankTrueWithoutARerankerIsABadRequestWithAClearMessage() throws Exception {
        FilingEmbeddingService embeddings = mock(FilingEmbeddingService.class);
        FilingRetrievalRepository repository = mock(FilingRetrievalRepository.class);
        var realService = new FilingRetrievalService(embeddings, repository, new FilingRetrievalProperties(), Optional.empty());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FilingRetrievalController(realService))
                .setControllerAdvice(new RetrievalExceptionHandler()).build();
        mvc.perform(post("/api/rag/retrieve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticker\":\"AAPL\",\"query\":\"risks\",\"rerank\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no FilingReranker is configured")));
        verifyNoInteractions(embeddings, repository);
    }

    @Test
    void evaluateWithRerankTrueWithoutARerankerIsABadRequestBeforeAnyQuestionRuns() throws Exception {
        var retrieval = new FilingRetrievalService(mock(FilingEmbeddingService.class), mock(FilingRetrievalRepository.class),
                new FilingRetrievalProperties(), Optional.empty());
        var loader = mock(project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationSetLoader.class);
        var snapshots = mock(RetrievalEvaluationRepository.class);
        var evaluation = new RetrievalEvaluationService(loader, retrieval, snapshots,
                new project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationProperties(), new FilingRetrievalProperties());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RetrievalEvaluationController(evaluation, snapshots))
                .setControllerAdvice(new RetrievalExceptionHandler()).build();
        mvc.perform(post("/api/rag/evaluate").param("rerank", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no FilingReranker is configured")));
        verifyNoInteractions(loader, snapshots);
    }
}
