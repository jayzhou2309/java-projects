package project.stockrecommendationengine.outcome;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly scoring after the filing refresh; bars for evaluated contracts are refreshed on the way. */
@Component
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "outcomes.enabled", havingValue = "true")
public class OutcomeScheduler {
    private final OutcomeEvaluationService service;

    // Read from the properties bean so contexts without application.yaml (tests) still start.
    @Scheduled(cron = "#{@outcomeProperties.cron}", zone = "#{@outcomeProperties.zone}")
    public void nightly() { service.evaluateAll(); }
}
