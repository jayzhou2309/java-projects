package project.stockrecommendationengine.rag.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Renders an evidence report's JSON document ({@link RetrievalEvidenceReport} as serialised) as markdown tables. The input is the JSON
 * alone: every cell is a value read from it with its basis, and nothing is looked up, computed, or added beyond layout. A value is
 * written {@code 10 (observed [3])} or {@code 480 (derived [7])}, citing the numbered source or rule listed at the end, or
 * {@code unknown: <reason>}; a null value is {@code none} ({@code not in the fused list} for a fused position, {@code not returned}
 * for a returned position, {@code not removed} for a removedRedundantWith); offsets are {@code [start, end)}. Entries of a removed list are
 * plain values under the list's basis.
 */
final class EvidenceMarkdownRenderer {
    private static final String[] SETTINGS = {"rerank", "rerankCandidates", "reranker", "rerankerVersion", "loadedModelVersion", "rerankerScoring",
            "passageScoring", "windowOverlapTokens", "maxWindows", "maxLength", "chunkMaxChars", "chunkOverlapChars", "storeVersions"};
    private static final String[] QUESTION_FIELDS = {"rank", "matchedChunkId", "retrievalStrategy", "error", "rerankOutcome", "fallbackReason",
            "scoresNotRecorded", "fusedCount", "rerankInputCount", "queryTokens", "acceptedPhraseCount", "acceptedChunkRemoved"};
    private static final String[] TOKEN_COLUMNS = {"chunkTokens", "windowLength", "windowStarts"};
    private static final String[] OCCURRENCE_COLUMNS = {"characterSpan", "tokenSpan", "head", "windowsHoldingWholly"};
    private static final String[] CANDIDATE_COLUMNS = {"fusedPosition", "rerankInput", "rerankedPosition", "score", "windowCount", "windowScores",
            "returnedPosition", "removedRedundantWith"};
    private static final String[] RANKED_COLUMNS = {"position", "fusedPosition", "score", "windowScores"};
    private static final String[] REMOVED_COLUMNS = {"candidatePosition", "vectorRank", "keywordRank", "figureRank", "redundantWith"};

    private final StringBuilder out = new StringBuilder();
    private final Map<String, Integer> citations = new LinkedHashMap<>();

    private EvidenceMarkdownRenderer() {
    }

    static String render(JsonNode report) {
        return new EvidenceMarkdownRenderer().document(report);
    }

    private String document(JsonNode report) {
        line("# Retrieval evidence report: snapshot " + text(report.get("snapshotId")));
        line("");
        line("Each value is followed by its basis: observed (read from the numbered source), derived (computed by the numbered rule), or"
                + " unknown with the reason. Sources and rules are listed at the end. Offsets are [start, end), end exclusive; window numbers"
                + " start at 1. windowStarts and windowsHoldingWholly are arithmetic on the snapshot's recorded scoring: they are rows that"
                + " were scored only for a chunk that is a rerank input of a RERANKED trace (rerankInput observed true); for reranking off, a"
                + " fallback, a question without a trace, or a chunk outside the rerank input they are the rows the recorded scoring would"
                + " score, not rows that were scored. W uses max-length from the current configuration, which snapshots do not record.");
        line("");
        line("## Snapshot");
        line("");
        line("| Field | Value |");
        line("|---|---|");
        for (String field : new String[] {"setVersion", "set", "traced"}) row(field, cell(field, report.get(field)));
        JsonNode settings = report.path("settings");
        for (String field : SETTINGS) row(field, cell(field, settings.get(field)));
        for (JsonNode question : iterable(report.get("questions"))) question(question);
        line("");
        line("## Sources and rules");
        line("");
        citations.forEach((citation, number) -> line(number + ". " + escape(citation)));
        return out.toString();
    }

    private void question(JsonNode question) {
        line("");
        line("## Question " + text(question.get("id")) + " (" + text(question.get("ticker")) + ", " + text(question.get("kind")) + ")");
        line("");
        line("Question: " + escape(text(question.get("question"))));
        line("");
        line("| Field | Value |");
        line("|---|---|");
        for (String field : QUESTION_FIELDS) row(field, cell(field, question.get(field)));
        List<JsonNode> phrases = list(question.get("phrases"));
        for (int index = 0; index < phrases.size(); index++) phrase(phrases.get(index), index + 1, phrases.size());
        line("");
        line("### Ranked above the best accepted chunk");
        line("");
        line("| Field | Value |");
        line("|---|---|");
        for (String field : new String[] {"ranking", "bestAcceptedChunk", "bestAcceptedPosition"}) row(field, cell(field, question.get(field)));
        JsonNode above = question.path("rankedAbove");
        line("");
        if (!"unknown".equals(text(above.get("basis")))) {
            int count = list(above.get("value")).size();
            line("rankedAbove: " + count + (count == 1 ? " chunk " : " chunks ") + basis(above));
            if (!list(above.get("value")).isEmpty()) {
                line("");
                header("Chunk", RANKED_COLUMNS);
                for (JsonNode entry : list(above.get("value"))) {
                    List<String> cells = new ArrayList<>();
                    cells.add(text(entry.get("chunkId")));
                    for (String column : RANKED_COLUMNS) cells.add(cell(column, entry.get(column)));
                    tableRow(cells);
                }
            }
        } else {
            line("rankedAbove: " + cell("rankedAbove", above));
        }
        removed(question.path("removed"));
    }

    private void removed(JsonNode removed) {
        line("");
        line("### Removed by diversification");
        line("");
        if (removed.isMissingNode() || removed.isNull() || "unknown".equals(text(removed.get("basis")))) {
            line("removed: " + cell("removed", removed.isMissingNode() ? null : removed));
            return;
        }
        List<JsonNode> entries = list(removed.get("value"));
        line("removed: " + entries.size() + (entries.size() == 1 ? " chunk " : " chunks ") + basis(removed));
        if (entries.isEmpty()) return;
        line("");
        header("Chunk", REMOVED_COLUMNS);
        for (JsonNode entry : entries) {
            List<String> cells = new ArrayList<>();
            cells.add(text(entry.get("chunkId")));
            for (String column : REMOVED_COLUMNS) cells.add(text(entry.get(column)));
            tableRow(cells);
        }
    }

    private void phrase(JsonNode phrase, int number, int count) {
        line("");
        line("### Accepted phrase " + number + " of " + count);
        line("");
        line(escape(text(phrase.get("accessionNo"))) + " " + escape(text(phrase.get("sectionKey"))) + ": \"" + escape(text(phrase.get("phrase"))) + "\"");
        line("");
        line("heldByStoredChunk: " + cell("heldByStoredChunk", phrase.get("heldByStoredChunk")));
        List<JsonNode> chunks = list(phrase.get("chunks"));
        if (chunks.isEmpty()) return;
        line("");
        List<String> tokenHeader = new ArrayList<>(List.of(TOKEN_COLUMNS));
        tokenHeader.addAll(List.of(OCCURRENCE_COLUMNS));
        header("Chunk", tokenHeader.toArray(String[]::new));
        for (JsonNode chunk : chunks) {
            for (JsonNode occurrence : list(chunk.get("occurrences"))) {
                List<String> cells = new ArrayList<>();
                cells.add(text(chunk.get("chunkId")));
                for (String column : TOKEN_COLUMNS) cells.add(cell(column, chunk.get(column)));
                for (String column : OCCURRENCE_COLUMNS) cells.add(cell(column, occurrence.get(column)));
                tableRow(cells);
            }
        }
        line("");
        header("Chunk", CANDIDATE_COLUMNS);
        for (JsonNode chunk : chunks) {
            List<String> cells = new ArrayList<>();
            cells.add(text(chunk.get("chunkId")));
            for (String column : CANDIDATE_COLUMNS) cells.add(cell(column, chunk.get(column)));
            tableRow(cells);
        }
    }

    /** A value with its basis: {@code <value> (observed [n])}, {@code <value> (derived [n])}, or {@code unknown: <reason>}. */
    private String cell(String field, JsonNode evidence) {
        if (evidence == null || evidence.isMissingNode() || evidence.isNull()) return "absent";
        if ("unknown".equals(text(evidence.get("basis")))) return "unknown: " + escape(text(evidence.get("reason")));
        return value(field, evidence.get("value")) + " " + basis(evidence);
    }

    private String basis(JsonNode evidence) {
        String basis = text(evidence.get("basis"));
        String note = "observed".equals(basis) ? text(evidence.get("source")) : text(evidence.get("rule"));
        return "(" + basis + " [" + citations.computeIfAbsent(basis + ": " + note, key -> citations.size() + 1) + "])";
    }

    private static String value(String field, JsonNode value) {
        if (value == null || value.isNull()) {
            return switch (field) {
                case "fusedPosition" -> "not in the fused list";
                case "returnedPosition" -> "not returned";
                case "removedRedundantWith" -> "not removed";
                default -> "none";
            };
        }
        if (value.isArray()) {
            List<String> items = new ArrayList<>();
            for (JsonNode item : value) items.add(scalar(item));
            return items.isEmpty() ? "none" : String.join(", ", items);
        }
        if (value.isObject() && value.has("start") && value.has("end")) return "[" + scalar(value.get("start")) + ", " + scalar(value.get("end")) + ")";
        return escape(scalar(value));
    }

    private static String scalar(JsonNode node) {
        if (node.isNumber()) return node.decimalValue().toString();
        return text(node);
    }

    private void header(String first, String[] columns) {
        StringBuilder header = new StringBuilder("| ").append(first);
        StringBuilder rule = new StringBuilder("|---");
        for (String column : columns) {
            header.append(" | ").append(column);
            rule.append("|---");
        }
        line(header.append(" |").toString());
        line(rule.append("|").toString());
    }

    private void tableRow(List<String> cells) {
        line("| " + String.join(" | ", cells) + " |");
    }

    private void row(String field, String value) {
        line("| " + field + " | " + value + " |");
    }

    private void line(String text) {
        out.append(text).append('\n');
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        if (array != null && array.isArray()) array.forEach(items::add);
        return items;
    }

    private static Iterable<JsonNode> iterable(JsonNode array) {
        return list(array);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "none";
        return node.isValueNode() ? node.asString() : node.toString();
    }

    /** Keeps a value on one table line: pipes escaped, line breaks as spaces. */
    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("|", "\\|").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }
}
