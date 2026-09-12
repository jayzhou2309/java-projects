package project.stockrecommendationengine.rag.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.stockrecommendationengine.rag.dto.IngestionRequest;
import project.stockrecommendationengine.rag.freshness.FilingFreshness;
import project.stockrecommendationengine.rag.freshness.FilingFreshnessService;
import project.stockrecommendationengine.rag.freshness.FilingRefreshResult;
import project.stockrecommendationengine.rag.ingestion.FilingIngestionService;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/rag")
public class FilingIngestionController {
    private final FilingIngestionService filingIngestionService;
    private final FilingFreshnessService filingFreshnessService;

    /** Compare the SEC index with the store for one ticker and ingest what is new. */
    @PostMapping("/refresh")
    public FilingRefreshResult refresh(@org.springframework.web.bind.annotation.RequestParam String ticker) {
        return filingFreshnessService.refresh(ticker);
    }

    /** What is stored for a ticker and whether it is past cadence without a recent verification. */
    @org.springframework.web.bind.annotation.GetMapping("/freshness")
    public FilingFreshness freshness(@org.springframework.web.bind.annotation.RequestParam String ticker) {
        return filingFreshnessService.assess(ticker);
    }

    @PostMapping("/filings/{filingId}/rebuild")
    public java.util.Map<String, Object> rebuild(
            @org.springframework.web.bind.annotation.PathVariable long filingId) {
        return filingIngestionService.rebuild(filingId);
    }

    @PostMapping("/ingest")
    public ResponseEntity<Void> ingest (
            @Valid @RequestBody IngestionRequest request
            ) {
        long started = System.nanoTime();
        log.info("Ingestion request received: ticker={}, filingTypes={}, limit={}",
                request.ticker(), request.filingTypes(), request.limit());
        try {
            filingIngestionService.ingest(request.ticker(), request.filingTypes(), request.limit());
            log.info("Ingestion request completed: ticker={}, elapsedMs={}",
                    request.ticker(), (System.nanoTime() - started) / 1_000_000);
            return ResponseEntity.ok().build();
        } catch (RuntimeException failure) {
            log.error("Ingestion request failed: ticker={}, elapsedMs={}",
                    request.ticker(), (System.nanoTime() - started) / 1_000_000, failure);
            throw failure;
        }
    }
}
