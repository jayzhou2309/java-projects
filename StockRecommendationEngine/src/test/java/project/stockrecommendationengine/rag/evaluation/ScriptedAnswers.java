package project.stockrecommendationengine.rag.evaluation;

import jakarta.validation.Validation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import project.stockrecommendationengine.broker.BrokerReadService;
import project.stockrecommendationengine.outcome.ConfidenceCalibrationService;
import project.stockrecommendationengine.outcome.TrackRecordService;
import project.stockrecommendationengine.quant.QuantAnalysisService;
import project.stockrecommendationengine.quant.QuantProperties;
import project.stockrecommendationengine.rag.dto.RetrievalRequest;
import project.stockrecommendationengine.rag.dto.RetrievalResponse;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationQuestion.Kind;
import project.stockrecommendationengine.rag.freshness.FilingFreshness;
import project.stockrecommendationengine.rag.freshness.FilingFreshnessService;
import project.stockrecommendationengine.rag.freshness.FilingFreshnessService.EnsureOutcome;
import project.stockrecommendationengine.rag.ingestion.FilingIngestionProperties;
import project.stockrecommendationengine.rag.repository.SECFilingRepository;
import project.stockrecommendationengine.rag.retrieval.FilingRetrievalProperties;
import project.stockrecommendationengine.rag.retrieval.FilingRetrievalService;
import project.stockrecommendationengine.recommendation.RecommendationProperties;
import project.stockrecommendationengine.recommendation.RecommendationRepository;
import project.stockrecommendationengine.recommendation.RecommendationService;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The scripted seam of the answer-evaluation tests: the real {@link RecommendationService} over a scripted chat model
 * (the seam RecommendationServiceTests and RunPurposeTests use) and scripted retrieval, and the real
 * {@link AnswerEvaluationService} over a scripted question set. No test built on this calls a live model. The broker is
 * absent, as it is for evaluation runs, so an answered run's status is PARTIAL.
 */
final class ScriptedAnswers {
    static final String ACCESSION = "0000000001-26-000001";
    static final String SECTION = "ITEM_7";
    /** Tokens every scripted model response reports, so token totals can be asserted. */
    static final int TOKENS_PER_CALL = 150;

    final ChatModel model = mock(ChatModel.class);
    final FilingRetrievalService retrieval = mock(FilingRetrievalService.class);
    final RetrievalEvaluationSetLoader loader = mock(RetrievalEvaluationSetLoader.class);
    final SECFilingRepository filings = mock(SECFilingRepository.class);
    final RecommendationProperties recommendationProperties = new RecommendationProperties();
    final RetrievalEvaluationProperties evaluationProperties = new RetrievalEvaluationProperties();
    final List<Long> pauses = new ArrayList<>();
    final List<Prompt> prompts = java.util.Collections.synchronizedList(new ArrayList<>());
    private final Map<String, List<RetrievedFilingChunk>> chunksByQuery = new LinkedHashMap<>();
    private final Deque<Object> manager = new ArrayDeque<>();
    private final Deque<Object> rag = new ArrayDeque<>();
    private final Deque<Object> critic = new ArrayDeque<>();
    private final List<RetrievalEvaluationQuestion> questions = new ArrayList<>();

    ScriptedAnswers() {
        recommendationProperties.setModel("scripted-test-model");
        recommendationProperties.setParallelSpecialists(false);
        recommendationProperties.setCriticRounds(0);
        // The shortest cut the property allows, so a test passage can put its phrase past it.
        recommendationProperties.setModelPassageChars(200);
        recommendationProperties.setRateLimitRetryMs(1);
        when(filings.findDistinctProcessingVersionsOfEmbeddedFilings()).thenReturn(List.of("scripted-store-v1"));
        when(retrieval.retrieve(any())).thenAnswer(invocation -> {
            RetrievalRequest request = invocation.getArgument(0);
            List<RetrievedFilingChunk> chunks = chunksByQuery.getOrDefault(request.query(), List.of());
            return new RetrievalResponse(request.ticker(), request.query(), "HYBRID_RRF", true, request.topK(), chunks.size(), chunks);
        });
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            prompts.add(prompt);
            String system = prompt.getInstructions().get(0).getText();
            Deque<Object> script = system.contains("You are the manager") ? manager : system.contains("You are the critic") ? critic : rag;
            Object next;
            synchronized (this) { next = script.pollFirst(); }
            if (next == null) throw new IllegalStateException("No scripted response");
            if (next instanceof RuntimeException failure) throw failure;
            return next;
        });
    }

    /** A question whose prefetch search returns the chunks, and the manager's scripted final answer for it. */
    ScriptedAnswers question(String id, Kind kind, String text, List<String> phrases, List<RetrievedFilingChunk> chunks, String finalAnswer) {
        questions.add(new RetrievalEvaluationQuestion(id, "TSTA", kind, text,
                phrases.stream().map(phrase -> new ExpectedPassage(ACCESSION, SECTION, phrase)).toList(), null));
        chunksByQuery.put(text, chunks);
        if (finalAnswer != null) {
            // The scripted tool-call id carries a counter, not the question id, so a test can assert the id never reaches a prompt.
            manager.add(calls(new AssistantMessage.ToolCall("delegate-" + questions.size(), "function", "researchFilings", "{}")));
            rag.add(text("{\"summary\":\"Findings from the retrieved passages.\"}"));
            manager.add(text(finalAnswer));
        }
        return this;
    }

    /** A question with an arbitrary ticker and no script: for runs the service refuses or that never reach the model. */
    ScriptedAnswers unscripted(String id, String ticker, String text) {
        questions.add(new RetrievalEvaluationQuestion(id, ticker, Kind.NARRATIVE, text, List.of(new ExpectedPassage(ACCESSION, SECTION, "a phrase never retrieved")), null));
        return this;
    }

    ScriptedAnswers managerSays(Object response) { manager.add(response); return this; }
    ScriptedAnswers ragSays(Object response) { rag.add(response); return this; }
    ScriptedAnswers criticSays(Object response) { critic.add(response); return this; }

    RecommendationService recommendationService(RecommendationRepository store) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("model", model);
        var freshness = mock(FilingFreshnessService.class);
        var fresh = new FilingFreshness("TSTA", Map.of("10-K", LocalDate.of(2026, 2, 1)), LocalDate.of(2026, 2, 1), false, Instant.now(), false);
        when(freshness.ensure(any())).thenReturn(new EnsureOutcome("FRESH", null, fresh));
        when(freshness.assess(any())).thenReturn(fresh);
        return new RecommendationService(beans.getBeanProvider(ChatModel.class), retrieval, freshness,
                beans.getBeanProvider(BrokerReadService.class), beans.getBeanProvider(QuantAnalysisService.class),
                beans.getBeanProvider(QuantProperties.class), store, beans.getBeanProvider(TrackRecordService.class),
                beans.getBeanProvider(ConfidenceCalibrationService.class), recommendationProperties,
                Validation.buildDefaultValidatorFactory().getValidator(), new FilingIngestionProperties());
    }

    /** The runner over the given recommendation service (null: recommendations disabled), recording pauses instead of sleeping. */
    AnswerEvaluationService runner(RecommendationService service, AnswerEvaluationRepository snapshots) {
        when(loader.load()).thenReturn(new RetrievalEvaluationSet("scripted-v1", LocalDate.of(2026, 9, 19), List.copyOf(questions)));
        var beans = new StaticListableBeanFactory();
        if (service != null) beans.addBean("recommendations", service);
        return new AnswerEvaluationService(loader, beans.getBeanProvider(RecommendationService.class), snapshots, evaluationProperties,
                recommendationProperties, new FilingRetrievalProperties(), new FilingIngestionProperties(), filings,
                new MockEnvironment(), pauses::add);
    }

    static RetrievedFilingChunk chunk(long id, String section, String content) {
        return new RetrievedFilingChunk(id, 1L, "TSTA", "0000000001", ACCESSION, "10-K", LocalDate.of(2026, 2, 1), LocalDate.of(2025, 12, 31),
                section, "Section", (int) id, content, "https://www.sec.gov/example", 0.8);
    }

    static String answer(String assessment, String reasoning, String cited) {
        return "{\"assessment\":\"" + assessment + "\",\"reasoning\":\"" + reasoning + "\",\"citedChunkIds\":" + cited + "}";
    }

    static ChatResponse calls(AssistantMessage.ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())), usage());
    }

    static ChatResponse text(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), usage());
    }

    private static ChatResponseMetadata usage() {
        return ChatResponseMetadata.builder().usage(new DefaultUsage(TOKENS_PER_CALL - 50, 50)).build();
    }
}
