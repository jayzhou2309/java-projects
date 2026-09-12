package project.stockrecommendationengine.outcome;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import project.stockrecommendationengine.broker.BrokerData.DailyBar;
import project.stockrecommendationengine.recommendation.RecommendationRecord;

/**
 * Pure scoring over bars strictly after the recommendation's bars_as_of date. Entry is the stored last close.
 * The horizon exit is the close of the h-th trading day; first touch walks the highs and lows up to it.
 */
final class OutcomeCalculator {
    private OutcomeCalculator() { }

    /** Empty when fewer than {@code horizon} bars follow the as-of date. */
    static Optional<OutcomeRecord> evaluate(RecommendationRecord rec, List<DailyBar> barsAfterAsOf,
            List<DailyBar> benchmarkBars, Long benchmarkConid, int horizon, Instant evaluatedAt) {
        if (rec.barsAsOf() == null || rec.lastClose() == null || rec.lastClose().signum() <= 0) return Optional.empty();
        var path = barsAfterAsOf.stream().filter(b -> b.date().isAfter(rec.barsAsOf())).sorted((a, b) -> a.date().compareTo(b.date())).toList();
        if (path.size() < horizon) return Optional.empty();
        var window = path.subList(0, horizon);
        BigDecimal entry = rec.lastClose();
        DailyBar exit = window.get(horizon - 1);
        BigDecimal ret = fraction(exit.close(), entry);

        boolean bullish = "BULLISH".equals(rec.assessment()), bearish = "BEARISH".equals(rec.assessment());
        String touch = "NO_LEVELS";
        LocalDate touchDate = null;
        Integer daysToTouch = null;
        if ((bullish || bearish) && rec.takeProfit() != null && rec.stopLoss() != null) {
            touch = "NONE";
            for (int i = 0; i < window.size(); i++) {
                DailyBar bar = window.get(i);
                boolean tp = bullish ? bar.high().compareTo(rec.takeProfit()) >= 0 : bar.low().compareTo(rec.takeProfit()) <= 0;
                boolean sl = bullish ? bar.low().compareTo(rec.stopLoss()) <= 0 : bar.high().compareTo(rec.stopLoss()) >= 0;
                if (tp || sl) {
                    // Daily bars cannot order intraday touches; both in one bar is reported as such, never guessed.
                    touch = tp && sl ? "BOTH_SAME_DAY" : tp ? "TAKE_PROFIT" : "STOP_LOSS";
                    touchDate = bar.date();
                    daysToTouch = i + 1;
                    break;
                }
            }
        }
        BigDecimal best = entry, worst = entry;
        for (DailyBar bar : window) {
            if (bullish || !bearish) { best = best.max(bar.high()); worst = worst.min(bar.low()); }
            else { best = best.min(bar.low()); worst = worst.max(bar.high()); }
        }
        BigDecimal favorable = bearish ? fraction(best, entry).negate() : fraction(best, entry);
        BigDecimal adverse = bearish ? fraction(worst, entry).negate() : fraction(worst, entry);
        Boolean correct = null;
        if (bullish) correct = ret.signum() > 0;
        else if (bearish) correct = ret.signum() < 0;

        BigDecimal benchmarkReturn = null;
        if (benchmarkBars != null && !benchmarkBars.isEmpty()) {
            Map<LocalDate, DailyBar> byDate = benchmarkBars.stream().collect(Collectors.toMap(DailyBar::date, Function.identity(), (a, b) -> b));
            DailyBar start = byDate.get(rec.barsAsOf()), end = byDate.get(exit.date());
            if (start != null && end != null) benchmarkReturn = fraction(end.close(), start.close());
        }
        BigDecimal excess = benchmarkReturn == null ? null : ret.subtract(benchmarkReturn);
        return Optional.of(new OutcomeRecord(rec.runId(), horizon, evaluatedAt, rec.barsAsOf(), entry, exit.date(), exit.close(),
                ret, benchmarkReturn == null ? null : benchmarkConid, benchmarkReturn, excess, correct, touch, touchDate, daysToTouch,
                favorable, adverse, window.size()));
    }

    static BigDecimal fraction(BigDecimal value, BigDecimal base) {
        return value.divide(base, 10, RoundingMode.HALF_UP).subtract(BigDecimal.ONE).setScale(6, RoundingMode.HALF_UP);
    }
}
