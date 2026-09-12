package project.stockrecommendationengine.rag.evaluation;

import java.util.List;

/** An analyst-style question for one ticker; any one of the expected passages satisfies it. */
public record RetrievalEvaluationQuestion(String id, String ticker, Kind kind, String question,
                                          List<ExpectedPassage> expected, String notes) {

    /** FIGURE questions target an exact number stated in the filing; NARRATIVE questions target a risk, change, or policy. */
    public enum Kind { FIGURE, NARRATIVE }
}
