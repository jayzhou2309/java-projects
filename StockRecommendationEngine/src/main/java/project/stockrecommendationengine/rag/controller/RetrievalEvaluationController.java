package project.stockrecommendationengine.rag.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationRepository;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationService;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceService;

/** Run and read retrieval evaluation snapshots; protected by the integration access token. */
@RestController
@RequestMapping("/api/rag/evaluate")
@RequiredArgsConstructor
public class RetrievalEvaluationController {
    private final RetrievalEvaluationService service;
    private final RetrievalEvaluationRepository repository;
    private final RetrievalEvidenceService evidence;

    /** The markdown rendering's content type. */
    static final MediaType MARKDOWN = MediaType.parseMediaType("text/markdown;charset=UTF-8");

    /**
     * Run the bundled set through retrieval now and store the snapshot. The optional {@code hybrid} parameter forces
     * the keyword plus vector path on or off for every question; absent, each request follows
     * {@code rag.retrieval.hybrid-enabled}. The optional {@code rerank} parameter does the same for reranking against
     * {@code rag.retrieval.reranking-enabled}; {@code rerank=true} with no reranker configured is a 400 before any question runs.
     * The optional {@code trace} parameter, when true, stores each question's retrieval trace in the snapshot ({@code traces},
     * {@code properties.trace} true); absent or false stores none. Tracing never changes a question's results.
     */
    @PostMapping
    public RetrievalEvaluation evaluate(@RequestParam(required = false) Boolean hybrid, @RequestParam(required = false) Boolean rerank,
            @RequestParam(required = false) Boolean trace) {
        return service.evaluate(hybrid, rerank, trace);
    }

    /** The newest stored snapshot; 404 until one has been run. */
    @GetMapping
    public RetrievalEvaluation latest() {
        return repository.latest().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No retrieval evaluation stored yet"));
    }

    @GetMapping("/{id}")
    public RetrievalEvaluation byId(@PathVariable long id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown retrieval evaluation"));
    }

    /**
     * The per-question evidence report of a stored snapshot (RAG.md, Retrieval Evaluation, Evidence report): JSON by default or with
     * {@code format=json}; {@code format=markdown} renders the same JSON as tables ({@code text/markdown}). Reads the snapshot, the bundled
     * set, and stored chunks; calls no model. 404 for an unknown snapshot, 400 for any other format.
     */
    @GetMapping("/{id}/evidence")
    public ResponseEntity<?> evidence(@PathVariable long id, @RequestParam(required = false) String format) {
        if (format != null && !format.equals("json") && !format.equals("markdown")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "format must be json or markdown");
        }
        RetrievalEvidenceReport report = evidence.report(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown retrieval evaluation"));
        if ("markdown".equals(format)) return ResponseEntity.ok().contentType(MARKDOWN).body(evidence.markdown(report));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(report);
    }
}
