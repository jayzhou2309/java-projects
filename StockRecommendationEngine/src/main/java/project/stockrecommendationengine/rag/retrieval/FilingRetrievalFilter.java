package project.stockrecommendationengine.rag.retrieval;

import java.time.LocalDate;
import java.util.List;

public record FilingRetrievalFilter(
        String ticker,
        List<String> filingTypes,
        LocalDate filingDateFrom,
        LocalDate filingDateTo,
        List<String> sectionKeys,
        boolean latestFilingsOnly
) {
}
