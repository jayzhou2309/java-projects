package project.stockrecommendationengine.rag.evaluation.claims;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import project.stockrecommendationengine.rag.evaluation.claims.ClaimsCheck.Claim;
import project.stockrecommendationengine.rag.evaluation.claims.ClaimsCheck.Parsed;

/**
 * Generated documentation blocks (RAG.md, Claims, Generated blocks). A block is the lines between a start marker and its end marker, each
 * alone on its line: {@code <!-- generated:<claims path> start -->} and {@code <!-- generated:<claims path> end -->}, where the claims path
 * is relative to the documentation file's directory and may end in {@code #<block>} to select the claims whose {@code block} equals it
 * (without it, every claim of the file). The generator writes one bullet per selected claim in file order, at the start marker's
 * indentation: {@code * <text> (<id>, <basis>)}.
 * <p>
 * {@link #check} reports, with file and line, every line of a block that differs from what {@link #write} would write, an unbalanced or nested
 * marker, a claims file that cannot be read, a selection matching no claim, and every citation — an opening parenthesis followed by
 * {@code C-} and digits, anywhere in the file — whose id no claims file referenced by the file's markers defines, or whose stated basis
 * differs from the claim's.
 */
final class GeneratedBlocks {
    static final Pattern MARKER = Pattern.compile("^([ \\t]*)<!-- generated:([^\\s#]+)(?:#([A-Za-z0-9_-]+))? (start|end) -->[ \\t]*$");
    static final Pattern CITATION = Pattern.compile("\\((C-\\d+)(?:, ([a-z]+)\\))?");

    /** One balanced block: its marker lines (1-based), the start marker's indentation, the claims path, and the block name or null. */
    record Block(int start, int end, String indent, String path, String name) {
        String label() {
            return "generated:" + path + (name == null ? "" : "#" + name);
        }
    }

    private GeneratedBlocks() {
    }

    /** Every problem of the documentation file; empty when its blocks are as generated and its citations resolve. */
    static List<String> check(Path document) {
        List<String> lines = lines(document);
        String name = ClaimsCheck.display(document);
        List<String> problems = new ArrayList<>();
        List<Block> blocks = scan(lines, name, problems);
        Map<String, Parsed> claims = new LinkedHashMap<>();
        for (Block block : blocks) {
            Parsed parsed = claims.computeIfAbsent(block.path(), path -> parse(document, path));
            List<String> expected = render(block, parsed, name, problems);
            if (expected == null) continue;
            List<String> found = lines.subList(block.start(), block.end() - 1);
            for (Difference difference : differences(expected, found)) {
                problems.add(name + ":" + (block.start() + 1 + difference.foundIndex()) + " block " + block.label() + " (lines " + block.start() + " to "
                        + block.end() + ") differs from the generator's output: " + difference.text());
            }
        }
        citations(lines, name, blocks, claims, problems);
        return problems;
    }

    /** One differing line of a block: the index of the found line it concerns (the line after the gap for a missing line) and its text. */
    record Difference(int foundIndex, String text) {
    }

    /**
     * Every difference between the generator's lines and a block's lines, aligned on their longest common subsequence so that one edited,
     * removed, or added line is reported once and an edit further down is not hidden by an earlier one: a changed line gives
     * {@code expected "..", found ".."}, a missing line {@code expected "..", found no line}, an extra line {@code expected no line, found ".."}.
     */
    static List<Difference> differences(List<String> expected, List<String> found) {
        int[][] common = new int[expected.size() + 1][found.size() + 1];
        for (int i = expected.size() - 1; i >= 0; i--) {
            for (int j = found.size() - 1; j >= 0; j--) {
                common[i][j] = expected.get(i).equals(found.get(j)) ? common[i + 1][j + 1] + 1 : Math.max(common[i + 1][j], common[i][j + 1]);
            }
        }
        List<Difference> differences = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < expected.size() || j < found.size()) {
            if (i < expected.size() && j < found.size() && expected.get(i).equals(found.get(j))) {
                i++;
                j++;
                continue;
            }
            List<String> missing = new ArrayList<>();
            List<Integer> extra = new ArrayList<>();
            while ((i < expected.size() || j < found.size()) && !(i < expected.size() && j < found.size() && expected.get(i).equals(found.get(j)))) {
                if (j >= found.size() || (i < expected.size() && common[i + 1][j] >= common[i][j + 1])) missing.add(expected.get(i++));
                else extra.add(j++);
            }
            int paired = Math.min(missing.size(), extra.size());
            for (int k = 0; k < paired; k++) {
                differences.add(new Difference(extra.get(k), "expected \"" + missing.get(k) + "\", found \"" + found.get(extra.get(k)) + "\""));
            }
            for (int k = paired; k < missing.size(); k++) differences.add(new Difference(j, "expected \"" + missing.get(k) + "\", found no line"));
            for (int k = paired; k < extra.size(); k++) differences.add(new Difference(extra.get(k), "expected no line, found \"" + found.get(extra.get(k)) + "\""));
        }
        return differences;
    }

    /** Rewrites every block of the file from its claims; true when the file changed. Throws when a marker or claims file problem prevents it. */
    static boolean write(Path document) {
        List<String> lines = lines(document);
        String name = ClaimsCheck.display(document);
        List<String> problems = new ArrayList<>();
        List<Block> blocks = scan(lines, name, problems);
        List<String> output = new ArrayList<>();
        int next = 0;
        Map<String, Parsed> claims = new HashMap<>();
        for (Block block : blocks) {
            List<String> rendered = render(block, claims.computeIfAbsent(block.path(), path -> parse(document, path)), name, problems);
            output.addAll(lines.subList(next, block.start()));
            if (rendered != null) output.addAll(rendered);
            next = block.end() - 1;
        }
        if (!problems.isEmpty()) throw new IllegalStateException("not generating " + name + ":\n" + String.join("\n", problems));
        output.addAll(lines.subList(next, lines.size()));
        if (output.equals(lines)) return false;
        try {
            Files.writeString(document, String.join("\n", output));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return true;
    }

    /** Whether the file has any marker line (balanced or not). */
    static boolean hasMarkers(Path document) {
        return lines(document).stream().anyMatch(line -> MARKER.matcher(line).matches());
    }

    /** The balanced blocks in order; marker problems are added with file and line. */
    static List<Block> scan(List<String> lines, String name, List<String> problems) {
        List<Block> blocks = new ArrayList<>();
        Matcher open = null;
        int openLine = 0;
        for (int index = 0; index < lines.size(); index++) {
            Matcher marker = MARKER.matcher(lines.get(index));
            if (!marker.matches()) continue;
            int line = index + 1;
            String label = "generated:" + marker.group(2) + (marker.group(3) == null ? "" : "#" + marker.group(3));
            if (marker.group(4).equals("start")) {
                if (open != null) {
                    problems.add(name + ":" + line + " start marker " + label + " inside the block opened at line " + openLine + " (expected its end marker first)");
                    continue;
                }
                open = marker;
                openLine = line;
            } else if (open == null) {
                problems.add(name + ":" + line + " end marker " + label + " has no start marker");
            } else if (!(open.group(2).equals(marker.group(2)) && java.util.Objects.equals(open.group(3), marker.group(3)))) {
                problems.add(name + ":" + line + " end marker " + label + " does not match the start marker generated:" + open.group(2)
                        + (open.group(3) == null ? "" : "#" + open.group(3)) + " at line " + openLine);
                open = null;
            } else {
                blocks.add(new Block(openLine, line, open.group(1), open.group(2), open.group(3)));
                open = null;
            }
        }
        if (open != null) {
            problems.add(name + ":" + openLine + " start marker generated:" + open.group(2) + (open.group(3) == null ? "" : "#" + open.group(3))
                    + " has no end marker");
        }
        return blocks;
    }

    /** The lines the generator writes for a block; null (with a problem) when its claims cannot be read or select nothing. */
    static List<String> render(Block block, Parsed parsed, String name, List<String> problems) {
        if (parsed.claims() == null) {
            problems.add(name + ":" + block.start() + " block " + block.label() + ": claims file cannot be read: " + String.join("; ", parsed.problems()));
            return null;
        }
        List<String> rendered = new ArrayList<>();
        for (Claim claim : parsed.claims()) {
            if (block.name() != null && !block.name().equals(claim.block())) continue;
            rendered.add(block.indent() + "* " + claim.text() + " (" + claim.label() + ", " + claim.basis() + ")");
        }
        if (rendered.isEmpty()) {
            problems.add(name + ":" + block.start() + " block " + block.label() + ": expected at least one claim, found none"
                    + (block.name() == null ? "" : " with block \"" + block.name() + "\""));
            return null;
        }
        return rendered;
    }

    private static void citations(List<String> lines, String name, List<Block> blocks, Map<String, Parsed> claims, List<String> problems) {
        for (int index = 0; index < lines.size(); index++) {
            Matcher citation = CITATION.matcher(lines.get(index));
            while (citation.find()) {
                String id = citation.group(1);
                List<Claim> defined = new ArrayList<>();
                List<String> files = new ArrayList<>();
                for (Map.Entry<String, Parsed> entry : claims.entrySet()) {
                    if (entry.getValue().claims() == null) continue;
                    for (Claim claim : entry.getValue().claims()) {
                        if (id.equals(claim.id())) {
                            defined.add(claim);
                            files.add(entry.getKey());
                        }
                    }
                }
                String where = name + ":" + (index + 1) + " citation (" + id;
                if (defined.isEmpty()) {
                    problems.add(where + " has no claim: " + (blocks.isEmpty() ? "no generated block in this file references a claims file"
                            : "not defined in " + String.join(", ", claims.keySet())));
                } else if (defined.size() > 1) {
                    problems.add(where + " is ambiguous: defined in " + String.join(", ", files));
                } else if (citation.group(2) != null && ClaimsCheck.BASES.contains(citation.group(2)) && !citation.group(2).equals(defined.get(0).basis())) {
                    problems.add(where + ", " + citation.group(2) + ") states basis " + citation.group(2) + ", but the claim's basis is " + defined.get(0).basis());
                }
            }
        }
    }

    private static Parsed parse(Path document, String path) {
        Path claimsFile = document.toAbsolutePath().getParent().resolve(path).normalize();
        if (!Files.isRegularFile(claimsFile)) return new Parsed(claimsFile, null, List.of("not found at " + ClaimsCheck.display(claimsFile)));
        return ClaimsCheck.parse(claimsFile);
    }

    /** The file's lines; a final line break yields a last empty line, so writing the lines back joined by line breaks keeps it. */
    static List<String> lines(Path document) {
        try {
            return List.of(Files.readString(document).split("\n", -1));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Paths of the claims files the document's balanced blocks reference, resolved. */
    static List<Path> referencedClaimsFiles(Path document) {
        List<Path> files = new ArrayList<>();
        for (Block block : scan(lines(document), ClaimsCheck.display(document), new ArrayList<>())) {
            Path resolved = document.toAbsolutePath().getParent().resolve(block.path()).normalize();
            if (!files.contains(resolved)) files.add(resolved);
        }
        return files;
    }
}
