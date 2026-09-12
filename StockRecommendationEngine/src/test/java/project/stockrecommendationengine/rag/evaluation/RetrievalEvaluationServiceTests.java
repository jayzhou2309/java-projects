package project.stockrecommendationengine.rag.evaluation;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import project.stockrecommendationengine.rag.dto.RetrievalRequest;
import project.stockrecommendationengine.rag.dto.RetrievalResponse;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationQuestion.Kind;
import project.stockrecommendationengine.rag.retrieval.FilingRetrievalProperties;
import project.stockrecommendationengine.rag.retrieval.FilingRetrievalService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/** Metrics and matching against a scripted retrieval service; no database, no embedding. */
class RetrievalEvaluationServiceTests {
    private static final String ACC = "0000320193-25-000079";
    private static final String OTHER = "0000320193-26-000020";
    private final RetrievalEvaluationSetLoader loader = mock(RetrievalEvaluationSetLoader.class);
    private final FilingRetrievalService retrieval = mock(FilingRetrievalService.class);
    private final RetrievalEvaluationRepository repository = mock(RetrievalEvaluationRepository.class);
    private final RetrievalEvaluationProperties properties = new RetrievalEvaluationProperties();
    private final RetrievalEvaluationService service = new RetrievalEvaluationService(loader, retrieval, repository, properties,
            new FilingRetrievalProperties());

    @Test void computesHitAtKMrrPerTickerHitAndMissesFromScriptedRanks() {
        var q1 = question("q1", "AAPL", "net sales were");
        var q2 = question("q2", "AAPL", "a risk factor");
        var q3 = question("q3", "MSFT", "never returned");
        var q4 = question("q4", "MSFT", "operating income");
        when(loader.load()).thenReturn(new RetrievalEvaluationSet("v1", LocalDate.of(2026, 9, 12), List.of(q1, q2, q3, q4)));
        when(retrieval.retrieve(any())).thenAnswer(invocation -> {
            RetrievalRequest request = invocation.getArgument(0);
            return switch (request.query()) {
                case "q1?" -> response(request, chunk(11, ACC, "ITEM_7", "Total NET   sales were higher"), chunk(12, ACC, "ITEM_7", "other"));
                case "q2?" -> response(request, chunk(21, ACC, "ITEM_7", "x"), chunk(22, ACC, "ITEM_1A", "a risk factor in the wrong section"), chunk(23, ACC, "ITEM_7", "A RISK factor"));
                case "q3?" -> response(request, chunk(31, ACC, "ITEM_7", "nothing"), chunk(32, ACC, "ITEM_8", "here"), chunk(33, OTHER, "ITEM_2", "at all"),
                        chunk(34, ACC, "ITEM_1", "fourth is not carried"));
                default -> response(request, chunk(41, ACC, "ITEM_7", "no"), chunk(42, ACC, "ITEM_7", "Operating income rose"));
            };
        });
        when(repository.save(any())).thenAnswer(invocation -> ((RetrievalEvaluation) invocation.getArgument(0)).withId(7L));

        var evaluation = service.evaluate();

        assertThat(evaluation.id()).isEqualTo(7L);
        assertThat(evaluation.setVersion()).isEqualTo("v1");
        assertThat(evaluation.questionCount()).isEqualTo(4);
        assertThat(evaluation.window()).isEqualTo(10);
        assertThat(evaluation.retrievalStrategy()).isEqualTo("FILTERED_VECTOR");
        assertThat(evaluation.hitAt1()).isEqualByComparingTo("0.25");
        assertThat(evaluation.hitAt3()).isEqualByComparingTo("0.75");
        assertThat(evaluation.hitAt5()).isEqualByComparingTo("0.75");
        assertThat(evaluation.mrr()).isEqualByComparingTo("0.458333");
        assertThat(evaluation.mrr().scale()).isEqualTo(6);
        assertThat(evaluation.results()).extracting(RetrievalEvaluation.QuestionResult::rank).containsExactly(1, 3, null, 2);
        assertThat(evaluation.results()).extracting(RetrievalEvaluation.QuestionResult::matchedChunkId).containsExactly(11L, 23L, null, 42L);
        assertThat(evaluation.results()).extracting(RetrievalEvaluation.QuestionResult::error).containsOnlyNulls();
        assertThat(evaluation.tickerHitAt5()).containsEntry("AAPL", new java.math.BigDecimal("1.000000")).containsEntry("MSFT", new java.math.BigDecimal("0.500000"));
        assertThat(evaluation.misses()).hasSize(1);
        var miss = evaluation.misses().get(0);
        assertThat(miss.id()).isEqualTo("q3");
        assertThat(miss.error()).isNull();
        assertThat(miss.top()).extracting(RetrievalEvaluation.TopChunk::chunkId).containsExactly(31L, 32L, 33L);
        assertThat(miss.top().get(2).accessionNo()).isEqualTo(OTHER);
        assertThat(miss.top().get(2).sectionKey()).isEqualTo("ITEM_2");
        assertThat(miss.top().get(0).similarity()).isEqualByComparingTo("0.9");
        assertThat(evaluation.properties()).containsEntry("window", 10).containsEntry("latestFilingsOnly", true)
                .containsEntry("hybridEnabled", true).containsEntry("keywordCandidateCount", 40).containsEntry("rrfK", 60)
                .containsEntry("rrfVectorWeight", 1.0).containsEntry("rrfKeywordWeight", 0.5).containsEntry("rrfFigureWeight", 1.0)
                .containsKey("hybrid").containsEntry("hybrid", null);

        ArgumentCaptor<RetrievalRequest> requests = ArgumentCaptor.forClass(RetrievalRequest.class);
        verify(retrieval, times(4)).retrieve(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.topK()).isEqualTo(10);
            assertThat(request.latestFilingsOnly()).isTrue();
            assertThat(request.filingTypes()).isNull();
            assertThat(request.sectionKeys()).isNull();
            assertThat(request.hybrid()).isNull();
        });
        assertThat(requests.getAllValues()).extracting(RetrievalRequest::ticker).containsExactly("AAPL", "AAPL", "MSFT", "MSFT");
        verify(repository).save(argThat(saved -> saved.id() == null));
    }

    @Test void aRetrievalFailureIsRecordedAsAMissAndTheOthersStillCount() {
        var q1 = question("q1", "AAPL", "net sales were");
        var q2 = question("q2", "NVDA", "data center revenue");
        when(loader.load()).thenReturn(new RetrievalEvaluationSet("v1", LocalDate.of(2026, 9, 12), List.of(q1, q2)));
        when(retrieval.retrieve(argThat(r -> r != null && r.query().equals("q1?")))).thenThrow(new IllegalStateException("embedding unavailable"));
        when(retrieval.retrieve(argThat(r -> r != null && r.query().equals("q2?"))))
                .thenAnswer(inv -> response(inv.getArgument(0), chunk(1, ACC, "ITEM_7", "Data Center revenue grew")));
        when(repository.save(any())).thenAnswer(invocation -> ((RetrievalEvaluation) invocation.getArgument(0)).withId(1L));

        var evaluation = service.evaluate();

        assertThat(evaluation.questionCount()).isEqualTo(2);
        assertThat(evaluation.hitAt1()).isEqualByComparingTo("0.5");
        assertThat(evaluation.mrr()).isEqualByComparingTo("0.5");
        assertThat(evaluation.results().get(0).rank()).isNull();
        assertThat(evaluation.results().get(0).error()).isEqualTo("IllegalStateException: embedding unavailable");
        assertThat(evaluation.results().get(1).rank()).isEqualTo(1);
        assertThat(evaluation.misses()).hasSize(1);
        assertThat(evaluation.misses().get(0).id()).isEqualTo("q1");
        assertThat(evaluation.misses().get(0).top()).isEmpty();
        assertThat(evaluation.misses().get(0).error()).contains("embedding unavailable");
        assertThat(evaluation.tickerHitAt5()).containsEntry("AAPL", new java.math.BigDecimal("0.000000")).containsEntry("NVDA", new java.math.BigDecimal("1.000000"));
    }

    @Test void theHybridOverrideIsPassedOnEveryRequestAndRecordedInTheSnapshot() {
        var q1 = question("q1", "AAPL", "net sales were");
        var q2 = question("q2", "NVDA", "data center revenue");
        when(loader.load()).thenReturn(new RetrievalEvaluationSet("v1", LocalDate.of(2026, 9, 12), List.of(q1, q2)));
        when(retrieval.retrieve(any())).thenAnswer(inv -> response(inv.getArgument(0), chunk(1, ACC, "ITEM_7", "Net sales were up")));
        when(repository.save(any())).thenAnswer(invocation -> ((RetrievalEvaluation) invocation.getArgument(0)).withId(2L));

        var hybrid = service.evaluate(true);
        assertThat(hybrid.properties()).containsEntry("hybrid", true);
        assertThat(hybrid.retrievalStrategy()).isEqualTo("HYBRID_RRF");
        ArgumentCaptor<RetrievalRequest> requests = ArgumentCaptor.forClass(RetrievalRequest.class);
        verify(retrieval, times(2)).retrieve(requests.capture());
        assertThat(requests.getAllValues()).extracting(RetrievalRequest::hybrid).containsExactly(true, true);
        assertThat(requests.getAllValues()).allSatisfy(request -> assertThat(request.latestFilingsOnly()).isTrue());

        clearInvocations(retrieval);
        var vectorOnly = service.evaluate(false);
        assertThat(vectorOnly.properties()).containsEntry("hybrid", false);
        assertThat(vectorOnly.retrievalStrategy()).isEqualTo("FILTERED_VECTOR");
        verify(retrieval, times(2)).retrieve(requests.capture());
        assertThat(requests.getAllValues().subList(2, 4)).extracting(RetrievalRequest::hybrid).containsExactly(false, false);

        clearInvocations(retrieval);
        var byProperty = service.evaluate(null);
        assertThat(byProperty.properties()).containsKey("hybrid").containsEntry("hybrid", null);
        verify(retrieval, times(2)).retrieve(requests.capture());
        assertThat(requests.getAllValues().subList(4, 6)).extracting(RetrievalRequest::hybrid).containsOnlyNulls();
    }

    @Test void matchingIsCaseInsensitiveWithWhitespaceCollapsedAndRequiresTheSameFilingAndSection() {
        var passage = new ExpectedPassage(ACC, "ITEM_7", "Net sales increased 6%");
        assertThat(RetrievalEvaluationService.matches(chunk(1, ACC, "ITEM_7", "Total NET  SALES\n increased 6% in 2025"), passage)).isTrue();
        assertThat(RetrievalEvaluationService.matches(chunk(1, ACC, "ITEM_7", "net sales increased 6 %"), passage)).isFalse();
        assertThat(RetrievalEvaluationService.matches(chunk(1, ACC, "ITEM_8", "Net sales increased 6%"), passage)).isFalse();
        assertThat(RetrievalEvaluationService.matches(chunk(1, OTHER, "ITEM_7", "Net sales increased 6%"), passage)).isFalse();
        assertThat(RetrievalEvaluationService.matches(chunk(1, ACC, "item_7", "Net sales increased 6%"), passage)).isFalse();
        assertThat(RetrievalEvaluationService.matches(chunk(1, ACC, "ITEM_7", null), passage)).isFalse();
    }

    @Test void aMatchBeyondTheWindowDoesNotCount() {
        var question = question("q", "AAPL", "late match");
        var chunks = new java.util.ArrayList<RetrievedFilingChunk>();
        for (int i = 1; i <= 10; i++) chunks.add(chunk(i, ACC, "ITEM_7", "filler " + i));
        chunks.add(chunk(11, ACC, "ITEM_7", "the late match"));
        assertThat(RetrievalEvaluationService.firstMatch(question, chunks, 10)).isNull();
        assertThat(RetrievalEvaluationService.firstMatch(question, chunks, 11).chunkId()).isEqualTo(11L);
    }

    @Test void anyOneExpectedPassageSatisfiesAQuestion() {
        var question = new RetrievalEvaluationQuestion("q", "AAPL", Kind.FIGURE, "q?", List.of(new ExpectedPassage(ACC, "ITEM_7", "first passage text"),
                new ExpectedPassage(OTHER, "ITEM_2", "second passage text")), null);
        var matched = RetrievalEvaluationService.firstMatch(question, List.of(chunk(1, ACC, "ITEM_7", "unrelated"),
                chunk(2, OTHER, "ITEM_2", "the SECOND passage text here")), 10);
        assertThat(matched.chunkId()).isEqualTo(2L);
    }

    private static RetrievalEvaluationQuestion question(String id, String ticker, String phrase) {
        return new RetrievalEvaluationQuestion(id, ticker, Kind.NARRATIVE, id + "?", List.of(new ExpectedPassage(ACC, "ITEM_7", phrase)), null);
    }

    /** Scripted retrieval: the strategy mirrors the request's hybrid flag the way the real service resolves it with the property off. */
    private static RetrievalResponse response(RetrievalRequest request, RetrievedFilingChunk... chunks) {
        String strategy = Boolean.TRUE.equals(request.hybrid()) ? "HYBRID_RRF" : "FILTERED_VECTOR";
        return new RetrievalResponse(request.ticker(), request.query(), strategy, true, request.topK(), chunks.length, List.of(chunks));
    }

    private static RetrievedFilingChunk chunk(long id, String accessionNo, String sectionKey, String content) {
        return new RetrievedFilingChunk(id, 1L, "AAPL", "0000320193", accessionNo, "10-K", LocalDate.of(2025, 10, 31), LocalDate.of(2025, 9, 27),
                sectionKey, "Section", (int) id, content, "https://example.test/" + id, 0.9);
    }
}
