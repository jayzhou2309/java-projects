package project.stockrecommendationengine.rag.evaluation;

import java.math.BigDecimal;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.Miss;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.SliceMetrics;
import static org.assertj.core.api.Assertions.*;

/**
 * The two regression-floor assertions of {@link RetrievalEvaluationLiveTests}, kept static so a unit test can run them on
 * a scripted evaluation without a live store. The live test asserts the aggregate floor first, then the non-figure floor.
 */
final class RetrievalEvaluationFloors {
    private RetrievalEvaluationFloors() { }

    /** Aggregate hit@5 at or above {@code rag.evaluation.min-hit-at-5}. */
    static void assertAggregateFloor(RetrievalEvaluation evaluation, BigDecimal floor) {
        assertThat(evaluation.hitAt5())
                .as("hit@5 %s is below the floor %s (rag.evaluation.min-hit-at-5); misses %s", evaluation.hitAt5(), floor,
                        evaluation.misses().stream().map(Miss::id).toList())
                .isGreaterThanOrEqualTo(floor);
    }

    /**
     * Non-figure slice hit@5 at or above {@code rag.evaluation.min-non-figure-hit-at-5}. A snapshot without slices, or a set
     * with no non-figure questions, fails naming the set: the floor must never be satisfied vacuously.
     */
    static void assertNonFigureFloor(RetrievalEvaluation evaluation, BigDecimal floor, String set) {
        SliceMetrics nonFigure = evaluation.slices() == null ? null : evaluation.slices().get(RetrievalEvaluation.NON_FIGURE_SLICE);
        if (nonFigure == null) {
            fail("evaluation of set %s carries no non-figure slice; the non-figure floor %s (rag.evaluation.min-non-figure-hit-at-5) cannot be checked", set, floor);
        }
        if (nonFigure.questionCount() == 0 || nonFigure.hitAt5() == null) {
            fail("set %s has no non-figure questions (every question carries a figure); the non-figure floor %s (rag.evaluation.min-non-figure-hit-at-5) cannot pass vacuously", set, floor);
        }
        assertThat(nonFigure.hitAt5())
                .as("non-figure hit@5 %s (%d questions) is below the floor %s (rag.evaluation.min-non-figure-hit-at-5); non-figure misses %s",
                        nonFigure.hitAt5(), nonFigure.questionCount(), floor, nonFigure.missIds())
                .isGreaterThanOrEqualTo(floor);
    }
}
