package project.stockrecommendationengine.rag.evaluation.claims;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * A field path into an evidence report for the {@code notRecorded} check (RAG.md, Claims): segments separated by dots, each a field name
 * optionally followed by one selector, {@code [n]} for the n-th element (0-based) or {@code [key=value]} for the first element whose
 * {@code key} reads as {@code value}; for example {@code questions[id=msft-05].phrases[0].chunks[chunkId=515].rerankInput}. A dot inside a
 * selector does not split; a value cannot contain {@code ]}.
 */
final class ReportPath {
    private static final Pattern SEGMENT = Pattern.compile("([A-Za-z0-9_]+)(?:\\[([^\\]]+)\\])?");

    private ReportPath() {
    }

    /** The node the path names, or null when any segment names nothing; a malformed path throws IllegalArgumentException. */
    static JsonNode resolve(JsonNode root, String path) {
        JsonNode node = root;
        for (String segment : split(path)) {
            Matcher matcher = SEGMENT.matcher(segment);
            if (!matcher.matches()) throw new IllegalArgumentException("malformed path segment \"" + segment + "\"");
            node = node.get(matcher.group(1));
            if (node == null) return null;
            String selector = matcher.group(2);
            if (selector == null) continue;
            if (!node.isArray()) return null;
            if (selector.matches("\\d+")) {
                int index = Integer.parseInt(selector);
                node = index < node.size() ? node.get(index) : null;
            } else {
                int equals = selector.indexOf('=');
                if (equals <= 0) throw new IllegalArgumentException("malformed selector [" + selector + "]");
                String key = selector.substring(0, equals);
                String value = selector.substring(equals + 1);
                JsonNode match = null;
                for (JsonNode element : node) {
                    JsonNode field = element.get(key);
                    if (field != null && !field.isNull() && value.equals(field.isValueNode() ? field.asString() : field.toString())) {
                        match = element;
                        break;
                    }
                }
                node = match;
            }
            if (node == null) return null;
        }
        return node;
    }

    static List<String> split(String path) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (char c : path.toCharArray()) {
            if (c == '[') depth++;
            if (c == ']') depth--;
            if (c == '.' && depth == 0) {
                segments.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        segments.add(current.toString());
        return segments;
    }
}
