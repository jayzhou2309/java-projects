package project.stockrecommendationengine.outcome;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly scoring after the filing refresh, then a calibration snapshot; bars for evaluated contracts are refreshed on the way. */
@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "outcomes.enabled", havingValue = "true")
public class OutcomeScheduler {
    private final OutcomeEvaluationService service;
    private final ConfidenceCalibrationService calibration;

    // Read from the properties bean so contexts without application.yaml (tests) still start.
    @Scheduled(cron = "#{@outcomeProperties.cron}", zone = "#{@outcomeProperties.zone}")
    public void nightly() {
        service.evaluateAll();
        try { calibration.compute(); }
        catch (RuntimeException ex) { log.warn("Confidence calibration failed: {}", ex.getClass().getSimpleName()); }
    }
}
