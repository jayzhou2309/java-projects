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
import project.stockrecommendationengine.rag.evaluation.claims.ClaimsCheck.Result;

/**
 * Generated documentation blocks (RAG.md, Claims, Generated blocks). A block is the lines between a start marker and its end marker, each
 * alone on its line: {@code <!-- generated:<claims path> start -->} and {@code <!-- generated:<claims path> end -->}, where the claims path
 * is relative to the documentation file's directory and may end in {@code #<block>} to select the claims whose {@code block} equals it
 * (without it, every claim of the file). The generator writes one bullet per selected claim in file order, at the start marker's
 * indentation: {@code * <sentence> (<id>, <basis>)}, where the sentence is the one {@link ClaimsCheck#evaluate} renders (from the check for
 * observed and derived claims; the free text with its premises, reason, or experiment otherwise). The claims path must resolve inside the
 * evidence root.
 * <p>
 * {@link #check} reports, with file and line, every line of a block that differs from what {@link #write} would write, an unbalanced or nested
 * marker, a claims file that cannot be read or lies outside the evidence root, a selection matching no claim, a selected claim whose sentence
 * cannot be rendered, the line introducing a block (the nearest line above its start marker that is not blank once normalised, unless that
 * is a marker line) when it holds causal or absolute wording, digits, or spelled-out numbers ({@link Wording}), and every citation, an opening
 * parenthesis, optional whitespace, {@code C} in either case, a hyphen or dash, and digits, in the line as written or normalised: outside a
 * block every citation is a problem, since prose there is not checked and may not borrow a claim's authority; inside a block a citation must have the form {@code (C-nnn, <basis>)}, name a claim of a claims
 * file the document's blocks reference, unambiguously, and state that claim's basis.
 */
final class GeneratedBlocks {
    static final Pattern MARKER = Pattern.compile("^([ \\t]*)<!-- generated:([^\\s#]+)(?:#([A-Za-z0-9_-]+))? (start|end) -->[ \\t]*$");
    /** A citation as written: its opening ({@link Wording#CITATION}) and, when a closing parenthesis follows within 40 characters, up to it. */
    static final Pattern CITATION = Pattern.compile("\\(\\s*([Cc]\\s*[-\u2010-\u2015\u2212]\\s*\\d+)(?:[^()\\n]{0,40}\\))?");
    /** The one form a generated line writes. */
    static final Pattern WELL_FORMED = Pattern.compile("\\((C-\\d+), (observed|derived|inferred|unknown|experiment)\\)");

    /** One balanced block: its marker lines (1-based), the start marker's indentation, the claims path, and the block name or null. */
    record Block(int start, int end, String indent, String path, String name) {
        String label() {
            return "generated:" + path + (name == null ? "" : "#" + name);
        }
    }

    /** What the generator did to one documentation file: whether it rewrote the file, and what kept the file or a block from being written. */
    record Generation(boolean changed, List<String> problems) {
    }

    private GeneratedBlocks() {
    }

    /** Every problem of the documentation file; empty when its blocks are as generated and its citations resolve. */
    static List<String> check(Path document, Path evidenceRoot) {
        List<String> lines = lines(document);
        String name = ClaimsCheck.display(document);
        List<String> problems = new ArrayList<>();
        List<Block> blocks = scan(lines, name, problems);
        Map<String, Result> claims = new LinkedHashMap<>();
        for (Block block : blocks) {
            Result parsed = claims.computeIfAbsent(block.path(), path -> evaluate(document, path, evidenceRoot));
            introduction(lines, name, block, problems);
            List<Expected> expected = render(block, parsed, name, problems);
            if (expected == null) continue;
            List<String> found = lines.subList(block.start(), block.end() - 1);
            for (Difference difference : compare(expected, found)) {
                problems.add(name + ":" + (block.start() + 1 + difference.foundIndex()) + " block " + block.label() + " (lines " + block.start() + " to "
                        + block.end() + ") differs from the generator's output: " + difference.text());
            }
        }
        citations(lines, name, claims, problems);
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
        return compare(expected.stream().map(Expected::exactly).toList(), found);
    }

    /**
     * One line the generator expects: the exact line, or, for a selected claim whose sentence could not be rendered, any bullet at the block's
     * indentation ending with that claim's {@code (C-nnn, basis)}, so the block's other lines are still compared.
     */
    record Expected(String text, String prefix, String suffix) {
        static Expected exactly(String text) {
            return new Expected(text, null, null);
        }

        boolean matches(String line) {
            return text != null ? text.equals(line) : line.startsWith(prefix) && line.endsWith(suffix);
        }

        String describe() {
            return text != null ? "\"" + text + "\"" : "a line \"" + prefix + "...\" ending \"" + suffix + "\" (its sentence was not rendered)";
        }
    }

    /** {@link #differences} over expected lines that may be placeholders for claims without a rendered sentence. */
    static List<Difference> compare(List<Expected> expected, List<String> found) {
        int[][] common = new int[expected.size() + 1][found.size() + 1];
        for (int i = expected.size() - 1; i >= 0; i--) {
            for (int j = found.size() - 1; j >= 0; j--) {
                common[i][j] = expected.get(i).matches(found.get(j)) ? common[i + 1][j + 1] + 1 : Math.max(common[i + 1][j], common[i][j + 1]);
            }
        }
        List<Difference> differences = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < expected.size() || j < found.size()) {
            if (i < expected.size() && j < found.size() && expected.get(i).matches(found.get(j))) {
                i++;
                j++;
                continue;
            }
            List<Expected> missing = new ArrayList<>();
            List<Integer> extra = new ArrayList<>();
            while ((i < expected.size() || j < found.size()) && !(i < expected.size() && j < found.size() && expected.get(i).matches(found.get(j)))) {
                if (j >= found.size() || (i < expected.size() && common[i + 1][j] >= common[i][j + 1])) missing.add(expected.get(i++));
                else extra.add(j++);
            }
            int paired = Math.min(missing.size(), extra.size());
            for (int k = 0; k < paired; k++) {
                differences.add(new Difference(extra.get(k), "expected " + missing.get(k).describe() + ", found \"" + found.get(extra.get(k)) + "\""));
            }
            for (int k = paired; k < missing.size(); k++) differences.add(new Difference(j, "expected " + missing.get(k).describe() + ", found no line"));
            for (int k = paired; k < extra.size(); k++) differences.add(new Difference(extra.get(k), "expected no line, found \"" + found.get(extra.get(k)) + "\""));
        }
        return differences;
    }

    /**
     * Rewrites every block of the file from its claims. A file with an unbalanced, nested, or mismatched marker is not written at all, and a
     * block whose claims file cannot be read, selects no claim, or has a claim that cannot be rendered keeps its lines; each is reported in
     * the returned problems, and the caller goes on with its other files.
     */
    static Generation write(Path document, Path evidenceRoot) {
        List<String> lines = lines(document);
        String name = ClaimsCheck.display(document);
        List<String> problems = new ArrayList<>();
        List<Block> blocks = scan(lines, name, problems);
        if (!problems.isEmpty()) {
            List<String> reported = new ArrayList<>();
            problems.forEach(problem -> reported.add(problem + " (file not generated)"));
            return new Generation(false, reported);
        }
        List<String> output = new ArrayList<>();
        int next = 0;
        Map<String, Result> claims = new HashMap<>();
        for (Block block : blocks) {
            List<Expected> rendered = render(block, claims.computeIfAbsent(block.path(), path -> evaluate(document, path, evidenceRoot)), name, problems);
            output.addAll(lines.subList(next, block.start()));
            if (rendered != null && rendered.stream().allMatch(line -> line.text() != null)) rendered.forEach(line -> output.add(line.text()));
            else output.addAll(lines.subList(block.start(), block.end() - 1));
            next = block.end() - 1;
        }
        List<String> reported = new ArrayList<>();
        problems.forEach(problem -> reported.add(problem + " (block not generated)"));
        output.addAll(lines.subList(next, lines.size()));
        if (output.equals(lines)) return new Generation(false, reported);
        try {
            Files.writeString(document, String.join("\n", output));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return new Generation(true, reported);
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

    /**
     * The lines the generator writes for a block; null (with a problem) when its claims cannot be read or select nothing. A selected claim
     * whose sentence cannot be rendered is a problem and a placeholder line: the check still compares the block's other lines, and the
     * generator leaves the block as it is.
     */
    static List<Expected> render(Block block, Result parsed, String name, List<String> problems) {
        if (parsed.claims() == null) {
            problems.add(name + ":" + block.start() + " block " + block.label() + ": claims file cannot be read: " + String.join("; ", parsed.problems()));
            return null;
        }
        List<Expected> rendered = new ArrayList<>();
        List<String> unrendered = new ArrayList<>();
        for (Claim claim : parsed.claims()) {
            if (block.name() != null && !block.name().equals(claim.block())) continue;
            String sentence = parsed.sentences().get(claim.index());
            String suffix = " (" + claim.label() + ", " + claim.basis() + ")";
            if (sentence == null) {
                unrendered.add(claim.label());
                rendered.add(new Expected(null, block.indent() + "* ", suffix));
            } else {
                rendered.add(Expected.exactly(block.indent() + "* " + sentence + suffix));
            }
        }
        if (!unrendered.isEmpty()) {
            problems.add(name + ":" + block.start() + " block " + block.label() + ": " + ClaimsCheck.joinAnd(unrendered) + (unrendered.size() == 1 ? " has" : " have")
                    + " no rendered sentence (the problems of " + ClaimsCheck.display(parsed.file()) + " say why), so " + (unrendered.size() == 1 ? "its line is" : "their lines are")
                    + " compared by citation only and the block is not generated");
        }
        if (rendered.isEmpty()) {
            problems.add(name + ":" + block.start() + " block " + block.label() + ": expected at least one claim, found none"
                    + (block.name() == null ? "" : " with block \"" + block.name() + "\""));
            return null;
        }
        return rendered;
    }

    /**
     * Screens the line introducing the block: the nearest line above its start marker that is not blank once normalised ({@link Wording#blank}:
     * a line holding only an HTML comment, an invisible character, markdown marks, or Unicode space separators such as the no-break space
     * U+00A0 is skipped), when there is one and it is not itself a marker line (a block directly after another block has no introducing line of
     * its own).
     */
    private static void introduction(List<String> lines, String name, Block block, List<String> problems) {
        int index = block.start() - 2;
        while (index >= 0 && !MARKER.matcher(lines.get(index)).matches() && Wording.blank(lines.get(index))) index--;
        if (index < 0 || MARKER.matcher(lines.get(index)).matches()) return;
        String line = lines.get(index);
        List<String> found = new ArrayList<>();
        Wording.Found causal = Wording.causal(line);
        if (!causal.isEmpty()) found.add("expected no causal wording, found " + causal.quoted());
        Wording.Found absolute = Wording.absolute(line);
        if (!absolute.isEmpty()) found.add("expected no absolute or predictive wording, found " + absolute.quoted());
        Wording.Found numbers = Wording.numbers(line);
        if (!numbers.isEmpty()) found.add("expected no digits or spelled-out numbers, found " + numbers.quoted());
        if (!found.isEmpty()) {
            problems.add(name + ":" + (index + 1) + " line introducing block " + block.label() + " (start marker at line " + block.start() + "): " + String.join("; ", found));
        }
    }

    /**
     * Whether the line lies inside a block: the nearest marker line above it is a start marker. For balanced markers these are exactly the
     * blocks' lines; after a start marker without its end (already a problem) the lines up to the next marker count as the block's, so one
     * missing end marker is not also reported as every citation below it.
     */
    private static boolean insideBlock(List<String> lines, int index) {
        for (int above = index - 1; above >= 0; above--) {
            Matcher marker = MARKER.matcher(lines.get(above));
            if (marker.matches()) return marker.group(4).equals("start");
        }
        return false;
    }

    /** One citation of a line as written, and whether it appears only once the line is normalised ({@link Wording}). */
    record Citation(String written, String id, boolean normalised) {
    }

    /**
     * The line's citations as written (each distinct text once), then those that appear only after normalising, where a citation differing
     * from an earlier one only in whitespace is the same citation.
     */
    static List<Citation> citationsOf(String line) {
        List<Citation> found = new ArrayList<>();
        java.util.Set<String> written = new java.util.HashSet<>();
        java.util.Set<String> compact = new java.util.HashSet<>();
        List<String> readings = List.of(line, Wording.normalise(line, ""), Wording.normalise(line, " "));
        for (int reading = 0; reading < readings.size(); reading++) {
            Matcher citation = CITATION.matcher(readings.get(reading));
            while (citation.find()) {
                String text = citation.group();
                boolean added = reading == 0 ? written.add(text) : !compact.contains(text.replaceAll("\\s+", "")) && written.add(text);
                compact.add(text.replaceAll("\\s+", ""));
                if (added) found.add(new Citation(text, "C-" + citation.group(1).replaceAll("\\D", ""), reading > 0));
            }
        }
        return found;
    }

    private static void citations(List<String> lines, String name, Map<String, Result> claims, List<String> problems) {
        for (int index = 0; index < lines.size(); index++) {
            for (Citation citation : citationsOf(lines.get(index))) {
                String at = name + ":" + (index + 1) + " citation " + citation.written() + (citation.normalised() ? " (" + Wording.NORMALISED + ")" : "");
                if (MARKER.matcher(lines.get(index)).matches() || !insideBlock(lines, index)) {
                    problems.add(at + " is outside a generated block: expected claim citations only inside generated blocks"
                            + " (prose outside a block is not checked, so it may not cite a claim)");
                    continue;
                }
                Matcher form = WELL_FORMED.matcher(citation.written());
                boolean wellFormed = !citation.normalised() && form.matches();
                if (!wellFormed) problems.add(at + " expected the form (C-nnn, <basis>), with a comma, one space, and the basis in lower case");
                List<Claim> defined = new ArrayList<>();
                List<String> files = new ArrayList<>();
                for (Map.Entry<String, Result> entry : claims.entrySet()) {
                    if (entry.getValue().claims() == null) continue;
                    for (Claim claim : entry.getValue().claims()) {
                        if (citation.id().equals(claim.id())) {
                            defined.add(claim);
                            files.add(entry.getKey());
                        }
                    }
                }
                String where = name + ":" + (index + 1) + " citation (" + citation.id();
                if (defined.isEmpty()) {
                    problems.add(where + " has no claim: not defined in " + String.join(", ", claims.keySet()));
                } else if (defined.size() > 1) {
                    problems.add(where + " is ambiguous: defined in " + String.join(", ", files));
                } else if (wellFormed && !form.group(2).equals(defined.get(0).basis())) {
                    problems.add(where + ", " + form.group(2) + ") states basis " + form.group(2) + ", but the claim's basis is " + defined.get(0).basis());
                }
            }
        }
    }

    private static Result evaluate(Path document, String path, Path evidenceRoot) {
        Path claimsFile = document.toAbsolutePath().getParent().resolve(path).normalize();
        String outside = ClaimsCheck.outside(claimsFile, evidenceRoot);
        if (outside != null) return new Result(claimsFile, null, List.of(outside), Map.of());
        if (!Files.isRegularFile(claimsFile)) return new Result(claimsFile, null, List.of("not found at " + ClaimsCheck.display(claimsFile)), Map.of());
        return ClaimsCheck.evaluate(claimsFile, evidenceRoot);
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
