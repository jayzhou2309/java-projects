package project.stockrecommendationengine.recommendation;

import jakarta.validation.constraints.*;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties("recommendation")
@Validated
@Getter
@Setter
public class RecommendationProperties {
    private boolean enabled;
    private String model = "";
    @AssertTrue(message = "Set RECOMMENDATION_MODEL to a tool-capable chat model before enabling recommendations")
    public boolean isModelConfigured() { return !enabled || (model != null && !model.isBlank()); }
    @Min(1) @Max(20) private int maxModelCalls = 10;
    @Min(1) @Max(30) private int maxToolCalls = 12;
    @Min(100) @Max(300000) private int deadlineMs = 120000;
    /** Run the RAG and broker specialists concurrently when the manager delegates to both in one response. */
    private boolean parallelSpecialists = true;
    /** Ingest the latest filing of each listed type when the ticker has no embedded filings before RAG research. */
    private boolean autoIngest = true;
    @NotEmpty private List<@NotBlank String> autoIngestFilingTypes = List.of("10-K", "10-Q");
    /** With several listings and no request conid, the single listing in this currency is selected and disclosed. */
    @NotBlank private String preferredCurrency = "USD";
    @Min(128) @Max(4096) private int maxOutputTokens = 1200;
    @Min(1000) @Max(100000) private int maxObservedTokens = 16000;
    @Min(1000) @Max(100000) private int maxToolResultChars = 24000;
    @Min(4000) @Max(200000) private int maxContextChars = 80000;
    @Min(1) @Max(3600) private int maxQuoteAgeSeconds = 120;
}
