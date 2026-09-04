package project.ragdemo.sec;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;


@Service
@RequiredArgsConstructor
public class SECClient {

    private final RestClient restClient;
    @Value("${app.sec.user-agent}")
    private String userAgent;

    public String getSubmissions(String cik){
        String endpoint = "submissions/CIK" + cik + ".json";
        return restClient.get()
                .uri(endpoint)
                .headers(headers -> headers.addAll(createHeaders()))
                .retrieve()
                .body(String.class);
    }

    public String getFilingDocument(String cik, String accessionNo, String primaryDocument){
        return null;
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", userAgent);
        return headers;
    }
}
