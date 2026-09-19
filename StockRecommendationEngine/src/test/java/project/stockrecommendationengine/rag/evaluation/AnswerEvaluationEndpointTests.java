package project.stockrecommendationengine.rag.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import project.stockrecommendationengine.rag.controller.AnswerEvaluationController;
import project.stockrecommendationengine.rag.controller.RetrievalExceptionHandler;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** {@code /api/rag/evaluate/answers}: parameters, reads, and refusals with their reason in the body. Token gating is in IntegrationAccessTests. */
class AnswerEvaluationEndpointTests {
    private final AnswerEvaluationService service = mock(AnswerEvaluationService.class);
    private final AnswerEvaluationRepository repository = mock(AnswerEvaluationRepository.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AnswerEvaluationController(service, repository))
            .setControllerAdvice(new RetrievalExceptionHandler()).build();

    private static AnswerEvaluation snapshot(long id) {
        return new AnswerEvaluation(id, Instant.parse("2026-09-19T12:00:00Z"), "v2", 1, 1, false, null,
                AnswerEvaluationService.aggregate(List.of()), Map.of("searchTopK", 3), List.of(), List.of());
    }

    @Test void postPassesTheFilterAndLimitAndReturnsTheSnapshot() throws Exception {
        when(service.evaluate("aapl-08", 1)).thenReturn(snapshot(4));
        mvc.perform(post("/api/rag/evaluate/answers").param("questions", "aapl-08").param("limit", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(4)).andExpect(jsonPath("$.partial").value(false)).andExpect(jsonPath("$.properties.searchTopK").value(3));
        when(service.evaluate(null, null)).thenReturn(snapshot(5));
        mvc.perform(post("/api/rag/evaluate/answers")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(5));
        mvc.perform(post("/api/rag/evaluate/answers").param("limit", "many")).andExpect(status().isBadRequest());
    }

    @Test void aRefusalCarriesItsStatusAndReason() throws Exception {
        when(service.evaluate(any(), any())).thenThrow(new AnswerEvaluationRefusedException(HttpStatus.SERVICE_UNAVAILABLE,
                "recommendations are disabled: set RECOMMENDATION_ENABLED=true"));
        mvc.perform(post("/api/rag/evaluate/answers")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("recommendations are disabled: set RECOMMENDATION_ENABLED=true"));
        reset(service);
        when(service.evaluate(any(), any())).thenThrow(new AnswerEvaluationRefusedException(HttpStatus.CONFLICT, "already running"));
        mvc.perform(post("/api/rag/evaluate/answers")).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("already running"));
    }

    @Test void latestAndByIdReadStoredSnapshotsWithoutTouchingTheRunner() throws Exception {
        when(repository.latest()).thenReturn(Optional.empty());
        mvc.perform(get("/api/rag/evaluate/answers")).andExpect(status().isNotFound());
        when(repository.latest()).thenReturn(Optional.of(snapshot(9)));
        when(repository.findById(9L)).thenReturn(Optional.of(snapshot(9)));
        mvc.perform(get("/api/rag/evaluate/answers")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(9)).andExpect(jsonPath("$.setVersion").value("v2"));
        mvc.perform(get("/api/rag/evaluate/answers/9")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(9));
        mvc.perform(get("/api/rag/evaluate/answers/10")).andExpect(status().isNotFound());
        mvc.perform(get("/api/rag/evaluate/answers/abc")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
