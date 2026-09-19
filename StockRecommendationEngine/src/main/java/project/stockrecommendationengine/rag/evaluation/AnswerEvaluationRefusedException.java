package project.stockrecommendationengine.rag.evaluation;

import org.springframework.http.HttpStatus;

/** An answer-evaluation pass refused before any recommendation run, and so before any model call; the message says why. */
public class AnswerEvaluationRefusedException extends RuntimeException {
    private final HttpStatus status;

    public AnswerEvaluationRefusedException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
