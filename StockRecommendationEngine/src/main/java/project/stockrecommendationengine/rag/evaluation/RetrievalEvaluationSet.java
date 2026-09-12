package project.stockrecommendationengine.rag.evaluation;

import java.time.LocalDate;
import java.util.List;

/** A versioned, fixed set of retrieval questions with known-good passages, loaded from the classpath. */
public record RetrievalEvaluationSet(String version, LocalDate createdOn, List<RetrievalEvaluationQuestion> questions) {
}
