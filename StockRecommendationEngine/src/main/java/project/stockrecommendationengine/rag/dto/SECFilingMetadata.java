package project.stockrecommendationengine.rag.dto;

import java.time.LocalDate;

public record SECFilingMetadata(
        String ticker,
        String cik,
        String accessionNo,
        String filingType,
        LocalDate filingDate,
        LocalDate reportDate,
        String primaryDocument,
        String sourceUrl
) {
}
