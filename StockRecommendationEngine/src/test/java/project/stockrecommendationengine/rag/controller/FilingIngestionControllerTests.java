package project.stockrecommendationengine.rag.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import project.stockrecommendationengine.rag.ingestion.FilingIngestionService;
import project.stockrecommendationengine.rag.ingestion.UnknownTickerException;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilingIngestionControllerTests {
    private FilingIngestionService ingestion;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ingestion = mock(FilingIngestionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new FilingIngestionController(ingestion))
                .setControllerAdvice(new IngestionExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"filingTypes\":[\"10-K\"],\"limit\":1}",
            "{\"ticker\":null,\"filingTypes\":[\"10-K\"],\"limit\":1}",
            "{\"ticker\":\" \",\"filingTypes\":[\"10-K\"],\"limit\":1}",
            "{\"ticker\":\"AAPL\",\"limit\":1}",
            "{\"ticker\":\"AAPL\",\"filingTypes\":null,\"limit\":1}",
            "{\"ticker\":\"AAPL\",\"filingTypes\":[],\"limit\":1}",
            "{\"ticker\":\"AAPL\",\"filingTypes\":[null],\"limit\":1}",
            "{\"ticker\":\"AAPL\",\"filingTypes\":[\" \"],\"limit\":1}",
            "{\"ticker\":\"AAPL\",\"filingTypes\":[\"10-K\"],\"limit\":0}",
            "{\"ticker\":\"AAPL\",\"filingTypes\":[\"10-K\"],\"limit\":-1}"
    })
    void rejectsInvalidInputBeforeCallingIngestion(String body) throws Exception {
        mvc.perform(post("/api/rag/ingest").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ingestion);
    }

    @Test
    void validRequestCallsService() throws Exception {
        mvc.perform(post("/api/rag/ingest").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticker\":\"AAPL\",\"filingTypes\":[\"10-K\"],\"limit\":1}"))
                .andExpect(status().isOk());
        verify(ingestion).ingest("AAPL", List.of("10-K"), 1);
    }

    @Test
    void unknownTickerIsClientError() throws Exception {
        doThrow(new UnknownTickerException("UNKNOWN")).when(ingestion).ingest(anyString(), anyList(), anyInt());
        mvc.perform(post("/api/rag/ingest").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticker\":\"UNKNOWN\",\"filingTypes\":[\"10-K\"],\"limit\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upstreamFailureIsNotConvertedToClientError() {
        doThrow(new IllegalStateException("SEC unavailable")).when(ingestion).ingest(anyString(), anyList(), anyInt());
        assertThatThrownBy(() -> mvc.perform(post("/api/rag/ingest").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticker\":\"AAPL\",\"filingTypes\":[\"10-K\"],\"limit\":1}")))
                .hasRootCauseMessage("SEC unavailable");
    }
}
