package project.stockrecommendationengine.recommendation;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
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
import project.stockrecommendationengine.outcome.ConfidenceCalibration;
import project.stockrecommendationengine.outcome.ConfidenceCalibrationService;
import project.stockrecommendationengine.outcome.TrackRecord;
import project.stockrecommendationengine.outcome.TrackRecordService;
import project.stockrecommendationengine.quant.QuantAnalysis;
import project.stockrecommendationengine.quant.QuantAnalysisService;
import project.stockrecommendationengine.quant.QuantProperties;
import project.stockrecommendationengine.rag.dto.RetrievalResponse;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.freshness.FilingFreshness;
import project.stockrecommendationengine.rag.freshness.FilingFreshnessService;
import project.stockrecommendationengine.rag.freshness.FilingFreshnessService.EnsureOutcome;
import project.stockrecommendationengine.rag.freshness.FilingRefreshResult;
import project.stockrecommendationengine.rag.ingestion.UnknownTickerException;
import project.stockrecommendationengine.rag.retrieval.FilingRetrievalService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Deterministic evaluation scenarios run the real harness against scripted model/tool responses. */
class RecommendationServiceTests {
    private ChatModel model;
    private FilingRetrievalService filings;
    private FilingFreshnessService freshness;
    private BrokerReadService broker;
    private QuantAnalysisService quant;
    private RecommendationRepository store;
    private TrackRecordService trackRecords;
    private ConfidenceCalibrationService calibrations;
    private final StaticListableBeanFactory beans = new StaticListableBeanFactory();
    private RecommendationService service;
    private RecommendationProperties properties;
    private ValidatorFactory validators;

    @BeforeEach void setup() {
        model = mock(ChatModel.class);
        filings = mock(FilingRetrievalService.class);
        freshness = mock(FilingFreshnessService.class);
        broker = mock(BrokerReadService.class);
        quant = mock(QuantAnalysisService.class);
        store = mock(RecommendationRepository.class);
        trackRecords = mock(TrackRecordService.class);
        beans.addBean("trackRecords", trackRecords);
        when(trackRecords.trackRecord("AAPL", 10)).thenReturn(new TrackRecord("AAPL", 0, List.of(), List.of(), TrackRecordService.CAVEAT));
        calibrations = mock(ConfidenceCalibrationService.class);
        beans.addBean("calibrations", calibrations);
        when(calibrations.apply(any())).thenReturn(new ConfidenceCalibrationService.Applied("NO_CALIBRATION", null, null, null));
        properties = new RecommendationProperties();
        properties.setModel("scripted-test-model");
        // Sequentially scripted scenarios need a deterministic call order; concurrency scenarios opt back in.
        properties.setParallelSpecialists(false);
        // Scripted scenarios end at the manager's answer; critic scenarios opt back in and script the critic too.
        properties.setCriticRounds(0);
        when(freshness.ensure("AAPL")).thenReturn(new EnsureOutcome("FRESH", null, fresh(false)));
        when(freshness.assess("AAPL")).thenReturn(fresh(false));
        validators = Validation.buildDefaultValidatorFactory();
        beans.addBean("model", model);
        beans.addBean("broker", broker);
        beans.addBean("quant", quant);
        beans.addBean("quantProperties", new QuantProperties());
        service = service();
        when(quant.analyze(1)).thenReturn(analysis(List.of()));
        when(filings.retrieve(any())).thenReturn(new RetrievalResponse("AAPL", "risks", "FILTERED_VECTOR", true,
                5, 1, List.of(evidence())));
        when(broker.searchInstruments("AAPL")).thenReturn(List.of(instrument(1)));
        when(broker.getQuote(1)).thenReturn(quote("REALTIME", Instant.now()));
    }

    @AfterEach void close() { service.close(); validators.close(); }

    private RecommendationService service() {
        return new RecommendationService(beans.getBeanProvider(ChatModel.class), filings, freshness,
                beans.getBeanProvider(BrokerReadService.class), beans.getBeanProvider(QuantAnalysisService.class),
                beans.getBeanProvider(QuantProperties.class), store, beans.getBeanProvider(TrackRecordService.class),
                beans.getBeanProvider(ConfidenceCalibrationService.class), properties, validators.getValidator());
    }

    @Test void directionalConfidenceIsCalibratedThroughTheLatestSnapshotWhileTheRawFigureIsStored() {
        var bin = new ConfidenceCalibration.Bin(new BigDecimal("0.8"), new BigDecimal("1.0"), 20, new BigDecimal("0.9"),
                new BigDecimal("0.6"), new BigDecimal("0.386573"), new BigDecimal("0.781201"), new BigDecimal("0.583333"));
        var snapshot = new ConfidenceCalibration(9L, Instant.parse("2026-09-12T00:30:00Z"), 20, "READY", 40, 30, 10, new BigDecimal("0.55"),
                new BigDecimal("0.15"), new BigDecimal("0.29"), List.of(bin), Map.of("BULLISH", 40), Map.of("p", 40), ConfidenceCalibration.CAVEAT);
        when(calibrations.apply(new BigDecimal("0.90"))).thenReturn(new ConfidenceCalibrationService.Applied("APPLIED", new BigDecimal("0.583333"), snapshot, bin));
        scriptByRole(fullRunScript("BULLISH"));
        var result = service.recommend(request(false));
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.confidence()).as("raw input-coverage confidence is unchanged").isEqualByComparingTo("0.90");
        assertThat(result.calibratedConfidence()).isEqualByComparingTo("0.583333");
        assertThat(result.calibration()).isEqualTo(new RecommendationResponse.Calibration("APPLIED", 9L, Instant.parse("2026-09-12T00:30:00Z"), 20, 40,
                new BigDecimal("0.55"), 10, new BigDecimal("0.8"), new BigDecimal("1.0"), 20, new BigDecimal("0.6")));
        assertThat(result.limitations()).doesNotContain("CONFIDENCE_UNCALIBRATED");
        assertThat(result.toolTrace()).extracting(t -> t.tool() + ":" + t.outcome()).contains("MANAGER:calibrateConfidence:APPLIED");
        var records = org.mockito.ArgumentCaptor.forClass(RecommendationRecord.class);
        verify(store).save(records.capture());
        assertThat(records.getValue().confidence()).as("the audit row keeps the raw predictor").isEqualByComparingTo("0.90");
        assertThat(records.getValue().responseJson()).contains("\"calibratedConfidence\":0.583333").contains("\"calibrationId\":9");
        // Not enough scored runs yet: disclosed, nothing applied.
        when(calibrations.apply(any())).thenReturn(new ConfidenceCalibrationService.Applied("INSUFFICIENT_SAMPLE", null, snapshot, null));
        scriptByRole(fullRunScript("BEARISH"));
        var thin = service.recommend(request(false));
        assertThat(thin.calibratedConfidence()).isNull();
        assertThat(thin.calibration().status()).isEqualTo("INSUFFICIENT_SAMPLE");
        assertThat(thin.calibration().samples()).isEqualTo(40);
        assertThat(thin.calibration().binSamples()).isNull();
        assertThat(thin.limitations()).contains("CONFIDENCE_UNCALIBRATED");
        // NEUTRAL has no direction to calibrate against: no lookup, no trace entry.
        clearInvocations(calibrations);
        scriptByRole(fullRunScript("NEUTRAL"));
        var neutral = service.recommend(request(false));
        assertThat(neutral.confidence()).isEqualByComparingTo("0.90");
        assertThat(neutral.calibratedConfidence()).isNull();
        assertThat(neutral.calibration().status()).isEqualTo("NOT_DIRECTIONAL");
        assertThat(neutral.toolTrace()).extracting(t -> t.tool()).doesNotContain("MANAGER:calibrateConfidence");
        verifyNoInteractions(calibrations);
        // A failing calibration store is disclosed and the run completes.
        when(calibrations.apply(any())).thenThrow(new IllegalStateException("db"));
        scriptByRole(fullRunScript("BULLISH"));
        var failed = service.recommend(request(false));
        assertThat(failed.status()).isEqualTo("COMPLETE");
        assertThat(failed.calibration().status()).isEqualTo("UNAVAILABLE");
        assertThat(failed.toolTrace()).extracting(t -> t.tool() + ":" + t.outcome()).contains("MANAGER:calibrateConfidence:UNAVAILABLE");
        // No confidence at all means no calibration record either.
        when(model.call(any(Prompt.class))).thenReturn(answer("INSUFFICIENT_EVIDENCE", "[]"));
        assertThat(service.recommend(request(false)).calibration()).isNull();
    }

    @Test void priorRunsReachTheManagerAsEvidenceAndAreEchoedInTheResponse() {
        var prior = new TrackRecord("AAPL", 2, List.of(
                new TrackRecord.PriorRun("old-1", Instant.parse("2026-06-01T21:00:00Z"), "COMPLETE", "BULLISH", new BigDecimal("0.6"),
                        new BigDecimal("306.31"), LocalDate.of(2026, 6, 1), new BigDecimal("324.69"), new BigDecimal("297.12"),
                        List.of(new TrackRecord.HorizonOutcome(20, new BigDecimal("-0.055336"), null, false, "STOP_LOSS")))),
                List.of(new TrackRecord.AssessmentStats("BULLISH", 2, 1, 0, new BigDecimal("-0.055336"), 20)), TrackRecordService.CAVEAT);
        when(trackRecords.trackRecord("AAPL", 10)).thenReturn(prior);
        scriptByRole(fullRunScript("NEUTRAL"));
        var result = service.recommend(request(false));
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.trackRecord()).isEqualTo(prior);
        assertThat(result.toolTrace()).extracting(t -> t.tool() + ":" + t.outcome()).contains("MANAGER:reviewTrackRecord:OK");
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).call(prompts.capture());
        var manager = prompts.getAllValues().get(0);
        assertThat(manager.getInstructions()).as("system, request, track-record evidence").hasSize(3);
        assertThat(manager.toString()).contains("\"trackRecord\"").contains("STOP_LOSS").contains("not proof");
        // Track-record failure is disclosed and the run continues; no prior runs means no evidence message.
        doThrow(new IllegalStateException("db")).when(trackRecords).trackRecord("AAPL", 10);
        scriptByRole(fullRunScript("NEUTRAL"));
        var degraded = service.recommend(request(false));
        assertThat(degraded.status()).isEqualTo("COMPLETE");
        assertThat(degraded.limitations()).contains("reviewTrackRecord:TRACK_RECORD_UNAVAILABLE");
        assertThat(degraded.trackRecord()).isNull();
        doReturn(new TrackRecord("AAPL", 0, List.of(), List.of(), TrackRecordService.CAVEAT)).when(trackRecords).trackRecord("AAPL", 10);
        scriptByRole(fullRunScript("NEUTRAL"));
        var none = service.recommend(request(false));
        assertThat(none.toolTrace()).extracting(t -> t.tool() + ":" + t.outcome()).contains("MANAGER:reviewTrackRecord:NO_PRIOR_RUNS");
        assertThat(none.trackRecord()).isNull();
        properties.setTrackRecordRuns(0);
        clearInvocations(trackRecords);
        scriptByRole(fullRunScript("NEUTRAL"));
        service.recommend(request(false));
        verifyNoInteractions(trackRecords);
    }
    private static FilingFreshness fresh(boolean stale) {
        return new FilingFreshness("AAPL", Map.of("10-K", LocalDate.of(2025, 10, 31), "10-Q", LocalDate.of(2026, 7, 31)),
                LocalDate.of(2026, 7, 31), stale, stale ? null : Instant.parse("2026-09-12T00:00:00Z"), stale);
    }

    @Test void everyRunIsRecordedWithVersionTagsIncludingStoppedRuns() {
        scriptByRole(fullRunScript("BULLISH"));
        var result = service.recommend(request(false));
        var records = org.mockito.ArgumentCaptor.forClass(RecommendationRecord.class);
        verify(store).save(records.capture());
        var record = records.getValue();
        assertThat(record.runId()).isEqualTo(result.runId());
        assertThat(record.ticker()).isEqualTo("AAPL");
        assertThat(record.conid()).isEqualTo(1L);
        assertThat(record.status()).isEqualTo("COMPLETE");
        assertThat(record.assessment()).isEqualTo("BULLISH");
        assertThat(record.takeProfit()).isEqualByComparingTo("104.00");
        assertThat(record.confidence()).isEqualByComparingTo("0.90");
        assertThat(record.lastClose()).isEqualByComparingTo("100");
        assertThat(record.quoteAvailability()).isEqualTo("REALTIME");
        assertThat(record.citedChunkIds()).containsExactly(11L);
        assertThat(record.promptVersion()).isEqualTo(RecommendationService.PROMPT_VERSION);
        assertThat(record.model()).isEqualTo("scripted-test-model");
        assertThat(record.quantVersion()).isEqualTo(new QuantProperties().version());
        assertThat(record.processingVersion()).isEqualTo(project.stockrecommendationengine.rag.ingestion.FilingIngestionService.PROCESSING_VERSION);
        assertThat(record.responseJson()).contains("\"runId\":\"" + result.runId() + "\"").contains("\"takeProfit\":104.00");
        assertThat(record.requestedAt()).isBeforeOrEqualTo(record.completedAt());
        assertThat(result.limitations()).doesNotContain("AUDIT_NOT_PERSISTED");

        clearInvocations(store);
        when(model.call(any(Prompt.class))).thenReturn(calls(call("1", "placeOrder", "{}")));
        var stopped = service.recommend(request(false));
        verify(store).save(records.capture());
        assertThat(records.getValue().status()).isEqualTo("TOOL_NOT_ALLOWED");
        assertThat(records.getValue().runId()).isEqualTo(stopped.runId());
        assertThat(records.getValue().limitations()).contains("TOOL_NOT_ALLOWED");
    }

    @Test void deadlineStoppedRunsAreRecordedToo() throws Exception {
        properties.setDeadlineMs(100);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            try { new CountDownLatch(1).await(5, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            return calls(call("1", "findInstrument", "{}"));
        });
        var result = service.recommend(request(false));
        assertThat(result.status()).isEqualTo("DEADLINE_EXCEEDED");
        var records = org.mockito.ArgumentCaptor.forClass(RecommendationRecord.class);
        verify(store).save(records.capture());
        assertThat(records.getValue().status()).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(records.getValue().conid()).isNull();
    }

    @Test void auditWriteFailureIsDisclosedNotHidden() {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("db down")).when(store).save(any());
        scriptByRole(fullRunScript("NEUTRAL"));
        var result = service.recommend(request(false));
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.limitations()).contains("AUDIT_NOT_PERSISTED");
        assertThat(result.sources()).containsExactly(evidence());
    }

    /** Scripts responses per conversation role so specialists may run in any order or concurrently. */
    private void scriptByRole(Map<String, List<ChatResponse>> byRole) {
        Map<String, Deque<ChatResponse>> queues = new java.util.concurrent.ConcurrentHashMap<>();
        byRole.forEach((role, responses) -> queues.put(role, new java.util.concurrent.ConcurrentLinkedDeque<>(responses)));
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            String system = invocation.<Prompt>getArgument(0).getInstructions().get(0).getText();
            String role = system.contains("You are the manager") ? "MANAGER" : system.contains("You are the critic") ? "CRITIC"
                    : system.contains("RAG specialist") ? "RAG" : "BROKER";
            var next = queues.getOrDefault(role, new ArrayDeque<>()).pollFirst();
            if (next == null) throw new IllegalStateException("No scripted response for " + role);
            return next;
        });
    }
    private static Map<String, List<ChatResponse>> fullRunScript(String assessment) {
        return Map.of(
                "MANAGER", List.of(calls(call("m1", "researchFilings", "{}"), call("m2", "researchBroker", "{}")), answer(assessment, "[11]")),
                "RAG", List.of(calls(call("r1", "searchFilings", "{\"query\":\"risks\"}")), report()),
                "BROKER", List.of(calls(call("b1", "findInstrument", "{}")),
                        calls(call("b2", "getQuote", "{\"conid\":1}"), call("b3", "analyzePriceHistory", "{\"conid\":1}")), report()));
    }

    @Test void criticReviewsTheDraftWithoutToolsAndTheVerdictIsEchoed() {
        properties.setCriticRounds(2);
        scriptByRole(withCritic(fullRunScript("BULLISH"), verdict("ACCEPT", "[\"Reasoning matches the cited passage.\"]")));
        var result = service.recommend(request(false));
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.modelCalls()).isEqualTo(8);
        assertThat(result.takeProfit()).isEqualByComparingTo("104.00");
        assertThat(result.critique()).isEqualTo(new RecommendationResponse.Critique("ACCEPT",
                List.of("Reasoning matches the cited passage."), List.of(review("BULLISH", "ACCEPT", "Reasoning matches the cited passage.")), false, List.of()));
        assertThat(result.limitations()).doesNotContain("CRITIC_UNRESOLVED", "CRITIC_DISABLED").noneMatch(item -> item.startsWith("critic:"));
        assertThat(result.toolTrace()).extracting(t -> t.tool() + ":" + t.outcome()).contains("CRITIC:review:ACCEPT")
                .noneMatch(item -> item.startsWith("MANAGER:revise"));
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(8)).call(prompts.capture());
        var critic = prompts.getAllValues().get(7);
        assertThat(critic.getInstructions().get(0).getText()).contains("You are the critic");
        assertThat(critic.getInstructions()).as("system, request, draft and evidence").hasSize(3);
        assertThat(critic.toString()).contains("\"assessment\":\"BULLISH\"").contains("Material business risks.")
                .contains("\"priceAnalysis\"").contains("104.00").contains("\"numeralsNotFoundInEvidence\":[]");
        assertThat(((org.springframework.ai.model.tool.ToolCallingChatOptions) critic.getOptions()).getToolCallbacks()).isEmpty();
        var records = org.mockito.ArgumentCaptor.forClass(RecommendationRecord.class);
        verify(store).save(records.capture());
        assertThat(records.getValue().responseJson()).contains("\"critique\":{\"verdict\":\"ACCEPT\"");
        assertThat(records.getValue().promptVersion()).isEqualTo("manager-specialists-v4-critic");
    }

    @Test void criticIssuesTriggerOneRevisionOnTheManagerConversationWhichIsReviewedAgain() {
        properties.setCriticRounds(2);
        var script = new java.util.HashMap<>(fullRunScript("BULLISH"));
        script.put("MANAGER", List.of(calls(call("m1", "researchFilings", "{}"), call("m2", "researchBroker", "{}")),
                answer("BULLISH", "[11]"),
                text("{\"assessment\":\"NEUTRAL\",\"reasoning\":\"The filing describes material business risks; the 20-day SMA of 99 sits above the 50-day SMA of 95.\",\"citedChunkIds\":[11]}")));
        scriptByRole(withCritic(script, verdict("REVISE", "[\"The bullish call is not supported by the risk passage.\"]"), verdict("ACCEPT", "[]")));
        var result = service.recommend(request(false));
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.assessment()).as("the revised answer is the one returned").isEqualTo("NEUTRAL");
        assertThat(result.reasoning()).contains("20-day SMA of 99");
        assertThat(result.takeProfit()).as("levels follow the revised assessment").isNull();
        assertThat(result.modelCalls()).isEqualTo(10);
        assertThat(result.critique()).as("history keeps the first critique and the assessment it judged").isEqualTo(new RecommendationResponse.Critique("ACCEPT", List.of(),
                List.of(review("BULLISH", "REVISE", "The bullish call is not supported by the risk passage."), review("NEUTRAL", "ACCEPT")), true, List.of()));
        assertThat(result.limitations()).doesNotContain("CRITIC_UNRESOLVED").noneMatch(item -> item.startsWith("revise:"));
        assertThat(result.toolTrace()).extracting(t -> t.tool() + ":" + t.outcome())
                .containsSubsequence("CRITIC:review:REVISE", "MANAGER:revise:OK", "CRITIC:review:ACCEPT");
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(10)).call(prompts.capture());
        var revision = prompts.getAllValues().get(8);
        assertThat(revision.getInstructions().get(0).getText()).contains("You are the manager");
        assertThat(revision.getInstructions()).as("the manager continues its own history: draft and critique appended")
                .anyMatch(message -> message instanceof AssistantMessage assistant && assistant.getText().contains("\"assessment\":\"BULLISH\""))
                .last().satisfies(message -> assertThat(message.getText()).contains("The bullish call is not supported").contains("Revise your answer"));
        var records = org.mockito.ArgumentCaptor.forClass(RecommendationRecord.class);
        verify(store).save(records.capture());
        assertThat(records.getValue().assessment()).isEqualTo("NEUTRAL");
    }

    @Test void unresolvedCritiqueIsDisclosedAndTheLastValidatedDraftStands() {
        properties.setCriticRounds(1);
        scriptByRole(withCritic(fullRunScript("BULLISH"), verdict("REVISE", "[\"Unsupported claim.\"]")));
        var single = service.recommend(request(false));
        assertThat(single.status()).isEqualTo("COMPLETE");
        assertThat(single.assessment()).isEqualTo("BULLISH");
        assertThat(single.modelCalls()).as("no revision when no review remains").isEqualTo(8);
        assertThat(single.critique()).isEqualTo(new RecommendationResponse.Critique("REVISE", List.of("Unsupported claim."), List.of(review("BULLISH", "REVISE", "Unsupported claim.")), false, List.of()));
        assertThat(single.limitations()).contains("CRITIC_UNRESOLVED");
        assertThat(single.toolTrace()).extracting(t -> t.tool()).doesNotContain("MANAGER:revise");

        properties.setCriticRounds(2);
        var script = new java.util.HashMap<>(fullRunScript("BULLISH"));
        script.put("MANAGER", List.of(calls(call("m1", "researchFilings", "{}"), call("m2", "researchBroker", "{}")),
                answer("BULLISH", "[11]"), answer("BEARISH", "[11]")));
        scriptByRole(withCritic(script, verdict("REVISE", "[\"First issue.\"]"), verdict("REVISE", "[\"Still unsupported.\"]")));
        var twice = service.recommend(request(false));
        assertThat(twice.assessment()).isEqualTo("BEARISH");
        assertThat(twice.takeProfit()).isEqualByComparingTo("96.00");
        assertThat(twice.critique()).isEqualTo(new RecommendationResponse.Critique("REVISE", List.of("Still unsupported."),
                List.of(review("BULLISH", "REVISE", "First issue."), review("BEARISH", "REVISE", "Still unsupported.")), true, List.of()));
        assertThat(twice.limitations()).contains("CRITIC_UNRESOLVED");
    }

    @Test void criticOrRevisionFailuresNeverStopAValidatedRun() {
        properties.setCriticRounds(2);
        // Malformed verdicts, a verdict with the wrong shape, and a tool call from the tool-less critic.
        for (var bad : List.of(text("not json"), text("{\"verdict\":\"REVISE\",\"issues\":[]}"),
                text("{\"verdict\":\"ACCEPT\"}"), text("{\"verdict\":\"MAYBE\",\"issues\":[]}"))) {
            scriptByRole(withCritic(fullRunScript("BULLISH"), bad));
            var result = service.recommend(request(false));
            assertThat(result.status()).isEqualTo("COMPLETE");
            assertThat(result.assessment()).isEqualTo("BULLISH");
            assertThat(result.critique()).isEqualTo(new RecommendationResponse.Critique("UNAVAILABLE", List.of(), List.of(), false, List.of()));
            assertThat(result.limitations()).contains("critic:INVALID_CRITIC_OUTPUT").doesNotContain("CRITIC_UNRESOLVED");
        }
        clearInvocations(filings);
        scriptByRole(withCritic(fullRunScript("BULLISH"), calls(call("c", "searchFilings", "{\"query\":\"x\"}"))));
        var toolCall = service.recommend(request(false));
        assertThat(toolCall.status()).isEqualTo("COMPLETE");
        assertThat(toolCall.limitations()).contains("critic:TOOL_NOT_ALLOWED");
        verify(filings, times(1)).retrieve(any()); // the RAG specialist's search only; the critic's call never executed
        // The shared model budget ends exactly at the critic: disclosed, not fatal.
        properties.setMaxModelCalls(7);
        scriptByRole(withCritic(fullRunScript("BULLISH"), verdict("ACCEPT", "[]")));
        var exhausted = service.recommend(request(false));
        assertThat(exhausted.status()).isEqualTo("COMPLETE");
        assertThat(exhausted.modelCalls()).isEqualTo(7);
        assertThat(exhausted.limitations()).contains("critic:MODEL_CALL_LIMIT");
        assertThat(exhausted.critique().verdict()).isEqualTo("UNAVAILABLE");
        // A revision that fails validation leaves the reviewed draft standing with both facts disclosed.
        properties.setMaxModelCalls(14);
        var script = new java.util.HashMap<>(fullRunScript("BULLISH"));
        script.put("MANAGER", List.of(calls(call("m1", "researchFilings", "{}"), call("m2", "researchBroker", "{}")),
                answer("BULLISH", "[11]"), answer("BEARISH", "[999]")));
        scriptByRole(withCritic(script, verdict("REVISE", "[\"Issue.\"]"), verdict("ACCEPT", "[]")));
        var rejected = service.recommend(request(false));
        assertThat(rejected.status()).isEqualTo("COMPLETE");
        assertThat(rejected.assessment()).isEqualTo("BULLISH");
        assertThat(rejected.takeProfit()).isEqualByComparingTo("104.00");
        assertThat(rejected.modelCalls()).isEqualTo(9);
        assertThat(rejected.limitations()).contains("revise:INVALID_CITATION", "CRITIC_UNRESOLVED");
        assertThat(rejected.critique()).isEqualTo(new RecommendationResponse.Critique("REVISE", List.of("Issue."), List.of(review("BULLISH", "REVISE", "Issue.")), false, List.of()));
        assertThat(rejected.toolTrace()).extracting(t -> t.tool() + ":" + t.outcome()).contains("MANAGER:revise:INVALID_CITATION");
        // Disabled: no critic call, no critique, a limitation instead.
        properties.setCriticRounds(0);
        scriptByRole(fullRunScript("BULLISH"));
        var disabled = service.recommend(request(false));
        assertThat(disabled.modelCalls()).isEqualTo(7);
        assertThat(disabled.critique()).isNull();
        assertThat(disabled.limitations()).contains("CRITIC_DISABLED");
    }

    @Test void numeralsAbsentFromTheEvidenceAreFlaggedForTheCritic() {
        String evidence = "{\"passages\":[\"Net sales of $391,035 million in fiscal 2025.\"],\"priceAnalysis\":{\"lastClose\":326.57,\"momentum\":0.0805,\"longSmaPeriod\":50},\"dataFreshness\":{\"10-K\":\"2025-10-31\"}}";
        var flagged = RecommendationService.unsupportedNumerals(
                "Per the 10-K (Item 1A) net sales were $391,035 million in fiscal 2025; the close of 326.57 is above the 50-day SMA, "
                        + "momentum is 8.05%, and revenue grew 12% to 1,200.5 billion. See Part II, Item 7 and the 10-Q.", evidence);
        assertThat(flagged).containsExactly("8.05", "12", "1200.5");
        assertThat(RecommendationService.unsupportedNumerals("No numbers here.", evidence)).isEmpty();
        // In a run, the flagged list reaches both the critic prompt and the response.
        properties.setCriticRounds(1);
        var script = new java.util.HashMap<>(fullRunScript("BULLISH"));
        script.put("MANAGER", List.of(calls(call("m1", "researchFilings", "{}"), call("m2", "researchBroker", "{}")),
                text("{\"assessment\":\"BULLISH\",\"reasoning\":\"Close 100 with ATR 2.0 and revenue of 98765 million.\",\"citedChunkIds\":[11]}")));
        scriptByRole(withCritic(script, verdict("ACCEPT", "[]")));
        var result = service.recommend(request(false));
        assertThat(result.critique().unsupportedNumerals()).containsExactly("98765");
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(8)).call(prompts.capture());
        assertThat(prompts.getAllValues().get(7).toString()).contains("\"numeralsNotFoundInEvidence\":[\"98765\"]");
    }
    private static Map<String, List<ChatResponse>> withCritic(Map<String, List<ChatResponse>> script, ChatResponse... verdicts) {
        var withCritic = new java.util.HashMap<>(script);
        withCritic.put("CRITIC", List.of(verdicts));
        return withCritic;
    }
    private static RecommendationResponse.Review review(String assessment, String verdict, String... issues) {
        return new RecommendationResponse.Review(assessment, verdict, List.of(issues));
    }
    private static ChatResponse verdict(String verdict, String issues) {
        return text("{\"verdict\":\"" + verdict + "\",\"issues\":" + issues + "}");
    }

    @Test void specialistsRunConcurrentlyWhenTheManagerDelegatesToBoth() throws Exception {
        properties.setParallelSpecialists(true);
        properties.setDeadlineMs(5000);
        var bothStarted = new CyclicBarrier(2);
        var ragArrived = new java.util.concurrent.atomic.AtomicBoolean();
        var brokerArrived = new java.util.concurrent.atomic.AtomicBoolean();
        when(filings.retrieve(any())).thenAnswer(invocation -> {
            // Only passes when the broker specialist is running at the same time; later calls pass through.
            if (!ragArrived.getAndSet(true)) bothStarted.await(2, TimeUnit.SECONDS);
            return new RetrievalResponse("AAPL", "risks", "FILTERED_VECTOR", true, 5, 1, List.of(evidence()));
        });
        when(broker.searchInstruments("AAPL")).thenAnswer(invocation -> {
            if (!brokerArrived.getAndSet(true)) bothStarted.await(2, TimeUnit.SECONDS);
            return List.of(instrument(1));
        });
        scriptByRole(fullRunScript("BULLISH"));
        var result = service.recommend(request(false));
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.modelCalls()).isEqualTo(7);
        assertThat(result.takeProfit()).isEqualByComparingTo("104.00");
        assertThat(result.toolTrace()).extracting(t -> t.tool()).contains("RAG:searchFilings",
                "MANAGER:researchFilings", "BROKER:findInstrument", "BROKER:getQuote", "BROKER:analyzePriceHistory", "MANAGER:researchBroker");
        assertThat(result.sources()).containsExactly(evidence());
        assertThat(result.quotes()).hasSize(1);
    }

    @Test void sequentialModeStillCompletesTheSameScript() {
        scriptByRole(fullRunScript("NEUTRAL"));
        var result = service.recommend(request(false));
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.modelCalls()).isEqualTo(7);
    }

    @Test void sharedBudgetsAndLimitsHoldAcrossConcurrentSpecialists() {
        properties.setParallelSpecialists(true);
        properties.setMaxModelCalls(3);
        scriptByRole(fullRunScript("NEUTRAL"));
        assertThat(service.recommend(request(false)).status()).isEqualTo("MODEL_CALL_LIMIT");
        verify(model, atMost(3)).call(any(Prompt.class));
        scriptByRole(Map.of(
                "MANAGER", List.of(calls(call("m1", "researchFilings", "{}"), call("m2", "researchBroker", "{}"))),
                "RAG", List.of(calls(call("r1", "placeOrder", "{}"))),
                "BROKER", List.of(calls(call("b1", "findInstrument", "{}")), report())));
        properties.setMaxModelCalls(10);
        assertThat(service.recommend(request(false)).status()).isEqualTo("TOOL_NOT_ALLOWED");
    }

    @Test void deadlineCancelsBothConcurrentSpecialists() throws Exception {
        properties.setParallelSpecialists(true);
        properties.setDeadlineMs(300);
        var interrupted = new CountDownLatch(2);
        scriptByRole(Map.of("MANAGER", List.of(calls(call("m1", "researchFilings", "{}"), call("m2", "researchBroker", "{}")))));
        when(filings.retrieve(any())).thenAnswer(invocation -> block(interrupted));
        when(broker.searchInstruments("AAPL")).thenAnswer(invocation -> block(interrupted));
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            String system = invocation.<Prompt>getArgument(0).getInstructions().get(0).getText();
            if (system.contains("You are the manager")) return calls(call("m1", "researchFilings", "{}"), call("m2", "researchBroker", "{}"));
            return block(interrupted);
        });
        assertThat(service.recommend(request(false)).status()).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).as("both specialist threads were interrupted").isTrue();
    }
    private static <T> T block(CountDownLatch interrupted) throws InterruptedException {
        try { new CountDownLatch(1).await(5, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { interrupted.countDown(); throw ex; }
        throw new IllegalStateException("not interrupted");
    }

    @Test void filingsAreEnsuredBeforeRagResearchAndFreshnessIsReported() {
        var refresh = new FilingRefreshResult("AAPL", Instant.now(), 5, List.of("acc-1"), List.of("acc-1"), List.of());
        when(freshness.ensure("AAPL")).thenReturn(new EnsureOutcome("INGESTED", refresh, fresh(false)));
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchFilings", "{}")),
                calls(call("r", "searchFilings", "{\"query\":\"risks\"}")), report(), answer("NEUTRAL", "[11]"));
        var result = service.recommend(request(false));
        var order = inOrder(freshness, filings);
        order.verify(freshness).ensure("AAPL");
        order.verify(filings).retrieve(any());
        assertThat(result.toolTrace()).extracting(t -> t.tool() + ":" + t.outcome()).contains("RAG:ensureFilings:INGESTED");
        assertThat(result.limitations()).noneMatch(item -> item.startsWith("ensureFilings") || item.equals("FILINGS_MAY_BE_STALE"));
        assertThat(result.dataFreshness().latestFilingDates()).containsEntry("10-Q", LocalDate.of(2026, 7, 31));
        assertThat(result.dataFreshness().filingsMayBeStale()).isFalse();
        assertThat(result.dataFreshness().quoteAvailability()).isNull();
    }

    @Test void staleFilingsAreDisclosedAndTheSwitchDisablesRefresh() {
        when(freshness.ensure("AAPL")).thenReturn(new EnsureOutcome("REFRESH_FAILED",
                new FilingRefreshResult("AAPL", Instant.now(), 5, List.of("acc-2"), List.of(), List.of("acc-2")), fresh(true)));
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchFilings", "{}")),
                calls(call("r", "searchFilings", "{\"query\":\"risks\"}")), report(), answer("NEUTRAL", "[11]"));
        var result = service.recommend(request(false));
        assertThat(result.limitations()).contains("ensureFilings:REFRESH_FAILED", "FILINGS_MAY_BE_STALE");
        assertThat(result.dataFreshness().filingsMayBeStale()).isTrue();
        properties.setAutoIngest(false);
        when(freshness.assess("AAPL")).thenReturn(fresh(true));
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchFilings", "{}")),
                calls(call("r", "searchFilings", "{\"query\":\"risks\"}")), report(), answer("NEUTRAL", "[11]"));
        var disabled = service.recommend(request(false));
        verify(freshness, times(1)).ensure("AAPL");
        assertThat(disabled.toolTrace()).extracting(t -> t.tool() + ":" + t.outcome()).contains("RAG:ensureFilings:AUTO_INGEST_DISABLED");
        assertThat(disabled.limitations()).contains("FILINGS_MAY_BE_STALE");
    }

    @Test void ingestionFailuresBecomeLimitationsAndResearchContinues() {
        doThrow(new UnknownTickerException("AAPL")).when(freshness).ensure("AAPL");
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchFilings", "{}")),
                calls(call("r", "searchFilings", "{\"query\":\"risks\"}")), report(), answer("INSUFFICIENT_EVIDENCE", "[]"));
        var result = service.recommend(request(false));
        assertThat(result.limitations()).contains("ensureFilings:TICKER_NOT_FOUND");
        verify(filings).retrieve(any());
        doThrow(new IllegalStateException("SEC unavailable")).when(freshness).ensure("AAPL");
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchFilings", "{}")),
                calls(call("r", "searchFilings", "{\"query\":\"risks\"}")), report(), answer("INSUFFICIENT_EVIDENCE", "[]"));
        assertThat(service.recommend(request(false)).limitations()).contains("ensureFilings:INGESTION_FAILED");
    }

    @Test void deterministicLevelsAndConfidenceComeFromApplicationStateByDirection() {
        when(model.call(any(Prompt.class))).thenReturn(
                calls(call("m1", "researchFilings", "{}"), call("m2", "researchBroker", "{}")),
                calls(call("r1", "searchFilings", "{\"query\":\"risks\"}")), report(),
                calls(call("b1", "findInstrument", "{}")),
                calls(call("b2", "getQuote", "{\"conid\":1}"), call("b3", "analyzePriceHistory", "{\"conid\":1}")),
                report(), answer("BULLISH", "[11]"));
        var bullish = service.recommend(request(false));
        assertThat(bullish.status()).isEqualTo("COMPLETE");
        assertThat(bullish.takeProfit()).isEqualByComparingTo("104.00");
        assertThat(bullish.stopLoss()).isEqualByComparingTo("98.00");
        assertThat(bullish.confidence()).isEqualByComparingTo("0.90");
        assertThat(bullish.priceAnalysis()).isNotNull();
        assertThat(bullish.limitations()).contains("POSITION_SIZING_NOT_IMPLEMENTED", "CONFIDENCE_UNCALIBRATED")
                .doesNotContain("NO_PRICE_HISTORY", "QUANT_DISABLED");
        assertThat(bullish.toolTrace()).extracting(t -> t.tool()).contains("BROKER:analyzePriceHistory");
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(7)).call(prompts.capture());
        assertThat(prompts.getAllValues().get(6).toString()).contains("\"priceAnalysis\"").contains("104.00");

        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchBroker", "{}")),
                calls(call("b1", "findInstrument", "{}")), calls(call("b2", "analyzePriceHistory", "{\"conid\":1}")),
                report(), answer("BEARISH", "[11]"));
        assertThat(service.recommend(request(false)).status()).isEqualTo("INVALID_CITATION");
        when(model.call(any(Prompt.class))).thenReturn(
                calls(call("m1", "researchFilings", "{}"), call("m2", "researchBroker", "{}")),
                calls(call("r1", "searchFilings", "{\"query\":\"risks\"}")), report(),
                calls(call("b1", "findInstrument", "{}")), calls(call("b2", "analyzePriceHistory", "{\"conid\":1}")),
                report(), answer("BEARISH", "[11]"));
        var bearish = service.recommend(request(false));
        assertThat(bearish.status()).as("quote prefetched even though the model never called getQuote").isEqualTo("COMPLETE");
        assertThat(bearish.takeProfit()).isEqualByComparingTo("96.00");
        assertThat(bearish.stopLoss()).isEqualByComparingTo("102.00");
        assertThat(bearish.confidence()).isEqualByComparingTo("0.90");
    }

    @Test void brokerEvidenceIsPrefetchedEvenWhenTheSpecialistModelCallsNoTool() {
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchBroker", "{}")), report(),
                answer("INSUFFICIENT_EVIDENCE", "[]"));
        var result = service.recommend(request(false));
        assertThat(result.quotes()).hasSize(1);
        assertThat(result.priceAnalysis()).isNotNull();
        assertThat(result.toolTrace()).extracting(t -> t.tool() + ":" + t.outcome())
                .containsExactly("MANAGER:reviewTrackRecord:NO_PRIOR_RUNS", "BROKER:findInstrument:OK", "BROKER:getQuote:OK",
                        "BROKER:analyzePriceHistory:OK", "MANAGER:researchBroker:OK");
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(3)).call(prompts.capture());
        var specialistPrompt = prompts.getAllValues().get(1);
        assertThat(specialistPrompt.getInstructions()).hasSize(3);
        assertThat(specialistPrompt.toString()).contains("Evidence already retrieved").contains("104.00");
        // Ambiguity without a user conid: discovery only, nothing fetched, the model must ask for a conid.
        when(broker.searchInstruments("AAPL")).thenReturn(List.of(instrument(1), instrument(2)));
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchBroker", "{}")), report(),
                answer("INSUFFICIENT_EVIDENCE", "[]"));
        clearInvocations(broker);
        var ambiguous = service.recommend(request(false));
        assertThat(ambiguous.quotes()).isEmpty();
        assertThat(ambiguous.limitations()).contains("prefetch:AMBIGUOUS_CONTRACT");
        verify(broker, never()).getQuote(anyLong());
        // An explicit request conid among several listings is used directly.
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchBroker", "{}")), report(),
                answer("INSUFFICIENT_EVIDENCE", "[]"));
        var selected = service.recommend(new RecommendationRequest("AAPL", "Assess", 1L, false));
        assertThat(selected.quotes()).hasSize(1);
        assertThat(selected.limitations()).doesNotContain("prefetch:AMBIGUOUS_CONTRACT");
    }

    @Test void severalListingsResolveToTheSinglePreferredCurrencyListingAndDiscloseIt() {
        when(broker.searchInstruments("AAPL")).thenReturn(List.of(
                new Instrument(7, "AAPL", "Apple", "MEXI", "MXN", "STK"), instrument(1),
                new Instrument(8, "AAPL", "Apple", "TSE", "CAD", "STK")));
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchBroker", "{}")),
                calls(call("b", "getQuote", "{\"conid\":8}")), report(), answer("INSUFFICIENT_EVIDENCE", "[]"));
        var result = service.recommend(request(false));
        assertThat(result.quotes()).extracting(q -> q.conid()).containsExactly(1L);
        assertThat(result.priceAnalysis()).isNotNull();
        assertThat(result.limitations()).contains("CONTRACT_SELECTED_BY_POLICY:1:NASDAQ:USD", "getQuote:AMBIGUOUS_CONTRACT")
                .doesNotContain("prefetch:AMBIGUOUS_CONTRACT");
        verify(broker, never()).getQuote(8);
        // Two listings in the preferred currency remain ambiguous.
        when(broker.searchInstruments("AAPL")).thenReturn(List.of(instrument(1), instrument(2)));
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchBroker", "{}")), report(), answer("INSUFFICIENT_EVIDENCE", "[]"));
        assertThat(service.recommend(request(false)).limitations()).contains("prefetch:AMBIGUOUS_CONTRACT")
                .noneMatch(item -> item.startsWith("CONTRACT_SELECTED_BY_POLICY"));
    }

    @Test void neutralAndInsufficientAssessmentsCarryNoLevels() {
        when(model.call(any(Prompt.class))).thenReturn(
                calls(call("m1", "researchFilings", "{}"), call("m2", "researchBroker", "{}")),
                calls(call("r1", "searchFilings", "{\"query\":\"risks\"}")), report(),
                calls(call("b1", "findInstrument", "{}")), calls(call("b2", "analyzePriceHistory", "{\"conid\":1}")),
                report(), answer("NEUTRAL", "[11]"));
        var neutral = service.recommend(request(false));
        assertThat(neutral.takeProfit()).isNull();
        assertThat(neutral.stopLoss()).isNull();
        assertThat(neutral.confidence()).isEqualByComparingTo("0.90");
        assertThat(neutral.priceAnalysis()).isNotNull();
        when(model.call(any(Prompt.class))).thenReturn(answer("INSUFFICIENT_EVIDENCE", "[]"));
        var insufficient = service.recommend(request(false));
        assertThat(insufficient.confidence()).isNull();
        assertThat(insufficient.limitations()).contains("NO_PRICE_HISTORY");
    }

    @Test void perContractToolsDiscoverImplicitlyButStillRequireAMatchingContract() {
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchBroker", "{}")),
                calls(call("b1", "getQuote", "{\"conid\":1}"), call("b2", "analyzePriceHistory", "{\"conid\":1}")),
                report(), answer("INSUFFICIENT_EVIDENCE", "[]"));
        var result = service.recommend(request(false));
        assertThat(result.quotes()).hasSize(1);
        assertThat(result.priceAnalysis()).isNotNull();
        assertThat(result.limitations()).doesNotContain("getQuote:CONTRACT_NOT_DISCOVERED", "analyzePriceHistory:CONTRACT_NOT_DISCOVERED");
        verify(broker, times(1)).searchInstruments("AAPL");
        when(broker.searchInstruments("AAPL")).thenReturn(List.of(instrument(2)));
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchBroker", "{}")),
                calls(call("b", "analyzePriceHistory", "{\"conid\":1}")), report(), answer("INSUFFICIENT_EVIDENCE", "[]"));
        assertThat(service.recommend(request(false)).limitations()).contains("analyzePriceHistory:CONTRACT_NOT_DISCOVERED");
        verify(quant, times(2)).analyze(1); // prefetch and the model's own call in the first run; none in the second
        verify(quant, times(1)).analyze(2); // prefetch on the only discovered listing in the second run
    }

    @Test void quantToolSurfacesAnalysisLimitations() {
        when(broker.searchInstruments("AAPL")).thenReturn(List.of(instrument(1)));
        when(quant.analyze(1)).thenThrow(new IllegalArgumentException("INSUFFICIENT_BARS"));
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchBroker", "{}")),
                calls(call("b1", "findInstrument", "{}")), calls(call("b2", "analyzePriceHistory", "{\"conid\":1}")),
                report(), answer("INSUFFICIENT_EVIDENCE", "[]"));
        assertThat(service.recommend(request(false)).limitations()).contains("analyzePriceHistory:INSUFFICIENT_BARS", "NO_PRICE_HISTORY");
        reset(quant);
        when(quant.analyze(1)).thenReturn(analysis(List.of("STALE_BARS")));
        when(model.call(any(Prompt.class))).thenReturn(
                calls(call("m1", "researchFilings", "{}"), call("m2", "researchBroker", "{}")),
                calls(call("r1", "searchFilings", "{\"query\":\"risks\"}")), report(),
                calls(call("b1", "findInstrument", "{}")), calls(call("b2", "analyzePriceHistory", "{\"conid\":1}")),
                report(), answer("BULLISH", "[11]"));
        var stale = service.recommend(request(false));
        assertThat(stale.takeProfit()).isNull();
        assertThat(stale.limitations()).contains("analyzePriceHistory:STALE_BARS");
        assertThat(stale.confidence()).isEqualByComparingTo("0.78");
    }

    @Test void quantToolIsAbsentWhenTheModuleIsDisabled() {
        service.close();
        var withoutQuant = new StaticListableBeanFactory();
        withoutQuant.addBean("model", model);
        withoutQuant.addBean("broker", broker);
        service = new RecommendationService(withoutQuant.getBeanProvider(ChatModel.class), filings, freshness,
                withoutQuant.getBeanProvider(BrokerReadService.class), withoutQuant.getBeanProvider(QuantAnalysisService.class),
                withoutQuant.getBeanProvider(QuantProperties.class), store, withoutQuant.getBeanProvider(TrackRecordService.class),
                withoutQuant.getBeanProvider(ConfidenceCalibrationService.class), properties, validators.getValidator());
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchBroker", "{}")),
                calls(call("b1", "findInstrument", "{}")), calls(call("b2", "analyzePriceHistory", "{\"conid\":1}")));
        var result = service.recommend(request(false));
        assertThat(result.status()).isEqualTo("TOOL_NOT_ALLOWED");
        assertThat(result.limitations()).contains("QUANT_DISABLED");
        verifyNoInteractions(quant);
    }

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
        assertThat(result.toolTrace()).extracting(t -> t.tool()).containsExactly("MANAGER:reviewTrackRecord", "RAG:ensureFilings", "RAG:searchFilings",
                "MANAGER:researchFilings", "BROKER:findInstrument", "BROKER:getQuote", "BROKER:analyzePriceHistory", // deterministic prefetch
                "BROKER:findInstrument", "BROKER:getQuote", "MANAGER:researchBroker");
        verify(broker, never()).getPositions();
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(7)).call(prompts.capture());
        var all = prompts.getAllValues();
        assertThat(all.get(1).getInstructions()).hasSize(2);
        assertThat(all.get(3).getInstructions()).as("system, request, prefetched evidence").hasSize(3);
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
        clearInvocations(broker);
        when(model.call(any(Prompt.class))).thenReturn(calls(call("m", "researchBroker", "{}")),
                calls(call("b1", "findInstrument", "{}")), calls(call("b2", "getQuote", "{\"conid\":1}")),
                report(), answer("INSUFFICIENT_EVIDENCE", "[]"));
        assertThat(service.recommend(request(false)).limitations()).contains("getQuote:AMBIGUOUS_CONTRACT");
        verify(broker, never()).getQuote(anyLong());
    }

    private static ChatResponse report() { return text("{\"summary\":\"Findings from the available evidence.\"}"); }

    @Test void providerFailuresStopWithANamedCodeAndSpareAValidatedDraft() {
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("503 Service Unavailable"));
        var stopped = service.recommend(request(false));
        assertThat(stopped.status()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(stopped.limitations()).contains("MODEL_UNAVAILABLE").doesNotContain("MODEL_RATE_LIMITED_RETRIED");
        verify(model, times(1)).call(any(Prompt.class));
        assertThat(stopped.toolTrace()).extracting(t -> t.tool()).as("counters and trace survive").contains("MANAGER:reviewTrackRecord");
        var records = org.mockito.ArgumentCaptor.forClass(RecommendationRecord.class);
        verify(store).save(records.capture());
        assertThat(records.getValue().status()).isEqualTo("MODEL_UNAVAILABLE");
        // Same failure at the critic: the manager's validated answer is returned with the critic's absence disclosed.
        properties.setCriticRounds(2);
        var script = fullRunScript("BULLISH");
        var queues = new java.util.HashMap<String, java.util.Deque<ChatResponse>>();
        script.forEach((role, responses) -> queues.put(role, new java.util.ArrayDeque<>(responses)));
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            String system = invocation.<Prompt>getArgument(0).getInstructions().get(0).getText();
            if (system.contains("You are the critic")) throw new IllegalStateException("503");
            String role = system.contains("You are the manager") ? "MANAGER" : system.contains("RAG specialist") ? "RAG" : "BROKER";
            return queues.get(role).pollFirst();
        });
        var spared = service.recommend(request(false));
        assertThat(spared.status()).isEqualTo("COMPLETE");
        assertThat(spared.assessment()).isEqualTo("BULLISH");
        assertThat(spared.limitations()).contains("critic:MODEL_UNAVAILABLE");
        assertThat(spared.critique().verdict()).isEqualTo("UNAVAILABLE");
    }

    @Test void aRateLimitedModelCallIsRetriedOnceAndDisclosed() {
        properties.setRateLimitRetryMs(10);
        properties.setCriticRounds(0);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("429: Rate limit reached for gpt-4.1 on tokens per min");
            return answer("INSUFFICIENT_EVIDENCE", "[]");
        });
        var result = service.recommend(request(false));
        assertThat(result.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.limitations()).contains("MODEL_RATE_LIMITED_RETRIED");
        assertThat(result.modelCalls()).as("the failed attempt is not a second budgeted call").isEqualTo(1);
        verify(model, times(2)).call(any(Prompt.class));
        // A second rate limit in a row stops the run; with the retry disabled the first one does.
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("429 again"));
        assertThat(service.recommend(request(false)).status()).isEqualTo("MODEL_UNAVAILABLE");
        properties.setRateLimitRetryMs(0);
        clearInvocations(model);
        assertThat(service.recommend(request(false)).status()).isEqualTo("MODEL_UNAVAILABLE");
        verify(model, times(1)).call(any(Prompt.class));
    }

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
                .usage(new org.springframework.ai.chat.metadata.DefaultUsage(40000, 100)).build();
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
        return new Quote(1, new BigDecimal("100"), null, null, null, updated, Instant.now(), availability, "RpB");
    }
    private static QuantAnalysis analysis(List<String> limitations) {
        boolean stale = limitations.contains("STALE_BARS");
        return new QuantAnalysis(1, "AAPL", "USD", "TWS_DAILY_TRADES", false, Instant.now(), LocalDate.now(), 80,
                new BigDecimal("100"), new BigDecimal("2.0000"), 14, new BigDecimal("0.2500"), 20, new BigDecimal("99"), 20,
                new BigDecimal("95"), 50, new BigDecimal("0.05"), "ABOVE_LONG_SMA",
                stale ? null : new QuantAnalysis.Levels(new BigDecimal("104.00"), new BigDecimal("98.00")),
                stale ? null : new QuantAnalysis.Levels(new BigDecimal("96.00"), new BigDecimal("102.00")),
                "ATR_MULTIPLE_OF_LAST_CLOSE", limitations);
    }
    private static RetrievedFilingChunk evidence() {
        return new RetrievedFilingChunk(11L, 1L, "AAPL", "0000320193", "accession", "10-K", LocalDate.of(2025, 10, 31),
                LocalDate.of(2025, 9, 30), "ITEM_1A", "Risk factors", 0, "Material business risks.", "https://www.sec.gov/example", 0.8);
    }
}
