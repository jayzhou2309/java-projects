package project.stockrecommendationengine.outcome;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import project.stockrecommendationengine.outcome.ConfidenceCalibration.Bin;
import project.stockrecommendationengine.outcome.ConfidenceCalibration.Sample;

/**
 * Histogram-binning calibration. Equal-width bins over [0,1]; each bin's calibrated value is its hit rate shrunk toward
 * the overall hit rate with priorWeight pseudo-samples, so a thin bin moves little from the base rate and an empty bin
 * equals it. Expected calibration error and Brier score describe the raw confidence, in-sample. Pure arithmetic.
 */
final class CalibrationCalculator {
    private CalibrationCalculator() { }
    private static final double Z = 1.959964; // two-sided 95%

    static ConfidenceCalibration compute(List<Sample> samples, int horizonDays, int binCount, int minSamples, int priorWeight,
            Instant computedAt) {
        int n = samples.size();
        int hits = 0;
        int[] counts = new int[binCount];
        int[] binHits = new int[binCount];
        double[] confidenceSum = new double[binCount];
        double squaredError = 0;
        var assessments = new TreeMap<String, Integer>();
        var promptVersions = new TreeMap<String, Integer>();
        for (Sample sample : samples) {
            double confidence = sample.confidence().doubleValue();
            int bin = ConfidenceCalibration.binIndex(sample.confidence(), binCount);
            counts[bin]++;
            confidenceSum[bin] += confidence;
            if (sample.directionCorrect()) { hits++; binHits[bin]++; }
            double error = confidence - (sample.directionCorrect() ? 1.0 : 0.0);
            squaredError += error * error;
            assessments.merge(String.valueOf(sample.assessment()), 1, Integer::sum);
            promptVersions.merge(String.valueOf(sample.promptVersion()), 1, Integer::sum);
        }
        Double base = n == 0 ? null : (double) hits / n;
        double ece = 0;
        var bins = new ArrayList<Bin>(binCount);
        for (int b = 0; b < binCount; b++) {
            BigDecimal lower = fraction(b, binCount), upper = fraction(b + 1, binCount);
            if (counts[b] == 0) {
                bins.add(new Bin(lower, upper, 0, null, null, null, null, base == null ? null : scaled(base)));
                continue;
            }
            double mean = confidenceSum[b] / counts[b];
            double hitRate = (double) binHits[b] / counts[b];
            ece += (double) counts[b] / n * Math.abs(hitRate - mean);
            double[] interval = wilson(binHits[b], counts[b]);
            double calibrated = (binHits[b] + priorWeight * base) / (counts[b] + priorWeight);
            bins.add(new Bin(lower, upper, counts[b], scaled(mean), scaled(hitRate), scaled(interval[0]), scaled(interval[1]), scaled(calibrated)));
        }
        String status = n >= minSamples ? "READY" : "INSUFFICIENT_SAMPLE";
        return new ConfidenceCalibration(null, computedAt, horizonDays, status, n, minSamples, priorWeight,
                base == null ? null : scaled(base), n == 0 ? null : scaled(ece), n == 0 ? null : scaled(squaredError / n),
                List.copyOf(bins), Map.copyOf(assessments), Map.copyOf(promptVersions), ConfidenceCalibration.CAVEAT);
    }

    /** Wilson score interval for k successes in n trials at 95%. */
    static double[] wilson(int k, int n) {
        double p = (double) k / n, z2 = Z * Z;
        double denominator = 1 + z2 / n;
        double centre = (p + z2 / (2 * n)) / denominator;
        double half = Z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denominator;
        return new double[] { Math.max(0, centre - half), Math.min(1, centre + half) };
    }
    private static BigDecimal fraction(int numerator, int denominator) {
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }
    private static BigDecimal scaled(double value) { return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP); }
}
