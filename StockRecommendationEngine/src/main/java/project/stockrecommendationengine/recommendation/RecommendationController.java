package project.stockrecommendationengine.recommendation;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "recommendation.enabled", havingValue = "true")
public class RecommendationController {
    private final RecommendationService service;
    private final RecommendationRepository store;

    @PostMapping public RecommendationResponse recommend(@Valid @RequestBody RecommendationRequest request) {
        return service.recommend(request);
    }

    /** Stored audit record for one run, including runs that stopped on a limit or failed. */
    @GetMapping("/{runId}") public RecommendationRecord byRunId(@PathVariable String runId) {
        if (runId == null || !runId.matches("[0-9a-fA-F-]{36}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid run ID");
        return store.findByRunId(runId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown run"));
    }

    /** Newest stored runs for a ticker; the agent's own track record. */
    @GetMapping public List<RecommendationRecord> byTicker(@RequestParam String ticker,
            @RequestParam(defaultValue = "20") int limit) {
        if (ticker == null || !ticker.matches("[A-Za-z0-9.-]{1,16}") || limit < 1 || limit > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ticker or limit");
        }
        return store.findByTicker(ticker.toUpperCase(java.util.Locale.ROOT), limit);
    }
}
