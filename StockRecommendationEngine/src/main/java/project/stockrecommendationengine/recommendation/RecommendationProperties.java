package project.stockrecommendationengine.recommendation;

import jakarta.validation.constraints.*;
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
    /** Before RAG research, ingest a ticker with no embedded filings or refresh one past its filing cadence (rag.refresh limits). */
    private boolean autoIngest = true;
    /** Prior stored runs for the ticker shown to the manager with their outcomes; 0 disables the look-back. */
    @Min(0) @Max(50) private int trackRecordRuns = 10;
    /** With several listings and no request conid, the single listing in this currency is selected and disclosed. */
    @NotBlank private String preferredCurrency = "USD";
    @Min(128) @Max(4096) private int maxOutputTokens = 1200;
    @Min(1000) @Max(100000) private int maxObservedTokens = 16000;
    @Min(1000) @Max(100000) private int maxToolResultChars = 32000;
    @Min(4000) @Max(200000) private int maxContextChars = 120000;
    @Min(1) @Max(3600) private int maxQuoteAgeSeconds = 120;
}
