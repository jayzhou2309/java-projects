package project.stockrecommendationengine.outcome;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Computes calibration snapshots from stored outcomes and applies the newest READY one to a run's raw confidence.
 * Read-only for the recommendation loop; never called by a model. Calibration never replaces the raw confidence in
 * the audit row, so later snapshots always calibrate the same predictor.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "outcomes.enabled", havingValue = "true")
public class ConfidenceCalibrationService {
    private final CalibrationRepository repository;
    private final OutcomeProperties properties;
    private final Clock clock;

    @Autowired
    public ConfidenceCalibrationService(CalibrationRepository repository, OutcomeProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }
    ConfidenceCalibrationService(CalibrationRepository repository, OutcomeProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** status APPLIED with the bin's calibrated value, or INSUFFICIENT_SAMPLE / NO_CALIBRATION with nothing applied. */
    public record Applied(String status, BigDecimal calibratedConfidence, ConfidenceCalibration calibration, ConfidenceCalibration.Bin bin) { }

    /** Compute a snapshot from every scored directional run at the reference horizon and store it. */
    public ConfidenceCalibration compute() {
        int horizon = properties.referenceHorizon();
        var settings = properties.getCalibration();
        var samples = repository.samples(horizon);
        var calibration = CalibrationCalculator.compute(samples, horizon, settings.getBins(), settings.getMinSamples(),
                settings.getPriorWeight(), clock.instant());
        var stored = repository.save(calibration);
        log.info("Confidence calibration: id={} horizon={} status={} samples={} baseRate={} ece={} brier={}", stored.id(), horizon,
                stored.status(), stored.samples(), stored.baseRate(), stored.expectedCalibrationError(), stored.brierScore());
        return stored;
    }

    public Optional<ConfidenceCalibration> latest() { return repository.latest(properties.referenceHorizon()); }

    public Applied apply(BigDecimal rawConfidence) {
        var latest = latest();
        if (latest.isEmpty()) return new Applied("NO_CALIBRATION", null, null, null);
        var calibration = latest.get();
        if (!calibration.ready()) return new Applied("INSUFFICIENT_SAMPLE", null, calibration, null);
        var bin = calibration.binFor(rawConfidence);
        return new Applied("APPLIED", bin.calibrated(), calibration, bin);
    }
}
