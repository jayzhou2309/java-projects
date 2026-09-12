package project.stockrecommendationengine.rag.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record RetrievalRequest(
        @NotBlank @Size(max = 16) String ticker,
        @NotBlank @Size(max = 4000) String query,
        @Size(min = 1, max = 10) List<@NotBlank @Size(max = 10) String> filingTypes,
        LocalDate filingDateFrom,
        LocalDate filingDateTo,
        @Size(min = 1, max = 20) List<@NotBlank @Size(max = 64) String> sectionKeys,
        @Min(1) @Max(20) Integer topK,
        Boolean latestFilingsOnly,
        Boolean hybrid
) {
    @AssertTrue(message = "filingDateFrom must be on or before filingDateTo")
    public boolean isFilingDateRangeValid() {
        return filingDateFrom == null || filingDateTo == null || !filingDateFrom.isAfter(filingDateTo);
    }
}
