package project.stockrecommendationengine.rag.evaluation;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "rag.evaluation")
@Validated
@Getter
@Setter
public class RetrievalEvaluationProperties {
    /**
     * Classpath resource of the evaluation set that {@link RetrievalEvaluationSetLoader#load()} reads and every run
     * evaluates; recorded in each snapshot's {@code properties.set}. Set v1 stays bundled for comparison runs, but
     * metrics are only comparable between snapshots of the same set.
     */
    @NotBlank
    private String set = RetrievalEvaluationSetLoader.DEFAULT_RESOURCE;

    /** Chunks retrieved per question; a question whose passage sits beyond this window counts as a miss. */
    @Min(5) @Max(20)
    private int window = 10;

    /**
     * Regression floor for hit@5 asserted by the opt-in live test (RetrievalEvaluationLiveTests). Set from the first
     * baseline (snapshot 13, hit@5 0.6) minus 0.1, rounded down to a multiple of 0.05. A value above 1 can never be met,
     * which is how the assertion is proven live.
     */
    @NotNull @DecimalMin("0.0")
    private BigDecimal minHitAt5 = new BigDecimal("0.50");
}
