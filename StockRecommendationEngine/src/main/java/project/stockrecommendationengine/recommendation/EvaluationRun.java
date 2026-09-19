package project.stockrecommendationengine.recommendation;

import java.util.List;
import project.stockrecommendationengine.rag.dto.RetrievedFilingChunk;

/**
 * What {@link RecommendationService#recommendForEvaluation} returns to the answer evaluation: the public response of a
 * run stored with purpose EVALUATION, and every chunk retrieved during that run with the text a model was shown for it.
 * Internal to the application: no controller returns it and nothing in it is sent to a model.
 *
 * @param evidenceCaptured false when the run ended without handing its evidence over, so {@code retrieved} is unknown
 *                         (empty), not "nothing was retrieved"
 */
public record EvaluationRun(RecommendationResponse response, boolean evidenceCaptured, List<ShownPassage> retrieved) {
    /** One retrieved chunk with its full stored content, and the content after the {@code model-passage-chars} cut. */
    public record ShownPassage(RetrievedFilingChunk chunk, String shownToModel) { }
}
