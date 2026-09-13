package project.stockrecommendationengine.rag.evaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import project.stockrecommendationengine.rag.dto.RetrievalRequest;
import project.stockrecommendationengine.rag.dto.RetrievalResponse;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.QuestionResult;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.RankedQuestion;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.SliceMetrics;
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
        // No question text here carries a figure: the figure slice is empty (null metrics) and the non-figure slice is the aggregate.
        assertThat(evaluation.slices()).containsOnlyKeys("figure", "nonFigure");
        assertThat(evaluation.slices().get("figure")).isEqualTo(new SliceMetrics(0, null, null, null, null, List.of(), List.of()));
        var nonFigure = evaluation.slices().get("nonFigure");
        assertThat(nonFigure.questionCount()).isEqualTo(4);
        assertThat(nonFigure.hitAt1()).isEqualTo(evaluation.hitAt1());
        assertThat(nonFigure.hitAt3()).isEqualTo(evaluation.hitAt3());
        assertThat(nonFigure.hitAt5()).isEqualTo(evaluation.hitAt5());
        assertThat(nonFigure.mrr()).isEqualTo(evaluation.mrr());
        assertThat(nonFigure.missIds()).containsExactly("q3");
        assertThat(nonFigure.notInTop5()).containsExactly(new RankedQuestion("q3", null));
        assertThat(evaluation.properties()).containsEntry("set", "evaluation/retrieval-set-v2.json").containsEntry("setCreatedOn", "2026-09-12")
                .containsEntry("window", 10).containsEntry("latestFilingsOnly", true)
                .containsEntry("hybridEnabled", true).containsEntry("keywordCandidateCount", 40).containsEntry("rrfK", 60)
                .containsEntry("rrfVectorWeight", 1.0).containsEntry("rrfKeywordWeight", 0.5).containsEntry("rrfFigureWeight", 1.0)
                .containsKey("hybrid").containsEntry("hybrid", null)
                .containsKey("rerank").containsEntry("rerank", null).containsEntry("rerankingEnabled", false)
                .containsEntry("rerankCandidates", 20).containsKey("reranker").containsEntry("reranker", null)
                .containsEntry("rerankedQuestions", 0).containsEntry("rerankFallbackQuestions", 0);
        assertThat(evaluation.results()).extracting(QuestionResult::retrievalStrategy).containsExactly("FILTERED_VECTOR", "FILTERED_VECTOR", "FILTERED_VECTOR", "FILTERED_VECTOR");

        ArgumentCaptor<RetrievalRequest> requests = ArgumentCaptor.forClass(RetrievalRequest.class);
        verify(retrieval, times(4)).retrieve(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.topK()).isEqualTo(10);
            assertThat(request.latestFilingsOnly()).isTrue();
            assertThat(request.filingTypes()).isNull();
            assertThat(request.sectionKeys()).isNull();
            assertThat(request.hybrid()).isNull();
            assertThat(request.rerank()).isNull();
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
        assertThat(evaluation.slices().get("nonFigure").hitAt5()).isEqualByComparingTo("0.5");
        assertThat(evaluation.slices().get("nonFigure").missIds()).containsExactly("q1");
    }

    @Test void slicesSplitQuestionsByFigureTermsWithTheAggregateMetricsUnchanged() {
        var n1 = textQuestion("n1", "What was revenue in fiscal 2025?");
        var n2 = textQuestion("n2", "What are the main risk factors?");
        var n3 = textQuestion("n3", "How did operating income change?");
        var f1 = textQuestion("f1", "Where is revenue of $64,377 million reported?");
        var f2 = textQuestion("f2", "Which segment grew 40.4 percent?");
        assertThat(RetrievalEvaluationService.isFigureQuestion(n1.question())).isFalse();
        assertThat(RetrievalEvaluationService.isFigureQuestion(f1.question())).isTrue();
        assertThat(List.of(n2, n3)).noneMatch(q -> RetrievalEvaluationService.isFigureQuestion(q.question()));
        assertThat(RetrievalEvaluationService.isFigureQuestion(f2.question())).isTrue();
        // Interleaved so the split follows the text, not the position.
        when(loader.load()).thenReturn(new RetrievalEvaluationSet("v2", LocalDate.of(2026, 9, 13), List.of(n1, f1, n2, f2, n3)));
        var ranks = Map.of("n1", 1, "f1", 2, "n3", 4, "f2", 7);
        when(retrieval.retrieve(any())).thenAnswer(invocation -> {
            RetrievalRequest request = invocation.getArgument(0);
            String id = List.of(n1, f1, n2, f2, n3).stream().filter(q -> q.question().equals(request.query())).findFirst().orElseThrow().id();
            var chunks = new java.util.ArrayList<RetrievedFilingChunk>();
            Integer rank = ranks.get(id);
            int count = rank == null ? 10 : rank;
            for (int i = 1; i <= count; i++) chunks.add(chunk(i, ACC, "ITEM_7", rank != null && i == rank ? "the passage for " + id : "filler " + i));
            return response(request, chunks.toArray(RetrievedFilingChunk[]::new));
        });
        when(repository.save(any())).thenAnswer(invocation -> ((RetrievalEvaluation) invocation.getArgument(0)).withId(3L));

        var evaluation = service.evaluate();

        assertThat(evaluation.window()).isEqualTo(10);
        assertThat(evaluation.results()).extracting(QuestionResult::rank).containsExactly(1, 2, null, 7, 4);
        // Aggregate, computed as before over all five: hit@1 1/5, hit@3 2/5, hit@5 3/5, MRR (1 + 1/2 + 0 + 1/7 + 1/4)/5.
        assertThat(evaluation.questionCount()).isEqualTo(5);
        assertThat(evaluation.hitAt1()).isEqualTo(new BigDecimal("0.200000"));
        assertThat(evaluation.hitAt3()).isEqualTo(new BigDecimal("0.400000"));
        assertThat(evaluation.hitAt5()).isEqualTo(new BigDecimal("0.600000"));
        assertThat(evaluation.mrr()).isEqualTo(new BigDecimal("0.378571"));

        assertThat(evaluation.slices()).containsOnlyKeys(RetrievalEvaluation.FIGURE_SLICE, RetrievalEvaluation.NON_FIGURE_SLICE);
        assertThat(evaluation.slices().get("nonFigure")).isEqualTo(new SliceMetrics(3, new BigDecimal("0.333333"), new BigDecimal("0.333333"),
                new BigDecimal("0.666667"), new BigDecimal("0.416667"), List.of("n2"), List.of(new RankedQuestion("n2", null))));
        assertThat(evaluation.slices().get("figure")).isEqualTo(new SliceMetrics(2, new BigDecimal("0.000000"), new BigDecimal("0.500000"),
                new BigDecimal("0.500000"), new BigDecimal("0.321429"), List.of(), List.of(new RankedQuestion("f2", 7))));
        // Not in top 5: the non-figure slice holds only the no-match question (n1 rank 1 and n3 rank 4 count); the figure slice
        // holds the rank-7 question, which is in the window but not in the top 5, so it is not a miss.
        assertThat(evaluation.slices().get("nonFigure").notInTop5()).containsExactly(new RankedQuestion("n2", null));
        assertThat(evaluation.slices().get("figure").notInTop5()).containsExactly(new RankedQuestion("f2", 7));
        assertThat(evaluation.slices().get("figure").missIds()).isEmpty();
        verify(repository).save(argThat(saved -> saved.slices() != null && saved.slices().get("figure").questionCount() == 2));
    }

    @Test void aRetrievalErrorCountsAsAMissInItsOwnSlice() {
        var figure = textQuestion("f1", "Where is revenue of $64,377 million reported?");
        var nonFigure = textQuestion("n1", "What are the main risk factors?");
        when(loader.load()).thenReturn(new RetrievalEvaluationSet("v2", LocalDate.of(2026, 9, 13), List.of(figure, nonFigure)));
        when(retrieval.retrieve(argThat(r -> r != null && r.query().equals(figure.question())))).thenThrow(new IllegalStateException("embedding unavailable"));
        when(retrieval.retrieve(argThat(r -> r != null && r.query().equals(nonFigure.question()))))
                .thenAnswer(inv -> response(inv.getArgument(0), chunk(1, ACC, "ITEM_7", "the passage for n1")));
        when(repository.save(any())).thenAnswer(invocation -> ((RetrievalEvaluation) invocation.getArgument(0)).withId(4L));

        var evaluation = service.evaluate();

        assertThat(evaluation.slices().get("figure")).isEqualTo(new SliceMetrics(1, new BigDecimal("0.000000"), new BigDecimal("0.000000"),
                new BigDecimal("0.000000"), new BigDecimal("0.000000"), List.of("f1"), List.of(new RankedQuestion("f1", null))));
        assertThat(evaluation.slices().get("nonFigure")).isEqualTo(new SliceMetrics(1, new BigDecimal("1.000000"), new BigDecimal("1.000000"),
                new BigDecimal("1.000000"), new BigDecimal("1.000000"), List.of(), List.of()));
    }

    @Test void theNonFigureFloorAssertionPassesAtTheFloorAndFailsBelowItNamingEveryQuestionNotInTheTop5WithItsRank() {
        var evaluation = scripted(new SliceMetrics(30, new BigDecimal("0.433333"), new BigDecimal("0.633333"), new BigDecimal("0.700000"),
                new BigDecimal("0.560595"), List.of("msft-08", "nvda-02"),
                List.of(new RankedQuestion("aapl-09", 8), new RankedQuestion("msft-01", 6), new RankedQuestion("msft-08", null),
                        new RankedQuestion("nvda-02", null), new RankedQuestion("nvda-05", 10), new RankedQuestion("x-1", 7),
                        new RankedQuestion("x-2", 9), new RankedQuestion("x-3", null), new RankedQuestion("x-4", 6))));
        assertThatCode(() -> RetrievalEvaluationFloors.assertAggregateFloor(evaluation, new BigDecimal("0.65"))).doesNotThrowAnyException();
        assertThatCode(() -> RetrievalEvaluationFloors.assertNonFigureFloor(evaluation, new BigDecimal("0.60"), "evaluation/retrieval-set-v2.json"))
                .doesNotThrowAnyException();
        assertThatCode(() -> RetrievalEvaluationFloors.assertNonFigureFloor(evaluation, new BigDecimal("0.700000"), "evaluation/retrieval-set-v2.json"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RetrievalEvaluationFloors.assertNonFigureFloor(evaluation, new BigDecimal("0.95"), "evaluation/retrieval-set-v2.json"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("non-figure hit@5 0.700000 (21 of 30)").hasMessageContaining("floor 0.95")
                .hasMessageContaining("rag.evaluation.min-non-figure-hit-at-5")
                .hasMessageContaining("not in top 5: aapl-09 (rank 8), msft-01 (rank 6), msft-08 (no match in window), nvda-02 (no match in window), "
                        + "nvda-05 (rank 10), x-1 (rank 7), x-2 (rank 9), x-3 (no match in window), x-4 (rank 6)");
        assertThat(RetrievalEvaluationFloors.minimumHits(new BigDecimal("0.60"), 30)).isEqualTo(18);
        assertThat(RetrievalEvaluationFloors.minimumHits(new BigDecimal("0.65"), 42)).as("27.3 rounds up").isEqualTo(28);
        assertThat(RetrievalEvaluationFloors.minimumHits(new BigDecimal("0.95"), 30)).as("28.5 rounds up").isEqualTo(29);
    }

    @Test void theAggregateFloorFailureNamesEveryQuestionNotInTheTop5WithItsRankAndTheHitCount() {
        var results = List.of(new QuestionResult("aapl-01", "AAPL", Kind.FIGURE, 1, 1L, null, null),
                new QuestionResult("aapl-09", "AAPL", Kind.NARRATIVE, 8, 2L, null, null),
                new QuestionResult("msft-03", "MSFT", Kind.NARRATIVE, 5, 3L, null, null),
                new QuestionResult("msft-08", "MSFT", Kind.NARRATIVE, null, null, null, null),
                new QuestionResult("nvda-05", "NVDA", Kind.NARRATIVE, 6, 4L, null, null));
        var evaluation = new RetrievalEvaluation(1L, Instant.now(), "v2", 5, new BigDecimal("0.200000"), new BigDecimal("0.200000"),
                new BigDecimal("0.400000"), new BigDecimal("0.354167"), 10, "HYBRID_RRF", Map.of(), results, Map.of(), List.of(), null);
        assertThatCode(() -> RetrievalEvaluationFloors.assertAggregateFloor(evaluation, new BigDecimal("0.40"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> RetrievalEvaluationFloors.assertAggregateFloor(evaluation, new BigDecimal("0.65")))
                .isInstanceOf(AssertionError.class).hasMessageContaining("hit@5 0.400000 (2 of 5)").hasMessageContaining("floor 0.65")
                .hasMessageContaining("rag.evaluation.min-hit-at-5")
                .hasMessageContaining("not in top 5: aapl-09 (rank 8), msft-08 (no match in window), nvda-05 (rank 6)")
                .hasMessageNotContaining("msft-03").hasMessageNotContaining("aapl-01");
        assertThat(RetrievalEvaluationFloors.describe(List.of())).isEqualTo("none");
        assertThat(RetrievalEvaluationFloors.describe(null)).isEqualTo("not recorded");
    }

    @Test void anEmptyOrAbsentNonFigureSliceFailsNamingTheSetInsteadOfPassingVacuously() {
        var empty = scripted(new SliceMetrics(0, null, null, null, null, List.of(), List.of()));
        assertThatThrownBy(() -> RetrievalEvaluationFloors.assertNonFigureFloor(empty, new BigDecimal("0.0"), "evaluation/only-figures.json"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("evaluation/only-figures.json").hasMessageContaining("no non-figure questions");
        var withoutSlices = new RetrievalEvaluation(1L, Instant.now(), "v1", 1, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 10,
                "HYBRID_RRF", Map.of(), List.of(), Map.of(), List.of(), null);
        assertThatThrownBy(() -> RetrievalEvaluationFloors.assertNonFigureFloor(withoutSlices, new BigDecimal("0.0"), "evaluation/retrieval-set-v1.json"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("evaluation/retrieval-set-v1.json").hasMessageContaining("no non-figure slice");
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
        properties.setSet("evaluation/retrieval-set-v1.json");
        var byProperty = service.evaluate(null);
        assertThat(byProperty.properties()).containsEntry("set", "evaluation/retrieval-set-v1.json");
        assertThat(byProperty.properties()).containsKey("hybrid").containsEntry("hybrid", null);
        verify(retrieval, times(2)).retrieve(requests.capture());
        assertThat(requests.getAllValues().subList(4, 6)).extracting(RetrievalRequest::hybrid).containsOnlyNulls();
    }

    // Reranker milestone 1 (RAG-1), C4: the rerank override and the reranker name are passed and recorded.

    @Test void theRerankOverrideIsPassedOnEveryRequestAndRecordedWithTheRerankerName() {
        var q1 = question("q1", "AAPL", "net sales were");
        var q2 = question("q2", "NVDA", "data center revenue");
        when(loader.load()).thenReturn(new RetrievalEvaluationSet("v1", LocalDate.of(2026, 9, 12), List.of(q1, q2)));
        when(retrieval.retrieve(any())).thenAnswer(inv -> response(inv.getArgument(0), chunk(1, ACC, "ITEM_7", "Net sales were up")));
        when(retrieval.rerankerName()).thenReturn(java.util.Optional.of("ReversingFilingReranker"));
        when(repository.save(any())).thenAnswer(invocation -> ((RetrievalEvaluation) invocation.getArgument(0)).withId(3L));

        var reranked = service.evaluate(null, true);
        assertThat(reranked.properties()).containsEntry("rerank", true).containsEntry("reranker", "ReversingFilingReranker")
                .containsEntry("rerankCandidates", 20).containsEntry("hybrid", null);
        ArgumentCaptor<RetrievalRequest> requests = ArgumentCaptor.forClass(RetrievalRequest.class);
        verify(retrieval, times(2)).retrieve(requests.capture());
        assertThat(requests.getAllValues()).extracting(RetrievalRequest::rerank).containsExactly(true, true);

        clearInvocations(retrieval);
        var fused = service.evaluate(true, false);
        assertThat(fused.properties()).containsEntry("rerank", false).containsEntry("hybrid", true).containsEntry("reranker", "ReversingFilingReranker");
        verify(retrieval, times(2)).retrieve(requests.capture());
        assertThat(requests.getAllValues().subList(2, 4)).extracting(RetrievalRequest::rerank).containsExactly(false, false);
    }

    // Reranker milestone 2 (Amendment 1): per-question strategy, reranked and fallback counts, and the reranker version.

    @Test void eachQuestionRecordsItsStrategyAndTheSnapshotCountsRerankedAndFallbackQuestions() {
        var q1 = question("q1", "AAPL", "net sales were");
        var q2 = question("q2", "NVDA", "data center revenue");
        var q3 = question("q3", "MSFT", "cloud revenue");
        var q4 = question("q4", "MSFT", "operating income");
        when(loader.load()).thenReturn(new RetrievalEvaluationSet("v2", LocalDate.of(2026, 9, 13), List.of(q1, q2, q3, q4)));
        // q1 reranked, q2 fell back (timeout), q3 failed outright, q4 reranked.
        when(retrieval.retrieve(any())).thenAnswer(inv -> {
            RetrievalRequest request = inv.getArgument(0);
            return switch (request.query()) {
                case "q1?" -> withStrategy(request, "HYBRID_RRF_RERANKED", chunk(1, ACC, "ITEM_7", "Net sales were up"));
                case "q2?" -> withStrategy(request, "HYBRID_RRF", chunk(2, ACC, "ITEM_7", "Data center revenue grew"));
                case "q3?" -> throw new IllegalStateException("embedding unavailable");
                default -> withStrategy(request, "HYBRID_RRF_RERANKED", chunk(4, ACC, "ITEM_7", "no match here"));
            };
        });
        when(retrieval.rerankerName()).thenReturn(java.util.Optional.of("CrossEncoderReranker"));
        when(retrieval.rerankerVersion()).thenReturn(java.util.Optional.of("5d3e70fd0c9f"));
        when(repository.save(any())).thenAnswer(invocation -> ((RetrievalEvaluation) invocation.getArgument(0)).withId(5L));

        var reranked = service.evaluate(null, true);
        assertThat(reranked.results()).extracting(QuestionResult::retrievalStrategy)
                .containsExactly("HYBRID_RRF_RERANKED", "HYBRID_RRF", null, "HYBRID_RRF_RERANKED");
        assertThat(reranked.retrievalStrategy()).isEqualTo("HYBRID_RRF_RERANKED");
        assertThat(reranked.properties()).containsEntry("rerankedQuestions", 2).containsEntry("rerankFallbackQuestions", 1)
                .containsEntry("reranker", "CrossEncoderReranker").containsEntry("rerankerVersion", "5d3e70fd0c9f");

        // Reranking resolved off (override false): nothing is a fallback, whatever the strategies say.
        var off = service.evaluate(null, false);
        assertThat(off.properties()).containsEntry("rerankedQuestions", 2).containsEntry("rerankFallbackQuestions", 0);
    }

    @Test void withNoRerankOverrideTheFallbackCountFollowsTheRerankingProperty() {
        var q1 = question("q1", "AAPL", "net sales were");
        when(loader.load()).thenReturn(new RetrievalEvaluationSet("v2", LocalDate.of(2026, 9, 13), List.of(q1)));
        when(retrieval.retrieve(any())).thenAnswer(inv -> withStrategy(inv.getArgument(0), "HYBRID_RRF", chunk(1, ACC, "ITEM_7", "Net sales were up")));
        when(repository.save(any())).thenAnswer(invocation -> ((RetrievalEvaluation) invocation.getArgument(0)).withId(6L));

        var byPropertyOff = service.evaluate(null, null);
        assertThat(byPropertyOff.properties()).containsEntry("rerankedQuestions", 0).containsEntry("rerankFallbackQuestions", 0)
                .containsKey("rerankerVersion").containsEntry("rerankerVersion", null);

        var retrievalProperties = new FilingRetrievalProperties();
        retrievalProperties.setRerankingEnabled(true);
        var onByProperty = new RetrievalEvaluationService(loader, retrieval, repository, properties, retrievalProperties).evaluate(null, null);
        assertThat(onByProperty.properties()).containsEntry("rerankedQuestions", 0).containsEntry("rerankFallbackQuestions", 1);
    }

    private static RetrievalResponse withStrategy(RetrievalRequest request, String strategy, RetrievedFilingChunk... chunks) {
        return new RetrievalResponse(request.ticker(), request.query(), strategy, true, request.topK(), chunks.length, List.of(chunks));
    }

    @Test void rerankTrueWithoutARerankerFailsBeforeAnyQuestionRunsOrAnySnapshotIsStored() {
        when(retrieval.rerankerName()).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.evaluate(null, true))
                .isInstanceOf(project.stockrecommendationengine.rag.retrieval.RerankerUnavailableException.class)
                .hasMessageContaining("no FilingReranker is configured");
        verifyNoInteractions(loader, repository);
        verify(retrieval, never()).retrieve(any());
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

    /** A scripted evaluation with aggregate hit@5 0.785714 and the given non-figure slice. */
    private static RetrievalEvaluation scripted(SliceMetrics nonFigure) {
        var figure = new SliceMetrics(12, new BigDecimal("0.833333"), new BigDecimal("0.916667"), new BigDecimal("1.000000"), new BigDecimal("0.891667"), List.of(), List.of());
        return new RetrievalEvaluation(1L, Instant.now(), "v2", 42, new BigDecimal("0.547619"), new BigDecimal("0.714286"), new BigDecimal("0.785714"),
                new BigDecimal("0.655187"), 10, "HYBRID_RRF", Map.of(), List.of(), Map.of(), List.of(),
                Map.of(RetrievalEvaluation.FIGURE_SLICE, figure, RetrievalEvaluation.NON_FIGURE_SLICE, nonFigure));
    }

    /** A question with its own text whose one expected passage is "the passage for <id>". */
    private static RetrievalEvaluationQuestion textQuestion(String id, String text) {
        return new RetrievalEvaluationQuestion(id, "AAPL", Kind.NARRATIVE, text, List.of(new ExpectedPassage(ACC, "ITEM_7", "the passage for " + id)), null);
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
