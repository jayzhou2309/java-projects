package project.stockrecommendationengine.recommendation;

import jakarta.validation.constraints.*;

public record RecommendationRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9.-]{1,16}") String ticker,
        @NotBlank @Size(max = 4000) String question,
        @Positive Long conid,
        boolean includePortfolio) { }
