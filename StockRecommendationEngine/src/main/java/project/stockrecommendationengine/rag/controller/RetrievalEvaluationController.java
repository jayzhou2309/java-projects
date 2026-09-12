package project.stockrecommendationengine.rag.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

/** Run and read retrieval evaluation snapshots; protected by the integration access token. */
@RestController
@RequestMapping("/api/rag/evaluate")
@RequiredArgsConstructor
public class RetrievalEvaluationController {
    private final RetrievalEvaluationService service;
    private final RetrievalEvaluationRepository repository;

    /**
     * Run the bundled set through retrieval now and store the snapshot. The optional {@code hybrid} parameter forces
     * the keyword plus vector path on or off for every question; absent, each request follows
     * {@code rag.retrieval.hybrid-enabled}.
     */
    @PostMapping
    public RetrievalEvaluation evaluate(@RequestParam(required = false) Boolean hybrid) { return service.evaluate(hybrid); }

    /** The newest stored snapshot; 404 until one has been run. */
    @GetMapping
    public RetrievalEvaluation latest() {
        return repository.latest().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No retrieval evaluation stored yet"));
    }

    @GetMapping("/{id}")
    public RetrievalEvaluation byId(@PathVariable long id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown retrieval evaluation"));
    }
}
