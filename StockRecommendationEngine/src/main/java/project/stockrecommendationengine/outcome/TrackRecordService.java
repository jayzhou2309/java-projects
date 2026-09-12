package project.stockrecommendationengine.outcome;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import project.stockrecommendationengine.recommendation.RecommendationRecord;
import project.stockrecommendationengine.recommendation.RecommendationRepository;

/** Read-only look-back over stored runs and outcomes; never called by the model directly. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "outcomes.enabled", havingValue = "true")
public class TrackRecordService {
    public static final String CAVEAT = "Prior runs are evidence, not proof. Samples are small; a rate without its count is meaningless. "
            + "Outcomes use daily bars after each run's bar date; NEUTRAL runs are not scored for direction.";
    private final RecommendationRepository recommendations;
    private final OutcomeRepository outcomes;
    private final OutcomeProperties properties;

    /** The newest {@code limit} stored runs for the ticker with any stored outcomes, plus per-assessment statistics. */
    public TrackRecord trackRecord(String ticker, int limit) {
        String normalized = ticker.trim().toUpperCase(Locale.ROOT);
        int reference = properties.getHorizons().contains(20) ? 20 : properties.getHorizons().get(0);
        var runs = new ArrayList<TrackRecord.PriorRun>();
        var byAssessment = new LinkedHashMap<String, List<TrackRecord.PriorRun>>();
        for (RecommendationRecord rec : recommendations.findByTicker(normalized, limit)) {
            var scored = outcomes.findByRunId(rec.runId()).stream()
                    .map(o -> new TrackRecord.HorizonOutcome(o.horizonDays(), o.returnPct(), o.excessReturnPct(), o.directionCorrect(), o.firstTouch()))
                    .toList();
            var run = new TrackRecord.PriorRun(rec.runId(), rec.requestedAt(), rec.status(), rec.assessment(), rec.confidence(),
                    rec.lastClose(), rec.barsAsOf(), rec.takeProfit(), rec.stopLoss(), scored);
            runs.add(run);
            byAssessment.computeIfAbsent(rec.assessment(), key -> new ArrayList<>()).add(run);
        }
        var stats = new ArrayList<TrackRecord.AssessmentStats>();
        byAssessment.forEach((assessment, group) -> {
            int scored = 0, correct = 0;
            BigDecimal sum = BigDecimal.ZERO;
            for (var run : group) {
                var at = run.outcomes().stream().filter(o -> o.horizonDays() == reference).findFirst();
                if (at.isEmpty()) continue;
                scored++;
                sum = sum.add(at.get().returnPct());
                if (Boolean.TRUE.equals(at.get().directionCorrect())) correct++;
            }
            BigDecimal average = scored == 0 ? null : sum.divide(BigDecimal.valueOf(scored), 6, RoundingMode.HALF_UP);
            stats.add(new TrackRecord.AssessmentStats(assessment, group.size(), scored, correct, average, reference));
        });
        return new TrackRecord(normalized, runs.size(), List.copyOf(runs), List.copyOf(stats), CAVEAT);
    }
}
