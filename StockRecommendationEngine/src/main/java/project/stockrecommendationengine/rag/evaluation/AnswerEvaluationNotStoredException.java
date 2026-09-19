package project.stockrecommendationengine.rag.evaluation;

import java.nio.file.Path;

/**
 * A pass ended but its snapshot could not be written to {@code answer_evaluations}. The pass has spent its tokens by then,
 * so the snapshot is kept another way first: the message names the fallback file (null when that could not be written
 * either; the snapshot is then only in the log, on the line starting with the prefix the message names).
 */
public class AnswerEvaluationNotStoredException extends RuntimeException {
    private final transient Path fallbackFile;

    public AnswerEvaluationNotStoredException(String message, Path fallbackFile, Throwable cause) {
        super(message, cause);
        this.fallbackFile = fallbackFile;
    }

    public Path fallbackFile() {
        return fallbackFile;
    }
}
