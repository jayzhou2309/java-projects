package project.ragdemo.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import project.ragdemo.sec.client.SECClient;
import project.ragdemo.sec.dto.FilingMetadata;

@RestController
@RequiredArgsConstructor
public class SECTestController {
    private final SECClient secClient;

//    @GetMapping("/test-sec")
//    public FilingMetadata testSEC() {
//        String cik = "0000320193";
//        String filingType = "10-Q";
//        return secClient.getLatestFiling(cik, filingType);
//    }
//
    @GetMapping("/test-sec-document")
    public String testSEC() {
        String cik = "0000320193";
        String filingType = "10-Q";

        FilingMetadata filing = secClient.getLatestFiling(cik, filingType);

        return secClient.getFilingDocument(cik, filing.accessionNo(), filing.primaryDocument());
    }
}
