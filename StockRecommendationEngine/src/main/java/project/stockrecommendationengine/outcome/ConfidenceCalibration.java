package project.stockrecommendationengine.outcome;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One calibration snapshot: the input-coverage confidence of stored directional runs binned against their realized
 * direction hits at the reference horizon. status is READY when at least minSamples runs were scored, else
 * INSUFFICIENT_SAMPLE and nothing is applied. Every bin carries its count so no rate is read without its denominator.
 */
public record ConfidenceCalibration(Long id, Instant computedAt, int horizonDays, String status, int samples, int minSamples,
        int priorWeight, BigDecimal baseRate, BigDecimal expectedCalibrationError, BigDecimal brierScore, List<Bin> bins,
        Map<String, Integer> assessments, Map<String, Integer> promptVersions, String caveat) {
    public static final String CAVEAT = "Calibration maps the input-coverage confidence to the realized direction hit rate of prior "
            + "runs with similar confidence, shrunk toward the overall hit rate by priorWeight pseudo-samples. It is in-sample, pooled "
            + "across assessments and versions, and only as informative as the sample counts shown.";

    /** One raw-confidence interval [lower, upper): its runs, their mean confidence, hit rate with a 95% Wilson interval, and the calibrated value. */
    public record Bin(BigDecimal lower, BigDecimal upper, int samples, BigDecimal meanConfidence, BigDecimal hitRate,
            BigDecimal intervalLower, BigDecimal intervalUpper, BigDecimal calibrated) { }

    /** One scored directional run: the raw confidence it was stored with and whether its direction proved right. */
    public record Sample(BigDecimal confidence, boolean directionCorrect, String assessment, String promptVersion) { }

    public boolean ready() { return "READY".equals(status); }

    /** The bin a raw confidence in [0,1] falls into; 1.0 belongs to the last bin. */
    public Bin binFor(BigDecimal confidence) {
        return bins.get(binIndex(confidence, bins.size()));
    }
    static int binIndex(BigDecimal confidence, int binCount) {
        BigDecimal clamped = confidence.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        int index = clamped.multiply(BigDecimal.valueOf(binCount)).setScale(0, RoundingMode.FLOOR).intValue();
        return Math.min(binCount - 1, index);
    }
}
