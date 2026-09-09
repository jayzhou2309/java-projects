package project.stockrecommendationengine.rag.ingestion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import project.stockrecommendationengine.rag.dto.SECFilingMetadata;
import project.stockrecommendationengine.rag.dto.SECSubmissionResponse;
import project.stockrecommendationengine.rag.dto.SECTickerEntry;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@Slf4j
public class SECClient {
    private final RestClient restClient = RestClient.create();

    @Value("${sec.user-agent}")
    private String userAgent;

    @Value("${sec.tickers-url}")
    private String tickersUrl;

    @Value("${sec.submissions-base-url}")
    private String submissionsBaseUrl;

    @Value("${sec.archives-base-url}")
    private String archivesBaseUrl;

    public String resolveCik(String ticker){
        long started = System.nanoTime();
        log.info("Fetching SEC ticker mapping: ticker={}", ticker);
        Map<String, SECTickerEntry> tickerMap = restClient.get()
                .uri(tickersUrl)
                .header("User-Agent", userAgent)
                .retrieve()
                .body(
                        new ParameterizedTypeReference<Map<String, SECTickerEntry>>() {
                        }
                );
        if (tickerMap == null) throw new IllegalStateException("SEC returned empty ticker mapping");

        String cik = tickerMap.values()
                .stream()
                .filter(entry -> entry.ticker().equalsIgnoreCase(ticker))
                .findFirst()
                .map(entry -> String.valueOf(entry.cik()))
                .orElseThrow(() -> new UnknownTickerException(ticker));
        log.info("Resolved SEC ticker: ticker={}, cik={}, elapsedMs={}",
                ticker, cik, (System.nanoTime() - started) / 1_000_000);
        return cik;
    }

    public List<SECFilingMetadata> getRecentFilings(
            String ticker, List<String> filingTypes, int limit
    ) {
        if (limit <= 0){
            throw new IllegalArgumentException("Limit must be greater than 0");
        }
        String cik = resolveCik(ticker);
        String paddedCik = String.format("%010d", Long.parseLong(cik));
        String url = submissionsBaseUrl + "/CIK" + paddedCik + ".json";

        long started = System.nanoTime();
        log.info("Fetching SEC submissions: ticker={}, cik={}", ticker, paddedCik);
        SECSubmissionResponse response =
                restClient.get()
                        .uri(url)
                        .header("User-Agent", userAgent)
                        .retrieve()
                        .body(SECSubmissionResponse.class);

        if (response == null || response.filings() == null || response.filings().recent() == null){
            throw new IllegalStateException("SEC returned no filing data for " + ticker);
        }

        log.info("SEC submissions received: ticker={}, elapsedMs={}",
                ticker, (System.nanoTime() - started) / 1_000_000);
        var recent = response.filings().recent();
        List<SECFilingMetadata> results = new ArrayList<>();
        for (int i = 0; i < recent.form().size(); i++){
            String form = recent.form().get(i);
            if (!filingTypes.contains(form)) continue;
            String accessionNo = recent.accessionNumber().get(i);
            String accessionWithoutDashes =
                    accessionNo.replace("-","");

            String primaryDocument = recent.primaryDocument().get(i);

            LocalDate filingDate = LocalDate.parse(
                    recent.filingDate().get(i)
            );
            LocalDate reportDate = null;
            String reportDataValue = recent.reportDate().get(i);

            if (reportDataValue != null &&
            !reportDataValue.isBlank()){
                reportDate = LocalDate.parse(reportDataValue);
            }

            String sourceUrl =
                    archivesBaseUrl + "/" + Long.parseLong(cik) + "/" + accessionWithoutDashes
                    + "/" + primaryDocument;

            results.add(
                    new SECFilingMetadata(
                            ticker.toUpperCase(Locale.ROOT),
                            paddedCik,
                            accessionNo,
                            form,
                            filingDate,
                            reportDate,
                            primaryDocument,
                            sourceUrl
                    )
            );

            if (results.size() >= limit) break;
        }
        return results;
    }

    public String fetchFilingHTML(String sourceUrl){
        long started = System.nanoTime();
        log.info("Fetching SEC filing document: url={}", sourceUrl);
        String html = restClient.get()
                .uri(sourceUrl)
                .header("User-Agent", userAgent)
                .retrieve()
                .body(String.class);
        log.info("SEC filing document received: characters={}, elapsedMs={}",
                html == null ? 0 : html.length(), (System.nanoTime() - started) / 1_000_000);
        return html;
    }
}
