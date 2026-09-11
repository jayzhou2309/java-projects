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
    @Min(1) @Max(30) private int maxToolCalls = 10;
    @Min(100) @Max(180000) private int deadlineMs = 60000;
    @Min(128) @Max(4096) private int maxOutputTokens = 1200;
    @Min(1000) @Max(100000) private int maxObservedTokens = 16000;
    @Min(1000) @Max(100000) private int maxToolResultChars = 24000;
    @Min(4000) @Max(200000) private int maxContextChars = 80000;
    @Min(1) @Max(3600) private int maxQuoteAgeSeconds = 120;
}
