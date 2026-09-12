package project.stockrecommendationengine.outcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import project.stockrecommendationengine.outcome.ConfidenceCalibration.Bin;
import project.stockrecommendationengine.recommendation.RecommendationRecord;
import project.stockrecommendationengine.recommendation.RecommendationRepository;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class CalibrationRepositoryTests {
    @Autowired CalibrationRepository calibrations;
    @Autowired OutcomeRepository outcomes;
    @Autowired RecommendationRepository recommendations;
    private final String ticker = "CC" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();

    @Test void samplesJoinScoredDirectionalRunsWithARawConfidenceAtTheHorizonOnly() {
        String bullish = save("BULLISH", new BigDecimal("0.71"));
        String bearish = save("BEARISH", new BigDecimal("0.42"));
        String neutral = save("NEUTRAL", new BigDecimal("0.60"));
        String noConfidence = save("BULLISH", null);
        outcomes.upsert(outcome(bullish, 20, true));
        outcomes.upsert(outcome(bullish, 5, false));      // other horizon
        outcomes.upsert(outcome(bearish, 20, false));
        outcomes.upsert(outcome(neutral, 20, null));      // no direction
        outcomes.upsert(outcome(noConfidence, 20, true)); // no confidence stored
        var samples = calibrations.samples(20).stream().filter(s -> s.promptVersion().equals(ticker)).toList();
        assertThat(samples).hasSize(2);
        assertThat(samples).extracting(s -> s.assessment() + ":" + s.confidence().toPlainString() + ":" + s.directionCorrect())
                .containsExactly("BULLISH:0.7100:true", "BEARISH:0.4200:false");
    }

    @Test void snapshotsRoundTripAndTheNewestPerHorizonIsReturned() {
        // Real snapshots may exist in the shared database; timestamps in the future keep these rows the newest.
        Instant base = Instant.now().plusSeconds(3600);
        var bins = List.of(new Bin(new BigDecimal("0.000000"), new BigDecimal("0.500000"), 0, null, null, null, null, new BigDecimal("0.550000")),
                new Bin(new BigDecimal("0.500000"), new BigDecimal("1.000000"), 40, new BigDecimal("0.700000"), new BigDecimal("0.550000"),
                        new BigDecimal("0.400000"), new BigDecimal("0.690000"), new BigDecimal("0.550000")));
        var first = calibrations.save(new ConfidenceCalibration(null, base, 20, "INSUFFICIENT_SAMPLE", 12, 30, 10,
                new BigDecimal("0.5"), new BigDecimal("0.2"), new BigDecimal("0.3"), bins, Map.of("BULLISH", 12), Map.of("p1", 12), ConfidenceCalibration.CAVEAT));
        assertThat(first.id()).isNotNull();
        var second = calibrations.save(new ConfidenceCalibration(null, base.plusSeconds(60), 20, "READY", 40, 30, 10,
                new BigDecimal("0.55"), new BigDecimal("0.15"), new BigDecimal("0.29"), bins, Map.of("BULLISH", 30, "BEARISH", 10),
                Map.of("p1", 20, "p2", 20), ConfidenceCalibration.CAVEAT));
        calibrations.save(new ConfidenceCalibration(null, base.plusSeconds(120), 998, "READY", 50, 30, 10,
                new BigDecimal("0.5"), new BigDecimal("0.1"), new BigDecimal("0.2"), bins, Map.of(), Map.of(), ConfidenceCalibration.CAVEAT));
        var latest = calibrations.latest(20).orElseThrow();
        assertThat(latest.id()).isEqualTo(second.id());
        assertThat(latest.status()).isEqualTo("READY");
        assertThat(latest.samples()).isEqualTo(40);
        assertThat(latest.baseRate()).isEqualByComparingTo("0.55");
        assertThat(latest.expectedCalibrationError()).isEqualByComparingTo("0.15");
        assertThat(latest.bins()).hasSize(2);
        assertThat(latest.bins().get(1).samples()).isEqualTo(40);
        assertThat(latest.bins().get(1).intervalUpper()).isEqualByComparingTo("0.69");
        assertThat(latest.bins().get(0).hitRate()).isNull();
        assertThat(latest.assessments()).containsEntry("BULLISH", 30).containsEntry("BEARISH", 10);
        assertThat(latest.promptVersions()).containsEntry("p2", 20);
        assertThat(latest.binFor(new BigDecimal("0.9")).calibrated()).isEqualByComparingTo("0.55");
        assertThat(calibrations.latest(998).orElseThrow().samples()).isEqualTo(50);
        assertThat(calibrations.latest(999)).isEmpty();
        assertThatThrownBy(() -> calibrations.save(new ConfidenceCalibration(null, Instant.now(), 20, "MAYBE", 1, 30, 10, null, null, null,
                bins, Map.of(), Map.of(), ConfidenceCalibration.CAVEAT))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private String save(String assessment, BigDecimal confidence) {
        String runId = UUID.randomUUID().toString();
        // The prompt version carries this test's ticker so the sample query can be filtered to rows written here.
        recommendations.save(new RecommendationRecord(runId, ticker, 265598L, Instant.now(), Instant.now(), "q", "COMPLETE", assessment,
                null, null, confidence, new BigDecimal("100"), LocalDate.of(2026, 9, 1), "DELAYED", List.of(), List.of(), 0, 0, ticker, "m", null, "v",
                "{\"runId\":\"" + runId + "\"}"));
        return runId;
    }
    private static OutcomeRecord outcome(String runId, int horizon, Boolean correct) {
        return new OutcomeRecord(runId, horizon, Instant.now(), LocalDate.of(2026, 9, 1), new BigDecimal("100"), LocalDate.of(2026, 9, 30),
                new BigDecimal("105"), new BigDecimal("0.05"), null, null, null, correct, correct == null ? "NO_LEVELS" : "NONE", null, null,
                new BigDecimal("0.08"), new BigDecimal("-0.03"), horizon);
    }
}
