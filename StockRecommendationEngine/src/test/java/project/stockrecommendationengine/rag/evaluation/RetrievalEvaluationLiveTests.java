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
 * snapshot the service saves is rolled back; the metrics and miss ids are printed so a failure is diagnosable from the
 * build output alone. Run with -Drag.evaluation.live=true; raise the floors with -Drag.evaluation.min-hit-at-5=<fraction> and
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
                + " mrr=" + evaluation.mrr() + " floor=" + floor + " nonFigureFloor=" + nonFigureFloor);
        if (evaluation.slices() != null) {
            evaluation.slices().forEach((name, s) -> System.out.println("RETRIEVAL_EVAL slice=" + name + " questions=" + s.questionCount()
                    + " hitAt1=" + s.hitAt1() + " hitAt3=" + s.hitAt3() + " hitAt5=" + s.hitAt5() + " mrr=" + s.mrr() + " misses=" + s.missIds()));
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
