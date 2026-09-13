package project.stockrecommendationengine.rag.evaluation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.RankedQuestion;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.SliceMetrics;
import static org.assertj.core.api.Assertions.*;

/**
 * The two regression-floor assertions of {@link RetrievalEvaluationLiveTests}, kept static so a unit test can run them on
 * a scripted evaluation without a live store. The live test asserts the aggregate floor first, then the non-figure floor.
 * Each failure message carries the hit count out of the question count and names every question that does not count toward
 * hit@5 with its rank (6 to 10, or no match in the window), so a breach caused by questions sliding just below rank 5 is
 * visible, not only the long-standing misses.
 */
final class RetrievalEvaluationFloors {
    private RetrievalEvaluationFloors() { }

    /** Aggregate hit@5 at or above {@code rag.evaluation.min-hit-at-5}. */
    static void assertAggregateFloor(RetrievalEvaluation evaluation, BigDecimal floor) {
        List<RankedQuestion> notInTop5 = RetrievalEvaluationService.notInTop(evaluation.results(), 5);
        int hits = evaluation.results().size() - notInTop5.size();
        assertThat(evaluation.hitAt5())
                .as("hit@5 %s (%d of %d) is below the floor %s (rag.evaluation.min-hit-at-5); not in top 5: %s", evaluation.hitAt5(), hits,
                        evaluation.questionCount(), floor, describe(notInTop5))
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
                .as("non-figure hit@5 %s (%s of %d) is below the floor %s (rag.evaluation.min-non-figure-hit-at-5); not in top 5: %s",
                        nonFigure.hitAt5(), hitsAt5(nonFigure), nonFigure.questionCount(), floor, describe(nonFigure.notInTop5()))
                .isGreaterThanOrEqualTo(floor);
    }

    /** A slice's hit@5 count (question count minus the questions not in the top 5), or "unknown" when the list was not recorded. */
    static String hitsAt5(SliceMetrics slice) {
        return slice.notInTop5() == null ? "unknown" : String.valueOf(slice.questionCount() - slice.notInTop5().size());
    }

    /** The fewest hits out of {@code questionCount} that reach {@code floor}: ceiling of floor times the count. */
    static int minimumHits(BigDecimal floor, int questionCount) {
        return floor.multiply(BigDecimal.valueOf(questionCount)).setScale(0, RoundingMode.CEILING).intValueExact();
    }

    /** "aapl-09 (rank 8), msft-08 (no match in window)"; "none" when empty, "not recorded" when the snapshot predates the field. */
    static String describe(List<RankedQuestion> notInTop5) {
        if (notInTop5 == null) return "not recorded";
        if (notInTop5.isEmpty()) return "none";
        return notInTop5.stream().map(q -> q.id() + (q.rank() == null ? " (no match in window)" : " (rank " + q.rank() + ")"))
                .collect(Collectors.joining(", "));
    }
}
