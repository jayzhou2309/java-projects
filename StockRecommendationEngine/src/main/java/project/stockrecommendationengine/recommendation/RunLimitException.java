package project.stockrecommendationengine.recommendation;

class RunLimitException extends RuntimeException {
    RunLimitException(String code) { super(code); }
}
