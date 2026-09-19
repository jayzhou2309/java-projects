package project.stockrecommendationengine.recommendation;

/**
 * Why a run was made. USER runs are product data: they are scored, listed per ticker, shown to the manager as the
 * track record, and used for calibration. EVALUATION runs measure the loop itself and are excluded from all of those.
 * The public request cannot choose this; only {@link RecommendationService#recommend(RecommendationRequest, RunPurpose)} can.
 */
public enum RunPurpose { USER, EVALUATION }
