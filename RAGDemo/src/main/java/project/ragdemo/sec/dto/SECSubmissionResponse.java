package project.ragdemo.sec.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SECSubmissionResponse {
    private String name;
    private String cik;
    private Filings filings;
}
