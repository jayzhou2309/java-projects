package project.stockrecommendationengine.rag.evaluation.claims;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The committed output of {@code CrossEncoderAnswerVisibilityLiveTests} (RAG.md, Retrieval Evaluation, Chunk size diagnostic) read as text:
 * every line starting {@code ANSWER_VISIBILITY } is one measurement, its second word the experiment ({@code setup}, {@code visibility},
 * {@code truncation}, {@code rank}, {@code overlap}, {@code chunkSize}) and the rest {@code key=value} fields separated by spaces, where a
 * value may be a bracketed list, a braced group, or a quoted string holding spaces (the phrase, the holders). A bare word such as
 * {@code TOTALS} is kept as a field whose value is {@code true}. A {@code holders=[piece70{...}, piece71{...}]} field is also read as one
 * field map per piece, its name under {@code piece}. Lines not starting with the prefix are ignored, so a raw console log reads the same as
 * the extracted lines. Nothing here is a snapshot or an evidence report: the checks reading it (RAG.md, Claims, Check types) compare values
 * as printed and count lines; they compute no score.
 */
final class DiagnosticOutput {
    static final String PREFIX = "ANSWER_VISIBILITY ";

    /** One printed line: its 1-based line number in the file, its experiment, its fields as printed, and its holders (chunkSize lines). */
    record Line(int number, String experiment, Map<String, String> fields, List<Map<String, String>> holders) {
        String field(String name) {
            return fields.get(name);
        }

        /** Whether the line names a question (a measurement line; the {@code setup} and {@code TOTALS} lines do not). */
        boolean measurement() {
            return fields.containsKey("question");
        }

        /**
         * Whether the line concerns {@code chunk}: its {@code chunk} or {@code storedChunk} equals it, or its {@code chunksHoldingPhrase}
         * list (rank lines) holds it.
         */
        boolean concerns(long chunk) {
            String id = Long.toString(chunk);
            if (id.equals(fields.get("chunk")) || id.equals(fields.get("storedChunk"))) return true;
            String holding = fields.get("chunksHoldingPhrase");
            return holding != null && list(holding).contains(id);
        }
    }

    private final List<Line> lines;

    private DiagnosticOutput(List<Line> lines) {
        this.lines = lines;
    }

    static DiagnosticOutput parse(String text) {
        List<Line> parsed = new ArrayList<>();
        String[] raw = text.split("\n", -1);
        for (int i = 0; i < raw.length; i++) {
            String line = raw[i].endsWith("\r") ? raw[i].substring(0, raw[i].length() - 1) : raw[i];
            if (!line.startsWith(PREFIX)) continue;
            List<String> tokens = tokens(line.substring(PREFIX.length()));
            if (tokens.isEmpty()) continue;
            Map<String, String> fields = fields(tokens.subList(1, tokens.size()));
            List<Map<String, String>> holders = new ArrayList<>();
            String holdersValue = fields.get("holders");
            if (holdersValue != null) {
                for (String group : groups(holdersValue)) {
                    int brace = group.indexOf('{');
                    Map<String, String> holder = new LinkedHashMap<>();
                    holder.put("piece", brace < 0 ? group : group.substring(0, brace));
                    if (brace >= 0 && group.endsWith("}")) holder.putAll(fields(tokens(group.substring(brace + 1, group.length() - 1))));
                    holders.add(holder);
                }
            }
            parsed.add(new Line(i + 1, tokens.get(0), fields, List.copyOf(holders)));
        }
        return new DiagnosticOutput(List.copyOf(parsed));
    }

    /** Every parsed line, in file order. */
    List<Line> lines() {
        return lines;
    }

    /** The lines of one experiment, in file order. */
    List<Line> lines(String experiment) {
        return lines.stream().filter(line -> line.experiment().equals(experiment)).toList();
    }

    /**
     * The lines of the experiment matching every given selector (null means any): the question id, the chunk it concerns, and its
     * {@code sizeChars}.
     */
    List<Line> select(String experiment, String question, Long chunk, Integer sizeChars) {
        return lines(experiment).stream()
                .filter(line -> question == null || question.equals(line.field("question")))
                .filter(line -> chunk == null || line.concerns(chunk))
                .filter(line -> sizeChars == null || Integer.toString(sizeChars).equals(line.field("sizeChars")))
                .toList();
    }

    /** The items of a printed list {@code [a, b, c]}, trimmed; an empty list has no items. */
    static List<String> list(String value) {
        String inner = value.startsWith("[") && value.endsWith("]") ? value.substring(1, value.length() - 1) : value;
        List<String> items = new ArrayList<>();
        for (String group : groups(inner)) {
            if (!group.isBlank()) items.add(group.strip());
        }
        return items;
    }

    /** {@code key=value} tokens as a map in order; a bare token maps to {@code true}. The key ends at the first {@code =} outside parentheses. */
    private static Map<String, String> fields(List<String> tokens) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String token : tokens) {
            int depth = 0;
            int split = -1;
            for (int i = 0; i < token.length() && split < 0; i++) {
                char c = token.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (c == '=' && depth == 0) split = i;
            }
            if (split < 0) fields.putIfAbsent(token, "true");
            else fields.putIfAbsent(token.substring(0, split), token.substring(split + 1));
        }
        return fields;
    }

    /** The text split on spaces outside brackets, braces, parentheses, and double quotes. */
    static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (!quoted && (c == '[' || c == '{' || c == '(')) depth++;
            else if (!quoted && (c == ']' || c == '}' || c == ')')) depth--;
            if (c == ' ' && depth <= 0 && !quoted) {
                if (!current.isEmpty()) tokens.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) tokens.add(current.toString());
        return tokens;
    }

    /** The text split on commas outside brackets, braces, parentheses, and double quotes (the items of a list or the holder groups). */
    private static List<String> groups(String text) {
        List<String> groups = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        String inner = text.startsWith("[") && text.endsWith("]") ? text.substring(1, text.length() - 1) : text;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (!quoted && (c == '[' || c == '{' || c == '(')) depth++;
            else if (!quoted && (c == ']' || c == '}' || c == ')')) depth--;
            if (c == ',' && depth <= 0 && !quoted) {
                groups.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) groups.add(current.toString().strip());
        return groups;
    }
}
