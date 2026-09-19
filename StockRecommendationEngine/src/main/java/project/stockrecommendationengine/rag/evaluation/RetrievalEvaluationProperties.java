package project.stockrecommendationengine.rag.evaluation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
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
     * Regression floor for hit@5 asserted by the opt-in live test (RetrievalEvaluationLiveTests): the baseline's hit@5
     * minus 0.1, rounded down to a multiple of 0.05. Derived on set v2 (snapshot 69, hit@5 0.785714, gives 0.65); this
     * supersedes the set v1 floor of 0.50 (snapshot 13, hit@5 0.6), since metrics are not comparable across sets. The higher value is not stricter
     * for the 30 questions carried from v1: 0.65 needs 28 of 42 hits, so if the 12 figure-literal questions hit, 16 of the
     * 30 carried questions suffice (about 0.53, roughly v1's 0.50); {@link #minNonFigureHitAt5} separately requires 18 of those 30. A value
     * above 1 can never be met, which is how the assertion is proven live.
     */
    @NotNull @DecimalMin("0.0")
    private BigDecimal minHitAt5 = new BigDecimal("0.65");

    /**
     * Second regression floor, over the non-figure slice only (questions whose text has no figure by
     * {@code FilingRetrievalRepository.figureTerms}), asserted by the same live test after the aggregate floor. Derived by
     * the same rule from set v2 snapshot 91 (2026-09-13, current defaults; reproduces snapshot 69 question by question), whose non-figure slice hit@5 is 0.700000 (21 of 30): 0.70 - 0.1 = 0.60.
     * It needs 18 of the 30 non-figure questions to hit at 5, so at most three of that snapshot's 21 non-figure hits can be
     * lost; the aggregate floor alone allowed five. It is a guard against regressions, not a quality target. An empty
     * non-figure slice fails the live test rather than passing.
     */
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0")
    private BigDecimal minNonFigureHitAt5 = new BigDecimal("0.60");

    /** Settings of the answer evaluation ({@code POST /api/rag/evaluate/answers}); see {@link AnswerEvaluationService}. */
    @Valid @NotNull
    private Answers answers = new Answers();

    @Getter
    @Setter
    public static class Answers {
        /**
         * Pause between two recommendation runs of one pass (none after the last). Every run calls the chat model; the
         * default keeps a lean pass (about 7,000 tokens per run) under a 30,000 tokens-per-minute provider allowance.
         */
        @Min(0) @Max(600000)
        private int pauseMs = 20000;
    }
}
