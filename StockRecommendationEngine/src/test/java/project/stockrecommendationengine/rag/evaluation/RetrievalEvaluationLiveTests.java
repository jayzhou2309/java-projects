package project.stockrecommendationengine.rag.evaluation;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.Miss;
import static org.assertj.core.api.Assertions.*;

/**
 * Opt-in regression floor: the bundled set through the real retrieval path against the local store (one embedding call per
 * question, no chat model), asserting hit@5 at or above rag.evaluation.min-hit-at-5. Runs inside a transaction so the
 * snapshot the service saves is rolled back; the metrics and miss ids are printed so a failure is diagnosable from the
 * build output alone. Run with -Drag.evaluation.live=true; raise the floor with -Drag.evaluation.min-hit-at-5=<fraction>.
 */
@SpringBootTest
@Transactional
@EnabledIfSystemProperty(named = "rag.evaluation.live", matches = "true")
class RetrievalEvaluationLiveTests {
    @Autowired RetrievalEvaluationService service;
    @Autowired RetrievalEvaluationProperties properties;

    @Test void hitAt5StaysAtOrAboveTheConfiguredFloor() {
        BigDecimal floor = properties.getMinHitAt5();
        RetrievalEvaluation evaluation = service.evaluate();
        List<String> missIds = evaluation.misses().stream().map(Miss::id).toList();
        System.out.println("RETRIEVAL_EVAL set=" + evaluation.setVersion() + " questions=" + evaluation.questionCount()
                + " window=" + evaluation.window() + " strategy=" + evaluation.retrievalStrategy()
                + " hitAt1=" + evaluation.hitAt1() + " hitAt3=" + evaluation.hitAt3() + " hitAt5=" + evaluation.hitAt5()
                + " mrr=" + evaluation.mrr() + " floor=" + floor);
        System.out.println("RETRIEVAL_EVAL tickerHitAt5=" + evaluation.tickerHitAt5());
        System.out.println("RETRIEVAL_EVAL misses=" + missIds);
        for (Miss miss : evaluation.misses()) {
            System.out.println("RETRIEVAL_EVAL miss " + miss.id() + " top=" + miss.top() + (miss.error() == null ? "" : " error=" + miss.error()));
        }
        assertThat(evaluation.questionCount()).isBetween(24, 30);
        assertThat(evaluation.results()).noneMatch(r -> r.error() != null);
        assertThat(evaluation.hitAt5())
                .as("hit@5 %s is below the floor %s (rag.evaluation.min-hit-at-5); misses %s", evaluation.hitAt5(), floor, missIds)
                .isGreaterThanOrEqualTo(floor);
    }
}
