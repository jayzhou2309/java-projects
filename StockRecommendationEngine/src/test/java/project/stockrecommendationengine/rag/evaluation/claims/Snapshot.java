package project.stockrecommendationengine.rag.evaluation.claims;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * An evaluation snapshot read from a committed file in either shape (RAG.md, Claims): a {@code row_to_json} export of a
 * {@code retrieval_evaluations} row ({@code hit_at_5}, {@code set_version}, and a {@code results} object holding {@code questions},
 * {@code slices}, and {@code tickerHitAt5}) or the API JSON of {@code GET /api/rag/evaluate/{id}} ({@code hitAt5}, {@code setVersion},
 * and {@code results} as the list of questions beside {@code slices} and {@code tickerHitAt5}). Values are read as stored.
 */
record Snapshot(String file, String setVersion, Integer questionCount, JsonNode questions, JsonNode aggregate, JsonNode slices, JsonNode tickerHitAt5) {

    static Snapshot of(String file, JsonNode node) {
        JsonNode results = node.path("results");
        if (results.isArray()) {
            return new Snapshot(file, text(node.get("setVersion")), integer(node.get("questionCount")), results, node, node.path("slices"), node.path("tickerHitAt5"));
        }
        if (results.isObject() && results.path("questions").isArray()) {
            return new Snapshot(file, text(node.get("set_version")), integer(node.get("question_count")), results.get("questions"), node, results.path("slices"),
                    results.path("tickerHitAt5"));
        }
        throw new IllegalArgumentException(file + " is not an evaluation snapshot (no results list, and no results object with questions)");
    }

    /** The question's stored rank; null when it has no matching chunk in the window or errored. Throws when the question is absent. */
    Integer rank(String id) {
        for (JsonNode question : questions) {
            if (id.equals(question.path("id").asString(null))) {
                JsonNode rank = question.get("rank");
                return rank == null || rank.isNull() ? null : rank.intValue();
            }
        }
        throw new IllegalArgumentException(file + " has no question " + id);
    }

    /**
     * A metric by claim path: {@code hitAt1}, {@code hitAt3}, {@code hitAt5}, {@code mrr} (the aggregate), {@code slices.<slice>.<metric>},
     * or {@code tickerHitAt5.<TICKER>}; null when the snapshot does not record it.
     */
    BigDecimal metric(String path) {
        JsonNode value;
        if (path.startsWith("slices.")) {
            String[] parts = path.split("\\.", 3);
            value = slices.path(parts[1]).get(parts[2]);
        } else if (path.startsWith("tickerHitAt5.")) {
            value = tickerHitAt5.get(path.substring("tickerHitAt5.".length()));
        } else {
            value = aggregate.has(path) ? aggregate.get(path) : aggregate.get(switch (path) {
                case "hitAt1" -> "hit_at_1";
                case "hitAt3" -> "hit_at_3";
                case "hitAt5" -> "hit_at_5";
                default -> path;
            });
        }
        return value == null || !value.isNumber() ? null : value.decimalValue();
    }

    /** The tickers the snapshot records a hit@5 for, in stored order. */
    List<String> tickers() {
        return new ArrayList<>(tickerHitAt5.propertyNames());
    }

    /** Ids of questions of kind FIGURE ranked 1 to 5, in stored order. */
    List<String> figureKindInTop5() {
        List<String> ids = new ArrayList<>();
        for (JsonNode question : questions) {
            JsonNode rank = question.get("rank");
            if ("FIGURE".equals(question.path("kind").asString(null)) && rank != null && rank.isIntegralNumber() && rank.intValue() <= 5) {
                ids.add(question.path("id").asString());
            }
        }
        return ids;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static Integer integer(JsonNode node) {
        return node == null || !node.isIntegralNumber() ? null : node.intValue();
    }
}
