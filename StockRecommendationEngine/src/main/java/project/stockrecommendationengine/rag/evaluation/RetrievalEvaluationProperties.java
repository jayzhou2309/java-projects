package project.stockrecommendationengine.rag.evaluation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
    /** Chunks retrieved per question; a question whose passage sits beyond this window counts as a miss. */
    @Min(5) @Max(20)
    private int window = 10;
}
