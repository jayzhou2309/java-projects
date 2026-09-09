package project.stockrecommendationengine.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SECTickerEntry(
        @JsonProperty("cik_str")
        Long cik,
        String ticker,
        String title
) {
}
