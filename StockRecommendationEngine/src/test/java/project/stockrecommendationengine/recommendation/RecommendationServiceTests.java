package project.stockrecommendationengine.recommendation;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import project.stockrecommendationengine.broker.BrokerReadService;
import project.stockrecommendationengine.broker.BrokerException;
import project.stockrecommendationengine.broker.BrokerData.*;
import project.stockrecommendationengine.rag.dto.RetrievalResponse;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.retrieval.FilingRetrievalService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Deterministic evaluation scenarios run the real harness against scripted model/tool responses. */
class RecommendationServiceTests {
    private ChatModel model;
    private FilingRetrievalService filings;
    private BrokerReadService broker;
    private RecommendationService service;
    private RecommendationProperties properties;
    private ValidatorFactory validators;

    @BeforeEach void setup() {
        model = mock(ChatModel.class);
        filings = mock(FilingRetrievalService.class);
        broker = mock(BrokerReadService.class);
        properties = new RecommendationProperties();
        properties.setModel("scripted-test-model");
        validators = Validation.buildDefaultValidatorFactory();
        var beans = new StaticListableBeanFactory();
        beans.addBean("model", model);
        beans.addBean("broker", broker);
        service = new RecommendationService(beans.getBeanProvider(ChatModel.class), filings,
                beans.getBeanProvider(BrokerReadService.class), properties, validators.getValidator());
        when(filings.retrieve(any())).thenReturn(new RetrievalResponse("AAPL", "risks", "FILTERED_VECTOR", true,
                5, 1, List.of(evidence())));
        when(broker.searchInstruments("AAPL")).thenReturn(List.of(instrument(1)));
        when(broker.getQuote(1)).thenReturn(quote("REALTIME", Instant.now()));
    }

    @AfterEach void close() { service.close(); validators.close(); }

    @Test void managerConsolidatesIsolatedSpecialistsWithSharedBudgetAndProvenance() {
        when(model.call(any(Prompt.class))).thenReturn(
                calls(call("m1", "researchFilings", "{}"), call("m2", "researchBroker", "{}")),
                calls(call("r1", "searchFilings", "{\"query\":\"risks\"}")), report(),
                calls(call("b1", "findInstrument", "{}")),
                calls(call("b2", "getQuote", "{\"conid\":1}")), report(), answer("NEUTRAL", "[11]"));
        var result = service.recommend(request(false));
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.modelCalls()).isEqualTo(7);
        assertThat(result.sources()).containsExactly(evidence());
        assertThat(result.quotes()).hasSize(1);
        assertThat(result.toolTrace()).hasSize(5);
        verify(broker, never()).getPositions();
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(7)).call(prompts.capture());
        var all = prompts.getAllValues();
        assertThat(all.get(1).getInstructions()).hasSize(2);
        assertThat(all.get(3).getInstructions()).hasSize(2);
        assertThat(all.get(6).getInstructions()).anyMatch(ToolResponseMessage.class::isInstance);
        assertThat(all.get(6).toString()).contains("Material business risks.");
    }

    @Test void ragSpecialistCannotCallBrokerOrDelegateRecursively() {
        for (String tool : List.of("findInstrument", "researchBroker")) {
            when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchFilings", "{}")),
                    calls(call("r", tool, "{}")));
            assertThat(service.recommend(request(false)).status()).isEqualTo("TOOL_NOT_ALLOWED");
        }
        verifyNoInteractions(broker, filings);
    }

    @Test void brokerSpecialistCannotRetrieveFilings() {
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchBroker", "{}")),
                calls(call("b", "searchFilings", "{\"query\":\"risks\"}")));
        assertThat(service.recommend(request(false)).status()).isEqualTo("TOOL_NOT_ALLOWED");
        verifyNoInteractions(filings);
    }

    @Test void sharedModelBudgetStopsInsideSpecialist() {
        properties.setMaxModelCalls(2);
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchFilings", "{}")),
                calls(call("r", "searchFilings", "{\"query\":\"risks\"}")));
        assertThat(service.recommend(request(false)).status()).isEqualTo("MODEL_CALL_LIMIT");
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test void sharedToolBudgetIncludesDelegationAndNestedTools() {
        properties.setMaxToolCalls(2);
        when(model.call(any(Prompt.class))).thenReturn(
                calls(call("m1", "researchFilings", "{}"), call("m2", "researchBroker", "{}")),
                calls(call("r", "searchFilings", "{\"query\":\"risks\"}")), report());
        assertThat(service.recommend(request(false)).status()).isEqualTo("TOOL_LIMIT");
        verifyNoInteractions(broker);
    }

    @Test void malformedSpecialistReportBecomesAnExplicitLimitation() {
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchFilings", "{}")),
                text("not json"), answer("INSUFFICIENT_EVIDENCE", "[]"));
        assertThat(service.recommend(request(false)).limitations()).contains("researchFilings:INVALID_ARGUMENT");
    }

    @Test void specialistFailureAllowsManagerToExplainMissingData() {
        when(broker.getPositions()).thenThrow(new BrokerException(BrokerException.Code.LOGIN_REQUIRED));
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchBroker", "{}")),
                calls(call("b", "getPortfolioPositions", "{}")), report(), answer("INSUFFICIENT_EVIDENCE", "[]"));
        assertThat(service.recommend(request(true)).limitations())
                .contains("getPortfolioPositions:LOGIN_REQUIRED", "PORTFOLIO_UNAVAILABLE");
    }

    @Test void delayedQuoteRemainsPartialAndEvidenceDoesNotLeakBetweenRuns() {
        when(broker.getQuote(1)).thenReturn(quote("DELAYED", Instant.now()));
        when(model.call(any(Prompt.class))).thenReturn(
                calls(call("m1", "researchFilings", "{}"), call("m2", "researchBroker", "{}")),
                calls(call("r", "searchFilings", "{\"query\":\"risks\"}")), report(),
                calls(call("b1", "findInstrument", "{}")), calls(call("b2", "getQuote", "{\"conid\":1}")),
                report(), answer("NEUTRAL", "[11]"));
        assertThat(service.recommend(request(false)).status()).isEqualTo("PARTIAL");
        when(model.call(any(Prompt.class))).thenReturn(answer("NEUTRAL", "[11]"));
        assertThat(service.recommend(request(false)).status()).isEqualTo("INVALID_CITATION");
    }

    @Test void duplicateSpecialistToolIdsAreRejected() {
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchBroker", "{}")),
                calls(call("same", "findInstrument", "{}")), calls(call("same", "findInstrument", "{}")));
        assertThat(service.recommend(request(false)).status()).isEqualTo("INVALID_TOOL_CALL_ID");
    }

    @Test void specialistArgumentValidationAndResultSizeLimitsRemainEnforced() {
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchFilings", "{}")),
                calls(call("r", "searchFilings", "{\"query\":\"risks\",\"ticker\":\"OTHER\"}")),
                report(), answer("INSUFFICIENT_EVIDENCE", "[]"));
        assertThat(service.recommend(request(false)).limitations()).contains("searchFilings:INVALID_ARGUMENT");
        verifyNoInteractions(filings);
        properties.setMaxToolResultChars(10);
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchFilings", "{}")),
                calls(call("r", "searchFilings", "{\"query\":\"risks\"}")));
        assertThat(service.recommend(request(false)).status()).isEqualTo("TOOL_RESULT_LIMIT");
    }

    @Test void brokerSpecialistRespectsPortfolioOptInAndContractAmbiguity() {
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchBroker", "{}")),
                calls(call("b", "getPortfolioPositions", "{}")));
        assertThat(service.recommend(request(false)).status()).isEqualTo("TOOL_NOT_ALLOWED");
        verify(broker, never()).getPositions();
        when(broker.searchInstruments("AAPL")).thenReturn(List.of(instrument(1), instrument(2)));
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchBroker", "{}")),
                calls(call("b1", "findInstrument", "{}")), calls(call("b2", "getQuote", "{\"conid\":1}")),
                report(), answer("INSUFFICIENT_EVIDENCE", "[]"));
        assertThat(service.recommend(request(false)).limitations()).contains("getQuote:AMBIGUOUS_CONTRACT");
        verify(broker, never()).getQuote(anyLong());
    }

    private static ChatResponse report() { return text("{\"summary\":\"Findings from the available evidence.\"}"); }

    @Test void rejectsUnknownToolsWithoutExecutingAnyService() {
        when(model.call(any(Prompt.class))).thenReturn(calls(call("1", "placeOrder", "{}")));
        assertThat(service.recommend(request(false)).status()).isEqualTo("TOOL_NOT_ALLOWED");
        verifyNoInteractions(filings, broker);
    }

    @Test void portfolioToolRequiresRequestOptIn() {
        when(model.call(any(Prompt.class))).thenReturn(calls(call("1", "getPortfolioPositions", "{}")));
        assertThat(service.recommend(request(false)).status()).isEqualTo("TOOL_NOT_ALLOWED");
        verifyNoInteractions(broker);
    }

    @Test void rejectsInventedCitationsAndMissingEvidence() {
        when(model.call(any(Prompt.class))).thenReturn(answer("BULLISH", "[999]"));
        assertThat(service.recommend(request(false)).status()).isEqualTo("INVALID_CITATION");
        when(model.call(any(Prompt.class))).thenReturn(answer("BULLISH", "[]"));
        assertThat(service.recommend(request(false)).status()).isEqualTo("MISSING_EVIDENCE");
    }

    @Test void rejectsUnimplementedNumericFieldsInModelOutput() {
        when(model.call(any(Prompt.class))).thenReturn(text("{\"assessment\":\"BULLISH\",\"reasoning\":\"buy\",\"citedChunkIds\":[],\"takeProfit\":123}"));
        assertThat(service.recommend(request(false)).status()).isEqualTo("INVALID_MODEL_OUTPUT");
    }

    @Test void enforcesToolBudgetBeforeExecutingAnOversizedBatch() {
        properties.setMaxToolCalls(1);
        when(model.call(any(Prompt.class))).thenReturn(calls(call("1", "findInstrument", "{}"), call("2", "findInstrument", "{}")));
        assertThat(service.recommend(request(false)).status()).isEqualTo("TOOL_LIMIT");
        verifyNoInteractions(broker);
    }

    @Test void deadlineInterruptsTheRunAndPreventsSubsequentToolExecution() throws Exception {
        properties.setDeadlineMs(100);
        var interrupted = new CountDownLatch(1);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            try { new CountDownLatch(1).await(5, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return calls(call("1", "findInstrument", "{}"));
        });
        assertThat(service.recommend(request(false)).status()).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        verifyNoInteractions(broker);
    }

    @Test void contextLimitStopsBeforeSendingAnOversizedPrompt() {
        properties.setMaxContextChars(10);
        assertThat(service.recommend(request(false)).status()).isEqualTo("CONTEXT_LIMIT");
        verifyNoInteractions(model, filings, broker);
    }

    @Test void oversizedObservedUsageStopsBeforeExecutingModelRequestedTools() {
        var response = calls(call("1", "findInstrument", "{}"));
        var metadata = org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                .usage(new org.springframework.ai.chat.metadata.DefaultUsage(20000, 100)).build();
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(response.getResults(), metadata));
        assertThat(service.recommend(request(false)).status()).isEqualTo("TOKEN_LIMIT");
        verifyNoInteractions(broker);
    }

    private RecommendationRequest request(boolean portfolio) { return new RecommendationRequest("aapl", "Assess risks", null, portfolio); }
    private static AssistantMessage.ToolCall call(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }
    private static ChatResponse calls(AssistantMessage.ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())));
    }
    private static ChatResponse text(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    private static ChatResponse answer(String assessment, String citations) {
        return text("{\"assessment\":\"" + assessment + "\",\"reasoning\":\"The filing describes material business risks.\",\"citedChunkIds\":" + citations + "}");
    }
    private static Instrument instrument(long id) { return new Instrument(id, "AAPL", "Apple", "NASDAQ", "USD", "STK"); }
    private static Quote quote(String availability, Instant updated) {
        return new Quote(1, new BigDecimal("100"), null, null, updated, Instant.now(), availability, "RpB");
    }
    private static RetrievedFilingChunk evidence() {
        return new RetrievedFilingChunk(11L, 1L, "AAPL", "0000320193", "accession", "10-K", LocalDate.of(2025, 10, 31),
                LocalDate.of(2025, 9, 30), "ITEM_1A", "Risk factors", 0, "Material business risks.", "https://www.sec.gov/example", 0.8);
    }
}
