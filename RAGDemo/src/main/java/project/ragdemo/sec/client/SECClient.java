package project.ragdemo.sec.client;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import project.ragdemo.sec.FilingRepository;
import project.ragdemo.sec.dto.FilingMetadata;
import project.ragdemo.sec.dto.Recent;
import project.ragdemo.sec.dto.SECSubmissionResponse;

import java.util.ArrayList;
import java.util.List;


@Service
public class SECClient {

    private final RestClient restClient;
    private final RestClient secArchiveRestClient;
    private final SECProperties secProperties;

    public SECClient(@Qualifier("secRestClient") RestClient restClient,
    @Qualifier("secArchiveRestClient") RestClient secArchiveRestClient, SECProperties secProperties){
        this.restClient = restClient;
        this.secArchiveRestClient = secArchiveRestClient;
        this.secProperties = secProperties;
    }


    @Value("${app.sec.user-agent}")
    private String userAgent;

    public SECSubmissionResponse getSubmissions(String cik){
        String endpoint = "/submissions/CIK" + cik + ".json";
        return restClient.get()
                .uri(endpoint)
                .headers(headers -> headers.addAll(createHeaders()))
                .retrieve()
                .body(SECSubmissionResponse.class);
    }

    public FilingMetadata getLatestFiling(String cik, String filingType){
        SECSubmissionResponse response = getSubmissions(cik);

        Recent recent = response.getFilings().getRecent();

        for (int i = 0; i < recent.getForm().size(); i++){
            String form = recent.getForm().get(i);

            if (filingType.equals(form)){
                String accessionNo = recent.getAccessionNumber().get(i);
                String primaryDocument = recent.getPrimaryDocument().get(i);
                String filingDate = recent.getFilingDate().get(i);
                return new FilingMetadata(form, accessionNo, primaryDocument, filingDate);
            }

        }
        return null;
    }

    public List<FilingMetadata> getRecentFiling(String cik, String filingType){
        SECSubmissionResponse response = getSubmissions(cik);
        Recent recent = response.getFilings().getRecent();

        List<FilingMetadata> filings = new ArrayList<>();

        for (int i = 0; i < recent.getForm().size(); i++){
             if (filingType.equals(recent.getForm().get(i))){
                filings.add(
                        new FilingMetadata(
                                recent.getForm().get(i),
                                recent.getAccessionNumber().get(i),
                                recent.getPrimaryDocument().get(i),
                                recent.getFilingDate().get(i)
                        )
                );
             }

            if (filings.size() == secProperties.getFilingsPerType()){
                break;
            }
        }
        return filings;
    }

    public List<FilingMetadata> getAllRecentFilings (String cik){
        List<FilingMetadata> allFilings = new ArrayList<>();
        for (String filingType : secProperties.getFilingTypes()){
            List<FilingMetadata> filings =
                    getRecentFiling(cik, filingType);
            allFilings.addAll(filings);
        }

        return allFilings;
    }

    public String getFilingDocument(
            String cik, String accessionNo, String primaryDocument
    ){
        String endpoint = buildArchiveEndpoint(cik, accessionNo, primaryDocument);
        return secArchiveRestClient.get()
                .uri(endpoint)
                .headers(headers -> headers.addAll(createHeaders()))
                .retrieve()
                .body(String.class);
    }

    private String buildArchiveEndpoint(
            String cik,
            String accessionNo,
            String primaryDocument
    ) {
        String cikWithoutZeros =
                cik.replaceFirst("^0+(?!$)", "");
        String accessionWithoutDashes =
                accessionNo.replace("-", "");
        return "/Archives/edgar/data/"
                + cikWithoutZeros
                + "/"
                + accessionWithoutDashes
                + "/"
                + primaryDocument;
    }

    public String buildSourceURL(String cik, String accessionNo, String primaryDocument){
        return secProperties.getArchiveBaseUrl() + buildArchiveEndpoint(cik, accessionNo, primaryDocument);
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", userAgent);
        return headers;
    }
}
