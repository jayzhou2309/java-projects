package project.rag.dto;

import java.util.List;

public record SECSubmissionResponse(
        String cik,
        String name,
        Filings filings
) {
    public record Filings(RecentFiling recent){}
    public record RecentFiling(
            List<String> accessionNumber,
            List<String> filingDate,
            List<String> reportDate,
            List<String> form,
            List<String> primaryDocument
    ){}
}
