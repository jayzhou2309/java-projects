package project.stockrecommendationengine.outcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.stockrecommendationengine.outcome.ConfidenceCalibration.Sample;
import static org.assertj.core.api.Assertions.*;

class CalibrationCalculatorTests {
    private static final Instant NOW = Instant.parse("2026-09-12T05:00:00Z");

    @Test void binsShrinkTowardTheBaseRateAndReportErrorAndIntervalsAgainstHandComputedValues() {
        var samples = new ArrayList<Sample>();
        for (int i = 0; i < 20; i++) samples.add(sample("0.50", i < 10, "BULLISH", "p1"));   // bin [0.4,0.6): 10 of 20 right
        for (int i = 0; i < 20; i++) samples.add(sample("0.90", i < 12, i % 2 == 0 ? "BULLISH" : "BEARISH", "p2")); // bin [0.8,1.0]: 12 of 20
        var c = CalibrationCalculator.compute(samples, 20, 5, 30, 10, NOW);
        assertThat(c.status()).isEqualTo("READY");
        assertThat(c.samples()).isEqualTo(40);
        assertThat(c.horizonDays()).isEqualTo(20);
        assertThat(c.baseRate()).isEqualByComparingTo("0.550000");
        assertThat(c.expectedCalibrationError()).as("20/40 * |0.6 - 0.9|").isEqualByComparingTo("0.150000");
        assertThat(c.brierScore()).as("(20*0.25 + 12*0.01 + 8*0.81) / 40").isEqualByComparingTo("0.290000");
        assertThat(c.bins()).hasSize(5);
        var empty = c.bins().get(0);
        assertThat(empty.samples()).isZero();
        assertThat(empty.hitRate()).isNull();
        assertThat(empty.calibrated()).as("an empty bin equals the base rate").isEqualByComparingTo("0.550000");
        var middle = c.bins().get(2);
        assertThat(middle.lower()).isEqualByComparingTo("0.4");
        assertThat(middle.upper()).isEqualByComparingTo("0.6");
        assertThat(middle.meanConfidence()).isEqualByComparingTo("0.500000");
        assertThat(middle.hitRate()).isEqualByComparingTo("0.500000");
        assertThat(middle.calibrated()).as("(10 + 10*0.55) / 30").isEqualByComparingTo("0.516667");
        var top = c.bins().get(4);
        assertThat(top.hitRate()).isEqualByComparingTo("0.600000");
        assertThat(top.calibrated()).as("(12 + 10*0.55) / 30").isEqualByComparingTo("0.583333");
        assertThat(top.intervalLower().doubleValue()).isCloseTo(0.3866, within(0.0005));
        assertThat(top.intervalUpper().doubleValue()).isCloseTo(0.7812, within(0.0005));
        assertThat(c.assessments()).containsEntry("BULLISH", 30).containsEntry("BEARISH", 10);
        assertThat(c.promptVersions()).containsEntry("p1", 20).containsEntry("p2", 20);
        assertThat(c.binFor(new BigDecimal("0.90"))).isSameAs(top);
        assertThat(c.binFor(new BigDecimal("1.00"))).as("1.0 belongs to the last bin").isSameAs(top);
        assertThat(c.binFor(new BigDecimal("0.20"))).isSameAs(c.bins().get(1));
        assertThat(c.binFor(BigDecimal.ZERO)).isSameAs(empty);
        assertThat(c.caveat()).contains("in-sample");
    }

    @Test void tooFewSamplesAreInsufficientAndNoSamplesLeaveEveryStatisticNull() {
        var few = CalibrationCalculator.compute(List.of(sample("0.7", true, "BULLISH", "p"), sample("0.7", false, "BEARISH", "p")), 20, 5, 30, 10, NOW);
        assertThat(few.status()).isEqualTo("INSUFFICIENT_SAMPLE");
        assertThat(few.ready()).isFalse();
        assertThat(few.samples()).isEqualTo(2);
        assertThat(few.baseRate()).isEqualByComparingTo("0.500000");
        assertThat(few.binFor(new BigDecimal("0.7")).calibrated()).as("(1 + 10*0.5) / 12").isEqualByComparingTo("0.500000");
        var none = CalibrationCalculator.compute(List.of(), 20, 5, 30, 10, NOW);
        assertThat(none.samples()).isZero();
        assertThat(none.baseRate()).isNull();
        assertThat(none.expectedCalibrationError()).isNull();
        assertThat(none.brierScore()).isNull();
        assertThat(none.bins()).allSatisfy(bin -> { assertThat(bin.samples()).isZero(); assertThat(bin.calibrated()).isNull(); });
        assertThat(none.assessments()).isEmpty();
    }

    @Test void zeroPriorWeightUsesRawBinHitRates() {
        var samples = new ArrayList<Sample>();
        for (int i = 0; i < 4; i++) samples.add(sample("0.75", i < 3, "BULLISH", "p"));
        var c = CalibrationCalculator.compute(samples, 5, 4, 1, 0, NOW);
        assertThat(c.status()).isEqualTo("READY");
        assertThat(c.binFor(new BigDecimal("0.75")).calibrated()).isEqualByComparingTo("0.750000");
        assertThat(c.bins().get(0).calibrated()).as("empty bin still equals the base rate").isEqualByComparingTo("0.750000");
        assertThat(CalibrationCalculator.wilson(0, 10)[0]).isZero();
        assertThat(CalibrationCalculator.wilson(10, 10)[1]).isCloseTo(1.0, within(1e-9));
    }

    private static Sample sample(String confidence, boolean correct, String assessment, String prompt) {
        return new Sample(new BigDecimal(confidence), correct, assessment, prompt);
    }
}
