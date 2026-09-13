package project.stockrecommendationengine.rag.evaluation;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.Miss;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.SliceMetrics;
import static org.assertj.core.api.Assertions.*;

/**
 * Opt-in regression floor: the bundled set through the real retrieval path against the local store (one embedding call per
 * question, no chat model), asserting aggregate hit@5 at or above rag.evaluation.min-hit-at-5 and then the non-figure slice's
 * hit@5 at or above rag.evaluation.min-non-figure-hit-at-5 (an empty non-figure slice fails). Runs inside a transaction so the
 * snapshot the service saves is rolled back; the metrics, miss ids, every question not in the top 5 with its rank, and the
 * margin in hits above each floor are printed so a failure is diagnosable from the build output alone. Run with
 * -Drag.evaluation.live=true; raise the floors with -Drag.evaluation.min-hit-at-5=<fraction> and
 * -Drag.evaluation.min-non-figure-hit-at-5=<fraction>.
 */
@SpringBootTest
@Transactional
@EnabledIfSystemProperty(named = "rag.evaluation.live", matches = "true")
class RetrievalEvaluationLiveTests {
    @Autowired RetrievalEvaluationService service;
    @Autowired RetrievalEvaluationProperties properties;
    @Autowired RetrievalEvaluationSetLoader loader;

    @Test void hitAt5StaysAtOrAboveTheConfiguredFloor() {
        BigDecimal floor = properties.getMinHitAt5();
        BigDecimal nonFigureFloor = properties.getMinNonFigureHitAt5();
        RetrievalEvaluation evaluation = service.evaluate();
        List<String> missIds = evaluation.misses().stream().map(Miss::id).toList();
        System.out.println("RETRIEVAL_EVAL set=" + evaluation.setVersion() + " questions=" + evaluation.questionCount()
                + " window=" + evaluation.window() + " strategy=" + evaluation.retrievalStrategy()
                + " hitAt1=" + evaluation.hitAt1() + " hitAt3=" + evaluation.hitAt3() + " hitAt5=" + evaluation.hitAt5()
                + " mrr=" + evaluation.mrr() + " floor=" + floor + " nonFigureFloor=" + nonFigureFloor
                + " reranker=" + evaluation.properties().get("reranker") + " rerankerVersion=" + evaluation.properties().get("rerankerVersion")
                + " rerankedQuestions=" + evaluation.properties().get("rerankedQuestions")
                + " rerankFallbackQuestions=" + evaluation.properties().get("rerankFallbackQuestions"));
        // Any question whose retrieval fell back from reranking (or ran with a different strategy) is named here.
        System.out.println("RETRIEVAL_EVAL strategies=" + evaluation.results().stream()
                .collect(java.util.stream.Collectors.groupingBy(r -> String.valueOf(r.retrievalStrategy()), java.util.TreeMap::new,
                        java.util.stream.Collectors.mapping(RetrievalEvaluation.QuestionResult::id, java.util.stream.Collectors.toList()))));
        if (evaluation.slices() != null) {
            evaluation.slices().forEach((name, s) -> System.out.println("RETRIEVAL_EVAL slice=" + name + " questions=" + s.questionCount()
                    + " hitAt1=" + s.hitAt1() + " hitAt3=" + s.hitAt3() + " hitAt5=" + s.hitAt5() + " mrr=" + s.mrr() + " misses=" + s.missIds()));
        }
        // Every question not counting toward hit@5 with its rank, and the margin in hits above each floor, so a passing run
        // also shows how close it is: margin = hits - ceiling(floor x question count).
        var aggregateNotInTop5 = RetrievalEvaluationService.notInTop(evaluation.results(), 5);
        int aggregateHits = evaluation.results().size() - aggregateNotInTop5.size();
        System.out.println("RETRIEVAL_EVAL notInTop5 aggregate hits=" + aggregateHits + "/" + evaluation.questionCount()
                + " minHits=" + RetrievalEvaluationFloors.minimumHits(floor, evaluation.questionCount())
                + " margin=" + (aggregateHits - RetrievalEvaluationFloors.minimumHits(floor, evaluation.questionCount()))
                + " questions=" + RetrievalEvaluationFloors.describe(aggregateNotInTop5));
        if (evaluation.slices() != null) {
            evaluation.slices().forEach((name, s) -> {
                String margin = "";
                if (RetrievalEvaluation.NON_FIGURE_SLICE.equals(name) && s.notInTop5() != null) {
                    int hits = s.questionCount() - s.notInTop5().size();
                    int minHits = RetrievalEvaluationFloors.minimumHits(nonFigureFloor, s.questionCount());
                    margin = " minHits=" + minHits + " margin=" + (hits - minHits);
                }
                System.out.println("RETRIEVAL_EVAL notInTop5 slice=" + name + " hits=" + RetrievalEvaluationFloors.hitsAt5(s) + "/" + s.questionCount()
                        + margin + " questions=" + RetrievalEvaluationFloors.describe(s.notInTop5()));
            });
        }
        System.out.println("RETRIEVAL_EVAL tickerHitAt5=" + evaluation.tickerHitAt5());
        System.out.println("RETRIEVAL_EVAL misses=" + missIds);
        for (Miss miss : evaluation.misses()) {
            System.out.println("RETRIEVAL_EVAL miss " + miss.id() + " top=" + miss.top() + (miss.error() == null ? "" : " error=" + miss.error()));
        }
        assertThat(evaluation.questionCount()).isEqualTo(loader.load().questions().size());
        assertThat(evaluation.properties()).containsEntry("set", properties.getSet());
        assertThat(evaluation.results()).noneMatch(r -> r.error() != null);
        assertThat(evaluation.slices()).containsOnlyKeys(RetrievalEvaluation.FIGURE_SLICE, RetrievalEvaluation.NON_FIGURE_SLICE);
        assertThat(evaluation.slices().values().stream().mapToInt(SliceMetrics::questionCount).sum()).isEqualTo(evaluation.questionCount());
        // Aggregate first: in a run that forces only the non-figure floor up, this passes and the next assertion is what fails.
        RetrievalEvaluationFloors.assertAggregateFloor(evaluation, floor);
        RetrievalEvaluationFloors.assertNonFigureFloor(evaluation, nonFigureFloor, properties.getSet());
    }
}
