package project.stockrecommendationengine.rag.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import project.stockrecommendationengine.rag.retrieval.RerankerUnavailableException;

/** Maps a per-call rerank request with no reranker bean to 400 with the reason in the problem detail. */
@RestControllerAdvice(assignableTypes = {FilingRetrievalController.class, RetrievalEvaluationController.class})
public class RetrievalExceptionHandler {
    @ExceptionHandler(RerankerUnavailableException.class)
    public ProblemDetail rerankerUnavailable(RerankerUnavailableException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
}
