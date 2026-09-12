package project.stockrecommendationengine.outcome;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Read and trigger outcome evaluation; protected by the integration access token. */
@RestController
@RequestMapping("/api/outcomes")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "outcomes.enabled", havingValue = "true")
public class OutcomeController {
    private final OutcomeEvaluationService service;
    private final OutcomeRepository repository;
    private final ConfidenceCalibrationService calibration;

    @PostMapping("/evaluate")
    public OutcomeEvaluationService.EvaluationRun evaluateAll() { return service.evaluateAll(); }

    @PostMapping("/evaluate/{runId}")
    public List<OutcomeRecord> evaluate(@PathVariable String runId) {
        validate(runId);
        try { return service.evaluate(runId); }
        catch (java.util.NoSuchElementException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown run"); }
    }

    @GetMapping("/{runId}")
    public List<OutcomeRecord> byRunId(@PathVariable String runId) { validate(runId); return repository.findByRunId(runId); }

    @GetMapping("/summary")
    public List<OutcomeSummary> summary() { return repository.summary(); }

    /** Compute and store a calibration snapshot now from every scored directional run. */
    @PostMapping("/calibration")
    public ConfidenceCalibration calibrate() { return calibration.compute(); }

    /** The newest stored snapshot for the reference horizon; 404 until one has been computed. */
    @GetMapping("/calibration")
    public ConfidenceCalibration latestCalibration() {
        return calibration.latest().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No calibration computed yet"));
    }

    private static void validate(String runId) {
        if (runId == null || !runId.matches("[0-9a-fA-F-]{36}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid run ID");
    }
}
