package project.stockrecommendationengine.outcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.stockrecommendationengine.broker.BrokerData.DailyBar;
import project.stockrecommendationengine.recommendation.RecommendationRecord;
import static org.assertj.core.api.Assertions.*;

class OutcomeCalculatorTests {
    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 1);
    private static final Instant NOW = Instant.parse("2026-09-12T02:00:00Z");

    @Test void bullishReturnTouchAndExcursionsUseOnlyBarsAfterAsOf() {
        // Entry 100; TP 110, SL 95. Day 1: 100-104, day 2 high 111 touches TP, day 5 closes 108.
        var rec = rec("BULLISH", "110", "95");
        var bars = new ArrayList<DailyBar>();
        bars.add(bar(AS_OF, 90, 120, 100));  // same day as as-of: must be ignored even though it spans both levels
        bars.add(bar(AS_OF.plusDays(1), 99, 104, 103));
        bars.add(bar(AS_OF.plusDays(2), 101, 111, 109));
        bars.add(bar(AS_OF.plusDays(3), 105, 110, 106));
        bars.add(bar(AS_OF.plusDays(4), 103, 107, 104));
        bars.add(bar(AS_OF.plusDays(5), 106, 109, 108));
        var benchmark = List.of(bar(AS_OF, 50, 50, 50), bar(AS_OF.plusDays(5), 51, 51, 51));
        var outcome = OutcomeCalculator.evaluate(rec, bars, benchmark, 756733L, 5, NOW).orElseThrow();
        assertThat(outcome.exitDate()).isEqualTo(AS_OF.plusDays(5));
        assertThat(outcome.returnPct()).isEqualByComparingTo("0.080000");
        assertThat(outcome.benchmarkReturnPct()).isEqualByComparingTo("0.020000");
        assertThat(outcome.excessReturnPct()).isEqualByComparingTo("0.060000");
        assertThat(outcome.benchmarkConid()).isEqualTo(756733L);
        assertThat(outcome.directionCorrect()).isTrue();
        assertThat(outcome.firstTouch()).isEqualTo("TAKE_PROFIT");
        assertThat(outcome.touchDate()).isEqualTo(AS_OF.plusDays(2));
        assertThat(outcome.daysToTouch()).isEqualTo(2);
        assertThat(outcome.maxFavorablePct()).isEqualByComparingTo("0.110000");
        assertThat(outcome.maxAdversePct()).isEqualByComparingTo("-0.010000");
        assertThat(outcome.barsUsed()).isEqualTo(5);
    }

    @Test void bearishInvertsLevelsAndExcursions() {
        var rec = rec("BEARISH", "90", "105");
        var bars = List.of(bar(AS_OF.plusDays(1), 96, 102, 98), bar(AS_OF.plusDays(2), 89, 99, 91));
        var outcome = OutcomeCalculator.evaluate(rec, bars, List.of(), 756733L, 2, NOW).orElseThrow();
        assertThat(outcome.returnPct()).isEqualByComparingTo("-0.090000");
        assertThat(outcome.directionCorrect()).isTrue();
        assertThat(outcome.firstTouch()).isEqualTo("TAKE_PROFIT");
        assertThat(outcome.maxFavorablePct()).as("largest move in the favourable (down) direction").isEqualByComparingTo("0.110000");
        assertThat(outcome.maxAdversePct()).isEqualByComparingTo("-0.020000");
        assertThat(outcome.benchmarkReturnPct()).isNull();
        assertThat(outcome.excessReturnPct()).isNull();
        assertThat(outcome.benchmarkConid()).isNull();
    }

    @Test void sameBarTouchIsReportedNotGuessedAndStopLossFirstIsWrongDirection() {
        var rec = rec("BULLISH", "110", "95");
        var both = List.of(bar(AS_OF.plusDays(1), 94, 111, 100));
        assertThat(OutcomeCalculator.evaluate(rec, both, null, null, 1, NOW).orElseThrow().firstTouch()).isEqualTo("BOTH_SAME_DAY");
        var stop = List.of(bar(AS_OF.plusDays(1), 94, 101, 96), bar(AS_OF.plusDays(2), 95, 112, 111));
        var outcome = OutcomeCalculator.evaluate(rec, stop, null, null, 2, NOW).orElseThrow();
        assertThat(outcome.firstTouch()).isEqualTo("STOP_LOSS");
        assertThat(outcome.daysToTouch()).isEqualTo(1);
        assertThat(outcome.directionCorrect()).as("close 111 is above entry even though the stop hit first").isTrue();
    }

    @Test void neutralHasNoDirectionOrLevelsAndShortSeriesIsPending() {
        var rec = rec("NEUTRAL", null, null);
        var bars = List.of(bar(AS_OF.plusDays(1), 99, 101, 100), bar(AS_OF.plusDays(2), 100, 103, 102));
        var outcome = OutcomeCalculator.evaluate(rec, bars, null, null, 2, NOW).orElseThrow();
        assertThat(outcome.directionCorrect()).isNull();
        assertThat(outcome.firstTouch()).isEqualTo("NO_LEVELS");
        assertThat(outcome.returnPct()).isEqualByComparingTo("0.020000");
        assertThat(OutcomeCalculator.evaluate(rec, bars, null, null, 3, NOW)).isEmpty();
        assertThat(OutcomeCalculator.evaluate(rec("BULLISH", "1", "1", null), bars, null, null, 1, NOW)).isEmpty();
    }

    private static DailyBar bar(LocalDate date, double low, double high, double close) {
        return new DailyBar(date, BigDecimal.valueOf(close), BigDecimal.valueOf(high), BigDecimal.valueOf(low), BigDecimal.valueOf(close), BigDecimal.ONE);
    }
    private static RecommendationRecord rec(String assessment, String tp, String sl) { return rec(assessment, tp, sl, new BigDecimal("100")); }
    private static RecommendationRecord rec(String assessment, String tp, String sl, BigDecimal entry) {
        return new RecommendationRecord("run-1", "AAPL", 265598L, NOW, NOW, "q", "COMPLETE", assessment,
                tp == null ? null : new BigDecimal(tp), sl == null ? null : new BigDecimal(sl), null, entry, AS_OF, "DELAYED",
                List.of(), List.of(), 0, 0, "p", "m", null, "v", "{}");
    }
}
