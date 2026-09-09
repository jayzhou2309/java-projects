package project.stockrecommendationengine.rag.dto;

import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

public record IngestionRequest(
        @NotBlank String ticker,
        @NotEmpty List<@NotBlank String> filingTypes,
        @Positive int limit
) {
}
