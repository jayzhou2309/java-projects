package project.stockrecommendationengine.rag.dto;

import java.time.LocalDate;

public record RetrievedFilingChunk(
        Long chunkId,
        Long filingId,
        String ticker,
        String cik,
        String accessionNo,
        String filingType,
        LocalDate filingDate,
        LocalDate reportDate,
        String sectionKey,
        String sectionTitle,
        Integer chunkIndex,
        String content,
        String sourceUrl,
        double similarityScore
) {
}
