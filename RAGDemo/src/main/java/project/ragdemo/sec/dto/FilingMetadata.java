package project.ragdemo.sec.dto;

public record FilingMetadata(
        String form,
        String accessionNo,
        String primaryDocument,
        String filingDate
) {

}