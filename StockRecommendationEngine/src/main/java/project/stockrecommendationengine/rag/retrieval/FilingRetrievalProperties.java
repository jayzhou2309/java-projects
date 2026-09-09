package project.stockrecommendationengine.rag.retrieval;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "rag.retrieval")
@Validated
@Getter
@Setter
public class FilingRetrievalProperties {
    @Min(1) @Max(20)
    private int defaultTopK = 5;

    @Min(20) @Max(200)
    private int candidateCount = 40;

    private boolean latestFilingsOnly = true;
    private boolean rerankingEnabled = false;
}
