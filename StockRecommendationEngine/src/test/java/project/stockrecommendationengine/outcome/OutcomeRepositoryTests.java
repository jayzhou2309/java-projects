package project.stockrecommendationengine.outcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.stockrecommendationengine.recommendation.RecommendationRecord;
import project.stockrecommendationengine.recommendation.RecommendationRepository;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class OutcomeRepositoryTests {
    @Autowired OutcomeRepository outcomes;
    @Autowired RecommendationRepository recommendations;
    private final String ticker = "OC" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();

    @Test void upsertsReadsBackAggregatesAndListsPendingRuns() {
        String bullish = save("BULLISH", 265598L, new BigDecimal("110"), new BigDecimal("95"));
        String neutral = save("NEUTRAL", 265598L, null, null);
        String noConid = save("NEUTRAL", null, null, null);
        assertThat(recommendations.findPendingEvaluation(3, 100)).extracting(RecommendationRecord::runId).contains(bullish, neutral).doesNotContain(noConid);

        outcomes.upsert(outcome(bullish, 5, "0.050000", true, "TAKE_PROFIT"));
        outcomes.upsert(outcome(bullish, 20, "-0.020000", false, "STOP_LOSS"));
        outcomes.upsert(outcome(neutral, 5, "0.010000", null, "NO_LEVELS"));
        outcomes.upsert(outcome(bullish, 5, "0.060000", true, "TAKE_PROFIT")); // rewrite of the same horizon
        assertThat(outcomes.evaluatedHorizons(bullish)).containsExactly(5, 20);
        assertThat(outcomes.findByRunId(bullish)).hasSize(2).first().satisfies(o -> {
            assertThat(o.returnPct()).isEqualByComparingTo("0.060000");
            assertThat(o.firstTouch()).isEqualTo("TAKE_PROFIT");
            assertThat(o.benchmarkConid()).isEqualTo(756733L);
        });
        var pending = recommendations.findPendingEvaluation(2, 100);
        assertThat(pending).extracting(RecommendationRecord::runId).contains(neutral).doesNotContain(bullish);

        var rows = outcomes.summary().stream().filter(s -> s.horizonDays() == 5 || s.horizonDays() == 20).toList();
        var bullish5 = rows.stream().filter(s -> s.assessment().equals("BULLISH") && s.horizonDays() == 5).findFirst().orElseThrow();
        assertThat(bullish5.outcomes()).isGreaterThanOrEqualTo(1);
        assertThat(bullish5.directionHitRate()).isNotNull();
        assertThat(bullish5.takeProfitFirstRate()).isNotNull();
        var neutral5 = rows.stream().filter(s -> s.assessment().equals("NEUTRAL") && s.horizonDays() == 5).findFirst().orElseThrow();
        assertThat(neutral5.outcomes()).isGreaterThanOrEqualTo(1);
    }

    @Test void schemaRejectsUnknownTouchValues() {
        String run = save("BULLISH", 265598L, new BigDecimal("110"), new BigDecimal("95"));
        assertThatThrownBy(() -> outcomes.upsert(outcome(run, 5, "0.01", true, "MAYBE")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private String save(String assessment, Long conid, BigDecimal tp, BigDecimal sl) {
        String runId = UUID.randomUUID().toString();
        recommendations.save(new RecommendationRecord(runId, ticker, conid, Instant.now(), Instant.now(), "q", "COMPLETE", assessment,
                tp, sl, null, new BigDecimal("100"), LocalDate.of(2026, 9, 1), "DELAYED", List.of(), List.of(), 0, 0, "p", "m", null, "v",
                "{\"runId\":\"" + runId + "\"}"));
        return runId;
    }
    private static OutcomeRecord outcome(String runId, int horizon, String ret, Boolean correct, String touch) {
        return new OutcomeRecord(runId, horizon, Instant.now(), LocalDate.of(2026, 9, 1), new BigDecimal("100"), LocalDate.of(2026, 9, 10),
                new BigDecimal("105"), new BigDecimal(ret), 756733L, new BigDecimal("0.010000"), new BigDecimal(ret).subtract(new BigDecimal("0.010000")),
                correct, touch, touch.equals("NONE") || touch.equals("NO_LEVELS") ? null : LocalDate.of(2026, 9, 3),
                touch.equals("NONE") || touch.equals("NO_LEVELS") ? null : 2, new BigDecimal("0.080000"), new BigDecimal("-0.030000"), horizon);
    }
}
