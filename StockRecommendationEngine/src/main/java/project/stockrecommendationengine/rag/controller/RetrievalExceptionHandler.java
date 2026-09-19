package project.stockrecommendationengine.rag.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import project.stockrecommendationengine.rag.evaluation.AnswerEvaluationNotStoredException;
import project.stockrecommendationengine.rag.evaluation.AnswerEvaluationRefusedException;
import project.stockrecommendationengine.rag.retrieval.RerankerUnavailableException;

/**
 * Maps a per-call rerank request with no reranker bean to 400, a refused answer-evaluation pass to its status, and a
 * pass whose snapshot could not be stored to 500 naming the fallback file, each with the reason in the problem detail.
 */
@RestControllerAdvice(assignableTypes = {FilingRetrievalController.class, RetrievalEvaluationController.class, AnswerEvaluationController.class})
public class RetrievalExceptionHandler {
    @ExceptionHandler(RerankerUnavailableException.class)
    public ProblemDetail rerankerUnavailable(RerankerUnavailableException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(AnswerEvaluationRefusedException.class)
    public ProblemDetail answerEvaluationRefused(AnswerEvaluationRefusedException exception) {
        return ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
    }

    /** The detail is written by the runner and carries no exception text; {@code fallbackFile} is absent when no file was written. */
    @ExceptionHandler(AnswerEvaluationNotStoredException.class)
    public ProblemDetail answerEvaluationNotStored(AnswerEvaluationNotStoredException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        if (exception.fallbackFile() != null) problem.setProperty("fallbackFile", exception.fallbackFile().toString());
        return problem;
    }
}
