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
import project.stockrecommendationengine.rag.evaluation.AnswerEvaluation;
import project.stockrecommendationengine.rag.evaluation.AnswerEvaluationRepository;
import project.stockrecommendationengine.rag.evaluation.AnswerEvaluationService;

/**
 * Run and read answer-evaluation snapshots; protected by the integration access token like the retrieval evaluation.
 * Unlike it, a pass calls the chat model (one recommendation run per question), so it answers only when
 * {@code recommendation.enabled} is true and only one pass runs at a time. Stored snapshots stay readable either way.
 */
@RestController
@RequestMapping("/api/rag/evaluate/answers")
@RequiredArgsConstructor
public class AnswerEvaluationController {
    private final AnswerEvaluationService service;
    private final AnswerEvaluationRepository repository;

    /**
     * Run a pass now and store its snapshot; the request returns when the pass ends (runs are sequential and paced, so a
     * full pass takes many minutes; the snapshot is stored even if the caller has gone). {@code questions} is an optional
     * comma-separated list of question ids, {@code limit} then keeps the first so many in set order. 503 when
     * recommendations are disabled, 400 for an unknown id or a limit below 1, 409 while another pass runs; none of these
     * starts a run or calls a model.
     */
    @PostMapping
    public AnswerEvaluation evaluate(@RequestParam(required = false) String questions, @RequestParam(required = false) Integer limit) {
        return service.evaluate(questions, limit);
    }

    /** The newest stored snapshot; 404 until one has been run. */
    @GetMapping
    public AnswerEvaluation latest() {
        return repository.latest().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No answer evaluation stored yet"));
    }

    @GetMapping("/{id}")
    public AnswerEvaluation byId(@PathVariable long id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown answer evaluation"));
    }
}
