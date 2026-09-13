package project.stockrecommendationengine.rag.evaluation.claims;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * An evaluation snapshot read from a committed file in either shape (RAG.md, Claims): a {@code row_to_json} export of a
 * {@code retrieval_evaluations} row ({@code id}, {@code hit_at_5}, {@code set_version}, {@code window_size}, {@code properties}, and a
 * {@code results} object holding {@code questions}, {@code slices}, and {@code tickerHitAt5}) or the API JSON of
 * {@code GET /api/rag/evaluate/{id}} ({@code id}, {@code hitAt5}, {@code setVersion}, {@code window}, {@code properties}, and {@code results}
 * as the list of questions beside {@code slices} and {@code tickerHitAt5}). Values are read as stored.
 */
record Snapshot(String file, Long id, String setVersion, Integer questionCount, Integer windowSize, JsonNode questions, JsonNode aggregate,
        JsonNode slices, JsonNode tickerHitAt5, JsonNode properties, Map<String, JsonNode> columns) {

    static Snapshot of(String file, JsonNode node) {
        JsonNode results = node.path("results");
        JsonNode id = node.get("id");
        Long snapshotId = id != null && id.isIntegralNumber() ? id.longValue() : null;
        if (results.isArray()) {
            return new Snapshot(file, snapshotId, text(node.get("setVersion")), integer(node.get("questionCount")), integer(node.get("window")), results, node,
                    node.path("slices"), node.path("tickerHitAt5"), node.get("properties"),
                    columns(node.get("setVersion"), node.get("questionCount"), node.get("window")));
        }
        if (results.isObject() && results.path("questions").isArray()) {
            return new Snapshot(file, snapshotId, text(node.get("set_version")), integer(node.get("question_count")), integer(node.get("window_size")),
                    results.get("questions"), node, results.path("slices"), results.path("tickerHitAt5"), node.get("properties"),
                    columns(node.get("set_version"), node.get("question_count"), node.get("window_size")));
        }
        throw new IllegalArgumentException(file + " is not an evaluation snapshot (no results list, and no results object with questions)");
    }

    private static Map<String, JsonNode> columns(JsonNode setVersion, JsonNode questionCount, JsonNode windowSize) {
        Map<String, JsonNode> columns = new LinkedHashMap<>();
        if (setVersion != null) columns.put("setVersion", setVersion);
        if (questionCount != null) columns.put("questionCount", questionCount);
        if (windowSize != null) columns.put("windowSize", windowSize);
        return columns;
    }

    /** The stored result of a question; throws when the question is absent. */
    JsonNode question(String id) {
        for (JsonNode question : questions) {
            if (id.equals(question.path("id").asString(null))) return question;
        }
        throw new IllegalArgumentException(file + " has no question " + id);
    }

    /** The question's stored rank; null when it has no matching chunk in the window or errored. Throws when the question is absent. */
    Integer rank(String id) {
        JsonNode rank = question(id).get("rank");
        return rank == null || rank.isNull() ? null : rank.intValue();
    }

    /** The question's stored error text; null when none is recorded. */
    String error(String id) {
        JsonNode error = question(id).get("error");
        return error == null || error.isNull() ? null : error.asString();
    }

    /**
     * The properties an experiment compares (RAG.md, Claims, Experiments): {@code setVersion}, {@code questionCount}, and {@code windowSize}
     * as stored, then every key of {@code properties} except {@code trace}. Identifiers, timestamps, metrics, results, traces, and the
     * retrieval strategy are not compared. Throws when the snapshot has no properties object.
     */
    Map<String, JsonNode> recorded() {
        if (properties == null || !properties.isObject()) throw new IllegalArgumentException(file + " records no properties object");
        Map<String, JsonNode> recorded = new LinkedHashMap<>(columns);
        for (Map.Entry<String, JsonNode> property : properties.properties()) {
            if (property.getKey().equals("trace")) continue;
            if (recorded.containsKey(property.getKey())) {
                throw new IllegalArgumentException(file + " records a property named " + property.getKey() + ", which collides with the column of that name");
            }
            recorded.put(property.getKey(), property.getValue());
        }
        return recorded;
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
        return tickerHitAt5.isObject() ? new ArrayList<>(tickerHitAt5.propertyNames()) : new ArrayList<>();
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

    /** Ids of questions that record no kind, in stored order. */
    List<String> questionsWithoutKind() {
        List<String> ids = new ArrayList<>();
        for (JsonNode question : questions) {
            JsonNode kind = question.get("kind");
            if (kind == null || !kind.isString() || kind.stringValue().isBlank()) ids.add(question.path("id").asString("(no id)"));
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
