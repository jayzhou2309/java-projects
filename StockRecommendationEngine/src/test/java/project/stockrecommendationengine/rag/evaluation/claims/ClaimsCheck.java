package project.stockrecommendationengine.rag.evaluation.claims;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks one measurement's {@code claims.json} against committed evidence files only (RAG.md, Claims): evaluation snapshots as
 * {@code row_to_json} exports or API JSON, and evidence reports ({@code GET /api/rag/evaluate/{id}/evidence}) as JSON. It never reads the
 * database or calls a model, so it runs in {@code verify}. Paths in a claim resolve against the claims file's directory and must stay inside
 * the evidence root ({@code src/main/java/documentation/live-runs} for the repository check).
 * <p>
 * The sentence of an {@code observed} or {@code derived} claim is not written by hand: it is rendered by a fixed template per check type
 * from the check's parameters and the values found in the evidence ({@link #evaluate} returns it; the generator prints it). Only
 * {@code inferred}, {@code unknown}, and {@code experiment} claims carry free {@code text}, which is printed with its premises, the
 * report's reason, or the experiment and its checked outcome; it is screened for causal and absolute wording and citations ({@link Wording}),
 * never proven. Labels are screened the same way and for digits and spelled-out numbers, and a file may have one label. Every branch of every
 * template is listed in RAG.md, Claims, Sentences.
 * <p>
 * {@link #check} returns every problem of the file, one line each, naming the claim id, the check type, and what the claim expects beside
 * what the evidence holds; nothing stops at the first problem, and an exception inside one claim becomes a problem of that claim.
 */
public final class ClaimsCheck {
    static final List<String> BASES = List.of("observed", "derived", "inferred", "unknown", "experiment");
    static final Set<String> FREE_TEXT_BASES = Set.of("inferred", "unknown", "experiment");
    static final List<String> CHECK_TYPES = List.of("rank", "topK", "metric", "ruleRow", "phraseSpan", "membership", "candidate", "bestFusedPosition",
            "candidateRecall", "removedAccepted", "blend", "candidateLists", "diagnosticLine", "diagnosticCount", "sizeTable", "sizeChoice", "matchedChunk",
            "rankHistogram", "rankedAbove", "fileValue", "heldPhrases", "subsetMetric", "questionEquality", "notRecorded");
    static final List<String> CRITERIA = List.of("aggregateHitAt5", "nonFigureHitAt5", "tickerHitAt5", "figureKindTop5");
    static final Pattern ID = Pattern.compile("C-\\d{3,}");
    static final Pattern BLOCK = Pattern.compile("[A-Za-z0-9_-]+");

    private static final Pattern METRIC = Pattern.compile("(hitAt1|hitAt3|hitAt5|mrr)|slices\\.(figure|nonFigure)\\.(hitAt1|hitAt3|hitAt5|mrr)"
            + "|tickerHitAt5\\.([A-Z0-9.-]{1,16})");
    private static final Set<String> FILE_KEYS = Set.of("claims", "description", "labels");
    private static final Set<String> CLAIM_KEYS = Set.of("id", "basis", "block", "check", "text", "from", "experiment");
    private static final Map<String, Set<String>> CHECK_KEYS = Map.ofEntries(
            Map.entry("rank", Set.of("type", "snapshot", "question", "expected")),
            Map.entry("topK", Set.of("type", "question", "k", "rows")),
            Map.entry("metric", Set.of("type", "snapshot", "metric", "expected")),
            Map.entry("ruleRow", Set.of("type", "reference", "candidate", "criteria")),
            Map.entry("phraseSpan", Set.of("type", "report", "question", "phrase", "chunk", "occurrence", "expected")),
            Map.entry("membership", Set.of("type", "report", "question", "phrase", "chunk", "occurrence", "expected")),
            Map.entry("candidate", Set.of("type", "report", "question", "chunk", "expected")),
            Map.entry("bestFusedPosition", Set.of("type", "report", "question", "expected")),
            Map.entry("candidateRecall", Set.of("type", "report", "k", "expected")),
            Map.entry("removedAccepted", Set.of("type", "report", "expected")),
            Map.entry("blend", Set.of("type", "snapshot", "report", "k", "w", "questions", "metric", "expected", "reference")),
            Map.entry("candidateLists", Set.of("type", "reference", "candidate", "compare", "expected")),
            Map.entry("diagnosticLine", Set.of("type", "output", "experiment", "line", "question", "chunk", "sizeChars", "field", "expected")),
            Map.entry("diagnosticCount", Set.of("type", "output", "experiment", "sizeChars", "expected")),
            Map.entry("sizeTable", Set.of("type", "output", "sizeChars", "expected")),
            Map.entry("sizeChoice", Set.of("type", "output", "stored", "candidates", "expected")),
            Map.entry("matchedChunk", Set.of("type", "snapshot", "question", "expected")),
            Map.entry("rankHistogram", Set.of("type", "snapshot", "expected")),
            Map.entry("rankedAbove", Set.of("type", "snapshot", "question", "chunks", "expected")),
            Map.entry("fileValue", Set.of("type", "file", "path", "expected")),
            Map.entry("heldPhrases", Set.of("type", "report", "expected")),
            Map.entry("subsetMetric", Set.of("type", "snapshot", "questions", "metric", "expected")),
            Map.entry("questionEquality", Set.of("type", "reference", "candidate", "compare", "referenceChunks", "candidateChunks", "expected")),
            Map.entry("notRecorded", Set.of("type", "report", "path", "reason")));
    private static final Set<String> ROW_KEYS = Set.of("snapshot", "expected");
    private static final Map<String, List<String>> EXPECTED_KEYS = Map.of(
            "phraseSpan", List.of("tokenSpan", "characterSpan"),
            "membership", List.of("head", "windowsHoldingWholly"),
            "candidate", List.of("fusedPosition", "rerankInput", "rerankedPosition"),
            "sizeTable", List.of("phrases", "firstRanked", "split", "unseen"),
            "heldPhrases", List.of("phrases", "held"));
    private static final Set<String> EXPERIMENT_REFERENCE_KEYS = Set.of("file", "factor");
    private static final Set<String> EXPERIMENT_FILE_KEYS = Set.of("experiment", "description");
    private static final Set<String> EXPERIMENT_KEYS = Set.of("factor", "settings", "snapshots", "coupled");
    private static final Map<String, String> CRITERION_NAMES = Map.of("aggregateHitAt5", "aggregate hit@5", "nonFigureHitAt5", "non-figure hit@5",
            "tickerHitAt5", "per-ticker hit@5", "figureKindTop5", "FIGURE top-5");

    /** Numbers as written; a key repeated in one object is a read error, so a second "expected" cannot silently replace the first. */
    static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    /** One claim as written; {@code index} is its 1-based position in the file; {@code hasText} is whether the claim has a text key. */
    public record Claim(int index, String id, String text, boolean hasText, String basis, String block, JsonNode check, JsonNode from,
            JsonNode experiment) {
        String label() {
            return id != null ? id : "claim #" + index;
        }
    }

    /**
     * A claims file's outcome: its claims as written (null when the file cannot be read), every problem, and per claim index the sentence
     * the generator prints before {@code (C-nnn, basis)}; a claim whose sentence cannot be rendered has no entry.
     */
    public record Result(Path file, List<Claim> claims, List<String> problems, Map<Integer, String> sentences) {
    }

    private record Outcome(List<String> problems, String sentence) {
    }

    private record Loaded(Path path, String written, JsonNode json) {
    }

    private record Experiment(String file, List<Loaded> snapshots, List<Long> ids, String rendered) {
    }

    private final Path file;
    private final Path directory;
    private final Path root;
    private final String name;
    private final Map<Path, Object> files = new HashMap<>();
    private final Map<String, Claim> byId = new LinkedHashMap<>();
    private final Map<String, Outcome> evaluated = new HashMap<>();
    private final Map<Path, String> labels = new LinkedHashMap<>();
    private final Map<Path, String> labelKeys = new LinkedHashMap<>();
    private final Set<Path> referenced = new HashSet<>();

    private ClaimsCheck(Path file, Path evidenceRoot) {
        this.file = file.toAbsolutePath().normalize();
        this.directory = this.file.getParent();
        this.root = evidenceRoot.toAbsolutePath().normalize();
        this.name = display(file);
    }

    /** Every problem of the claims file, in claim order; empty when every claim holds. */
    public static List<String> check(Path claimsFile, Path evidenceRoot) {
        return evaluate(claimsFile, evidenceRoot).problems();
    }

    /** The claims file's claims, problems, and rendered sentences. */
    public static Result evaluate(Path claimsFile, Path evidenceRoot) {
        return new ClaimsCheck(claimsFile, evidenceRoot).run();
    }

    /** The path as shown in problem lines: relative to the working directory when under it. */
    static String display(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path cwd = Path.of("").toAbsolutePath();
        return absolute.startsWith(cwd) ? cwd.relativize(absolute).toString() : absolute.toString();
    }

    /**
     * Null when {@code path} lies inside {@code evidenceRoot}, both normalised and, for a file that exists, with symbolic links resolved;
     * otherwise where it resolves to.
     */
    static String outside(Path path, Path evidenceRoot) {
        Path absolute = path.toAbsolutePath().normalize();
        Path rootAbsolute = evidenceRoot.toAbsolutePath().normalize();
        if (!absolute.startsWith(rootAbsolute)) return "resolves to " + display(absolute) + ", outside the evidence root " + display(rootAbsolute);
        try {
            if (Files.exists(absolute) && Files.exists(rootAbsolute) && !absolute.toRealPath().startsWith(rootAbsolute.toRealPath())) {
                return "resolves through a symbolic link to " + absolute.toRealPath() + ", outside the evidence root " + display(rootAbsolute);
            }
        } catch (IOException failure) {
            return "cannot be resolved: " + failure.getMessage();
        }
        return null;
    }

    private Result run() {
        List<String> problems = new ArrayList<>();
        Map<Integer, String> sentences = new HashMap<>();
        String outside = outside(file, root);
        if (outside != null) {
            problems.add(name + ": the claims file " + outside);
            return new Result(file, null, problems, sentences);
        }
        List<Claim> claims = read(problems);
        if (claims == null) return new Result(file, null, problems, sentences);
        for (Claim claim : claims) {
            Outcome outcome = evaluate(claim, new ArrayDeque<>());
            problems.addAll(outcome.problems());
            if (outcome.sentence() != null) sentences.put(claim.index(), outcome.sentence());
        }
        for (Map.Entry<Path, String> label : labels.entrySet()) {
            if (!referenced.contains(label.getKey())) {
                problems.add(name + " labels \"" + labelKeys.get(label.getKey()) + "\": expected a file a check reads, found no check reading it");
            }
        }
        return new Result(file, claims, problems, sentences);
    }

    private List<Claim> read(List<String> problems) {
        JsonNode document;
        try {
            document = JSON.readTree(Files.readString(file));
        } catch (IOException | JacksonException failure) {
            problems.add(name + ": cannot be read as JSON: " + firstLine(failure.getMessage()));
            return null;
        }
        if (!document.isObject() || !document.path("claims").isArray()) {
            problems.add(name + ": expected an object with a \"claims\" array, found " + abbreviate(document.toString()));
            return null;
        }
        for (String key : document.propertyNames()) {
            if (!FILE_KEYS.contains(key)) problems.add(name + ": unknown top-level key \"" + key + "\"");
        }
        if (document.has("description") && !document.get("description").isString()) {
            problems.add(name + ": description expected a string, found " + abbreviate(document.get("description").toString()));
        }
        if (document.has("labels")) readLabels(document.get("labels"), problems);
        List<Claim> claims = new ArrayList<>();
        int index = 0;
        for (JsonNode node : document.get("claims")) {
            index++;
            if (!node.isObject()) {
                problems.add(name + " claim #" + index + ": expected an object, found " + abbreviate(node.toString()));
                continue;
            }
            Claim claim = new Claim(index, text(node, "id"), text(node, "text"), node.has("text"), text(node, "basis"), text(node, "block"),
                    node.get("check"), node.get("from"), node.get("experiment"));
            claims.add(claim);
            if (claim.id() != null && ID.matcher(claim.id()).matches() && byId.putIfAbsent(claim.id(), claim) != null) {
                problems.add(name + " claim #" + index + ": id " + claim.id() + " is already used by claim #" + byId.get(claim.id()).index());
            }
            String category = claim.check() != null && CHECK_TYPES.contains(claim.check().path("type").asString("")) ? claim.check().get("type").asString() : "format";
            for (String key : node.propertyNames()) {
                if (!CLAIM_KEYS.contains(key)) problems.add(name + " " + claim.label() + " [" + category + "]: unknown key \"" + key + "\"");
            }
        }
        return claims;
    }

    private void readLabels(JsonNode node, List<String> problems) {
        if (!node.isObject()) {
            problems.add(name + ": labels expected an object mapping a file to its label, found " + abbreviate(node.toString()));
            return;
        }
        Map<Path, String> labelled = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String where = name + " labels \"" + entry.getKey() + "\": ";
            if (!entry.getValue().isString()) {
                problems.add(where + "label expected a string, found " + abbreviate(entry.getValue().toString()));
                continue;
            }
            String label = entry.getValue().stringValue();
            List<String> found = new ArrayList<>();
            if (!oneLine(label)) found.add("label expected one non-blank line without comment markers, found " + quote(label));
            Wording.Found numbers = Wording.numbers(label);
            if (!numbers.isEmpty()) found.add("label expected no digits or spelled-out numbers (numbers are rendered from the evidence), found " + numbers.quoted() + " in " + quote(label));
            Wording.Found causal = Wording.causal(label);
            if (!causal.isEmpty()) found.add("label expected no causal wording, found " + causal.quoted());
            Wording.Found absolute = Wording.absolute(label);
            if (!absolute.isEmpty()) found.add("label expected no absolute or predictive wording, found " + absolute.quoted());
            Wording.Found citations = Wording.citations(label);
            if (!citations.isEmpty()) found.add("label expected no claim citation, found " + citations.quoted());
            Path resolved = null;
            try {
                resolved = resolve(entry.getKey(), directory);
            } catch (Unreadable | IllegalArgumentException unreadable) {
                found.add(unreadable.getMessage());
            }
            if (resolved != null && labelled.putIfAbsent(resolved, entry.getKey()) != null) {
                found.add("expected one label per file, found a second label for " + display(resolved) + ", already labelled by \"" + labelled.get(resolved) + "\"");
            }
            found.forEach(problem -> problems.add(where + problem));
            if (found.isEmpty()) {
                labels.put(resolved, label);
                labelKeys.put(resolved, entry.getKey());
            }
        }
    }

    /** The claim's own problems and printed sentence; premises of an inferred claim are evaluated once and reused. */
    private Outcome evaluate(Claim claim, Deque<String> visiting) {
        if (claim.id() != null && evaluated.containsKey(claim.id()) && byId.get(claim.id()) == claim) return evaluated.get(claim.id());
        List<String> problems = new ArrayList<>();
        String sentence = null;
        String prefix = name + " " + claim.label();
        try {
            boolean evaluable = structure(claim, prefix, problems);
            boolean textPrintable = FREE_TEXT_BASES.contains(claim.basis()) && claim.text() != null && oneLine(claim.text());
            if (evaluable) {
                switch (claim.basis()) {
                    case "inferred" -> {
                        List<String> premises = premises(claim, prefix, visiting);
                        problems.addAll(premises);
                        List<String> ids = new ArrayList<>();
                        claim.from().forEach(premise -> ids.add(premise.isString() ? premise.stringValue() : premise.toString()));
                        if (textPrintable) sentence = claim.text() + " (inferred from " + joinAnd(ids) + ")";
                    }
                    case "unknown" -> {
                        Evaluation evaluation = evaluateCheck(claim, prefix);
                        problems.addAll(evaluation.problems());
                        if (textPrintable && evaluation.sentence != null) sentence = claim.text() + " " + evaluation.sentence;
                    }
                    case "experiment" -> {
                        List<String> experimentProblems = new ArrayList<>();
                        Experiment experiment = experiment(claim, prefix, experimentProblems);
                        Evaluation evaluation = evaluateCheck(claim, prefix);
                        problems.addAll(experimentProblems);
                        problems.addAll(evaluation.problems());
                        if (experiment != null) problems.addAll(experimentReferences(prefix, experiment, evaluation));
                        if (textPrintable && experiment != null && evaluation.sentence != null) {
                            sentence = claim.text() + " (experiment: " + experiment.rendered() + "; checked: " + decapitalize(withoutPeriod(evaluation.sentence)) + ")";
                        }
                    }
                    default -> {
                        Evaluation evaluation = evaluateCheck(claim, prefix);
                        problems.addAll(evaluation.problems());
                        sentence = evaluation.sentence;
                    }
                }
            }
        } catch (RuntimeException failure) {
            problems.add(prefix + " [error]: evaluating the claim threw " + failure.getClass().getSimpleName() + ": " + firstLine(failure.getMessage()));
            sentence = null;
        }
        Outcome outcome = new Outcome(problems, sentence);
        if (claim.id() != null && byId.get(claim.id()) == claim) evaluated.put(claim.id(), outcome);
        return outcome;
    }

    /**
     * Adds the claim's shape and wording problems; true when its check (or premises) can be evaluated. Text, wording, and block-name problems
     * do not stop evaluation; the rest do.
     */
    private boolean structure(Claim claim, String prefix, List<String> problems) {
        boolean evaluable = true;
        if (claim.id() == null || !ID.matcher(claim.id()).matches()) {
            problems.add(prefix + " [format]: id expected C- followed by at least three digits, found " + quote(claim.id()));
            evaluable = false;
        }
        if (claim.basis() == null || !BASES.contains(claim.basis())) {
            problems.add(prefix + " [format]: basis expected one of " + String.join(", ", BASES) + ", found " + quote(claim.basis()));
            return false;
        }
        if (FREE_TEXT_BASES.contains(claim.basis())) {
            if (claim.text() == null || !oneLine(claim.text())) {
                problems.add(prefix + " [format]: text expected one non-blank line without comment markers on basis " + claim.basis() + ", found " + quote(claim.text()));
            }
        } else if (claim.hasText()) {
            problems.add(prefix + " [format]: text expected none on basis " + claim.basis() + " (its sentence is rendered from its check), found " + quote(claim.text()));
        }
        if (claim.text() != null) {
            Wording.Found causal = Wording.causal(claim.text());
            if (!causal.isEmpty() && !claim.basis().equals("experiment")) {
                problems.add(prefix + " [causal wording]: " + causal.quoted() + (causal.words().size() == 1 ? " states" : " state") + " a cause: basis expected experiment"
                        + " (with an experiment file whose two snapshots differ only in that factor), found " + claim.basis());
            }
            Wording.Found absolute = Wording.absolute(claim.text());
            if (!absolute.isEmpty()) {
                problems.add(prefix + " [absolute wording]: " + absolute.quoted() + " expected no absolute or predictive wording in free text, found in " + quote(claim.text()));
            }
            Wording.Found citations = Wording.citations(claim.text());
            if (!citations.isEmpty()) {
                problems.add(prefix + " [format]: text expected no claim citation (an inferred claim lists its premises in \"from\"; the generator prints the claim's own), found "
                        + citations.quoted() + " in " + quote(claim.text()));
            }
        }
        if (claim.block() != null && !BLOCK.matcher(claim.block()).matches()) {
            problems.add(prefix + " [format]: block expected letters, digits, _ or -, found " + quote(claim.block()));
        }
        boolean inferred = claim.basis().equals("inferred");
        if (claim.from() != null && !inferred) {
            problems.add(prefix + " [format]: \"from\" is only for basis inferred, found on basis " + claim.basis());
            evaluable = false;
        }
        if (claim.experiment() != null && !claim.basis().equals("experiment")) {
            problems.add(prefix + " [format]: \"experiment\" is only for basis experiment, found on basis " + claim.basis());
            evaluable = false;
        }
        if (inferred) {
            if (claim.check() != null) {
                problems.add(prefix + " [inferred]: check expected none (an inference is not evaluated), found " + abbreviate(claim.check().toString()));
                evaluable = false;
            }
            if (claim.from() == null || !claim.from().isArray() || claim.from().isEmpty()) {
                problems.add(prefix + " [inferred]: premises expected at least one claim id in \"from\", found " + (claim.from() == null ? "none" : claim.from().toString()));
                evaluable = false;
            }
            return evaluable;
        }
        if (claim.check() == null || !claim.check().isObject()) {
            problems.add(prefix + " [format]: check expected an object with a type, found " + (claim.check() == null ? "none" : abbreviate(claim.check().toString())));
            return false;
        }
        String type = claim.check().path("type").asString("");
        if (!CHECK_TYPES.contains(type)) {
            problems.add(prefix + " [format]: check type expected one of " + String.join(", ", CHECK_TYPES) + ", found " + quote(type));
            return false;
        }
        if (claim.basis().equals("unknown") && !type.equals("notRecorded")) {
            problems.add(prefix + " [" + type + "]: basis unknown expected check type notRecorded, found " + type);
            evaluable = false;
        }
        if (type.equals("notRecorded") && !claim.basis().equals("unknown")) {
            problems.add(prefix + " [notRecorded]: check type notRecorded expected basis unknown, found " + claim.basis());
            evaluable = false;
        }
        if (claim.basis().equals("experiment")) {
            JsonNode reference = claim.experiment();
            if (reference == null || !reference.isObject()) {
                problems.add(prefix + " [experiment]: experiment expected an object with file and factor, found " + (reference == null ? "none" : abbreviate(reference.toString())));
                evaluable = false;
            } else {
                for (String key : reference.propertyNames()) {
                    if (!EXPERIMENT_REFERENCE_KEYS.contains(key)) {
                        problems.add(prefix + " [experiment]: unknown experiment key \"" + key + "\"");
                        evaluable = false;
                    }
                }
                if (blank(reference.get("file")) || blank(reference.get("factor"))) {
                    problems.add(prefix + " [experiment]: experiment expected a file and a factor, found " + abbreviate(reference.toString()));
                    evaluable = false;
                }
            }
        }
        String label = prefix + " [" + type + "]: ";
        for (String key : claim.check().propertyNames()) {
            if (!CHECK_KEYS.get(type).contains(key)) {
                problems.add(label + "unknown check key \"" + key + "\"");
                evaluable = false;
            }
        }
        JsonNode rows = claim.check().get("rows");
        if (type.equals("topK") && rows != null && rows.isArray()) {
            for (int i = 0; i < rows.size(); i++) {
                if (!rows.get(i).isObject()) {
                    problems.add(label + "row " + (i + 1) + " expected an object {snapshot, expected}, found " + abbreviate(rows.get(i).toString()));
                    evaluable = false;
                    continue;
                }
                for (String key : rows.get(i).propertyNames()) {
                    if (!ROW_KEYS.contains(key)) {
                        problems.add(label + "unknown key \"" + key + "\" in row " + (i + 1));
                        evaluable = false;
                    }
                }
            }
        }
        JsonNode expected = claim.check().get("expected");
        if (EXPECTED_KEYS.containsKey(type) && expected != null && expected.isObject()) {
            for (String key : expected.propertyNames()) {
                if (!EXPECTED_KEYS.get(type).contains(key)) {
                    problems.add(label + "unknown expected field \"" + key + "\" (one of " + String.join(", ", EXPECTED_KEYS.get(type)) + ")");
                    evaluable = false;
                }
            }
        }
        JsonNode criteria = claim.check().get("criteria");
        if (type.equals("ruleRow") && criteria != null && criteria.isObject()) {
            for (String key : criteria.propertyNames()) {
                if (!CRITERIA.contains(key)) {
                    problems.add(label + "unknown criterion \"" + key + "\" (one of " + String.join(", ", CRITERIA) + ")");
                    evaluable = false;
                }
            }
        }
        return evaluable;
    }

    private List<String> premises(Claim claim, String prefix, Deque<String> visiting) {
        List<String> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        visiting.push(claim.id());
        for (JsonNode premise : claim.from()) {
            String id = premise.isString() ? premise.stringValue() : premise.toString();
            if (!seen.add(id)) {
                problems.add(prefix + " [inferred]: premise " + id + " is listed twice");
            } else if (id.equals(claim.id())) {
                problems.add(prefix + " [inferred]: premise expected another claim, found the claim itself");
            } else if (!byId.containsKey(id)) {
                problems.add(prefix + " [inferred]: premise " + id + " expected an existing claim, found none with that id");
            } else if (visiting.contains(id)) {
                problems.add(prefix + " [inferred]: premise " + id + " expected no cycle, found " + id + " inferred from " + claim.id() + " in turn");
            } else {
                List<String> failing = evaluate(byId.get(id), visiting).problems();
                if (!failing.isEmpty()) {
                    problems.add(prefix + " [inferred]: premise " + id + " expected to pass, found " + failing.size() + (failing.size() == 1 ? " problem" : " problems")
                            + " (listed under " + id + ")");
                }
            }
        }
        visiting.pop();
        return problems;
    }

    /**
     * The experiment the claim references, or null with problems: its file must declare the factor, two different settings, and two
     * snapshot files whose recorded properties differ in exactly that factor, each setting equal to its snapshot's recorded value.
     */
    private Experiment experiment(Claim claim, String prefix, List<String> problems) {
        String label = prefix + " [experiment]: ";
        String experimentFile = claim.experiment().get("file").asString();
        String factor = claim.experiment().get("factor").asString();
        Loaded loaded;
        try {
            loaded = load(experimentFile, directory, null);
        } catch (Unreadable | IllegalArgumentException unreadable) {
            problems.add(label + unreadable.getMessage());
            return null;
        }
        List<String> found = new ArrayList<>();
        JsonNode document = loaded.json();
        for (String key : document.propertyNames()) {
            if (!EXPERIMENT_FILE_KEYS.contains(key)) found.add(experimentFile + " unknown key \"" + key + "\"");
        }
        JsonNode declared = document.get("experiment");
        if (declared == null || !declared.isObject()) {
            found.add(experimentFile + " expected an \"experiment\" object with factor, settings, and snapshots, found " + (declared == null ? "none" : abbreviate(declared.toString())));
            found.forEach(problem -> problems.add(label + problem));
            return null;
        }
        for (String key : declared.propertyNames()) {
            if (!EXPERIMENT_KEYS.contains(key)) found.add(experimentFile + " unknown experiment key \"" + key + "\"");
        }
        JsonNode declaredFactor = declared.get("factor");
        if (declaredFactor == null || !declaredFactor.isString()) {
            found.add(experimentFile + " factor expected \"" + factor + "\", found " + (declaredFactor == null ? "none" : declaredFactor.toString()));
        } else if (!declaredFactor.stringValue().equals(factor)) {
            found.add(experimentFile + " factor expected \"" + factor + "\", found \"" + declaredFactor.stringValue() + "\"");
        }
        JsonNode settings = declared.get("settings");
        if (settings == null || !settings.isArray() || settings.size() != 2 || sameValue(settings.get(0), settings.get(1))) {
            found.add(experimentFile + " settings expected exactly two different settings of the factor, found " + (settings == null ? "none" : settings.toString()));
        }
        JsonNode snapshotFiles = declared.get("snapshots");
        if (snapshotFiles == null || !snapshotFiles.isArray() || snapshotFiles.size() != 2 || !snapshotFiles.get(0).isString() || !snapshotFiles.get(1).isString()) {
            found.add(experimentFile + " snapshots expected two committed snapshot files, one per setting, found " + (snapshotFiles == null ? "none" : abbreviate(snapshotFiles.toString())));
        }
        // Since 2026-09-17 (plan 2026-09-17-chunk-size.md, Milestone 3): recorded properties that change together with the factor, each of which
        // must differ between the two snapshots and is named in the rendered sentence; the factor itself may not be listed.
        List<String> coupled = new ArrayList<>();
        JsonNode declaredCoupled = declared.get("coupled");
        if (declaredCoupled != null) {
            if (!declaredCoupled.isArray()) {
                found.add(experimentFile + " coupled expected a list of recorded property names that change with the factor, found " + abbreviate(declaredCoupled.toString()));
            } else {
                for (JsonNode name : declaredCoupled) {
                    if (!name.isString() || name.stringValue().isBlank()) found.add(experimentFile + " coupled expected property names, found " + name);
                    else if (name.stringValue().equals(factor)) found.add(experimentFile + " coupled expected properties other than the factor, found the factor " + factor);
                    else if (coupled.contains(name.stringValue())) found.add(experimentFile + " coupled lists " + name.stringValue() + " twice");
                    else coupled.add(name.stringValue());
                }
            }
        }
        if (!found.isEmpty()) {
            found.forEach(problem -> problems.add(label + problem));
            return null;
        }
        Path experimentDirectory = loaded.path().getParent();
        List<Loaded> snapshots = new ArrayList<>();
        List<Map<String, JsonNode>> recorded = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (JsonNode snapshotFile : snapshotFiles) {
            try {
                Loaded snapshot = load(snapshotFile.stringValue(), experimentDirectory, null);
                Snapshot parsed = Snapshot.of(snapshotFile.stringValue(), snapshot.json());
                if (parsed.id() == null) throw new Unreadable(snapshotFile.stringValue() + " records no snapshot id");
                snapshots.add(snapshot);
                recorded.add(parsed.recorded());
                ids.add(parsed.id());
            } catch (Unreadable | IllegalArgumentException unreadable) {
                found.add(experimentFile + " snapshot " + unreadable.getMessage());
            }
        }
        if (found.isEmpty() && snapshots.get(0).path().equals(snapshots.get(1).path())) found.add(experimentFile + " snapshots expected two different files, found " + snapshotFiles);
        if (!found.isEmpty()) {
            found.forEach(problem -> problems.add(label + problem));
            return null;
        }
        String first = snapshotFiles.get(0).stringValue();
        String second = snapshotFiles.get(1).stringValue();
        JsonNode firstValue = recorded.get(0).get(factor);
        JsonNode secondValue = recorded.get(1).get(factor);
        if (firstValue == null && secondValue == null) {
            found.add(experimentFile + " factor " + factor + " expected a property both snapshots record, found neither recording it");
        } else {
            for (int i = 0; i < 2; i++) {
                JsonNode value = recorded.get(i).get(factor);
                if (value == null || !sameValue(settings.get(i), value)) {
                    found.add(experimentFile + " settings[" + i + "] expected the " + factor + " recorded by " + snapshotFiles.get(i).stringValue() + " ("
                            + (value == null ? "not recorded" : shown(value, settings.get(i))) + "), found " + shown(settings.get(i), value));
                }
            }
            if (firstValue != null && secondValue != null && sameValue(firstValue, secondValue)) {
                found.add(experimentFile + " snapshots expected to differ in " + factor + ", found " + render(firstValue) + " in both");
            }
        }
        List<String> coupledRendered = new ArrayList<>();
        for (String key : coupled) {
            JsonNode a = recorded.get(0).get(key);
            JsonNode b = recorded.get(1).get(key);
            if (a == null || b == null) {
                found.add(experimentFile + " coupled property " + key + " expected a property both snapshots record, found " + (a == null ? first : second) + " not recording it");
            } else if (sameValue(a, b)) {
                found.add(experimentFile + " snapshots expected to differ in the coupled property " + key + ", found " + render(a) + " in both");
            } else {
                coupledRendered.add(key + " " + render(a) + " against " + render(b));
            }
        }
        List<String> others = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(recorded.get(0).keySet());
        keys.addAll(recorded.get(1).keySet());
        for (String key : keys) {
            if (key.equals(factor) || coupled.contains(key)) continue;
            JsonNode a = recorded.get(0).get(key);
            JsonNode b = recorded.get(1).get(key);
            if (a == null || b == null || !sameValue(a, b)) {
                others.add(key + " " + (a == null ? "not recorded" : shown(a, b)) + " against " + (b == null ? "not recorded" : shown(b, a)));
            }
        }
        if (!others.isEmpty()) {
            found.add(experimentFile + " snapshots " + first + " and " + second + " expected to differ only in " + factor + (coupled.isEmpty() ? "" : " and the coupled " + String.join(", ", coupled)) + ", found " + others.size()
                    + (others.size() == 1 ? " other recorded property differing: " : " other recorded properties differing: ") + String.join(", ", others));
        }
        if (!found.isEmpty()) {
            found.forEach(problem -> problems.add(label + problem));
            return null;
        }
        String rendered = factor + " " + render(settings.get(0)) + " in snapshot " + ids.get(0) + " against " + render(settings.get(1)) + " in snapshot " + ids.get(1)
                + (coupledRendered.isEmpty() ? "" : ", with it " + joinAnd(coupledRendered));
        return new Experiment(experimentFile, snapshots, ids, rendered);
    }

    /** Every file an experiment claim's check reads must be one of the experiment's snapshots or an evidence report of one of them. */
    private List<String> experimentReferences(String prefix, Experiment experiment, Evaluation evaluation) {
        List<String> problems = new ArrayList<>();
        Set<Path> snapshotPaths = experiment.snapshots().stream().map(Loaded::path).collect(Collectors.toSet());
        for (Loaded reference : evaluation.references) {
            if (snapshotPaths.contains(reference.path())) continue;
            JsonNode snapshotId = reference.json().get("snapshotId");
            if (snapshotId != null && snapshotId.isIntegralNumber() && experiment.ids().contains(snapshotId.longValue())) continue;
            problems.add(prefix + " [experiment]: the check reads " + reference.written() + ", expected one of the snapshots of " + experiment.file() + " (snapshots "
                    + experiment.ids().get(0) + " and " + experiment.ids().get(1) + ") or an evidence report of one of them");
        }
        return problems;
    }

    private Evaluation evaluateCheck(Claim claim, String prefix) {
        JsonNode check = claim.check();
        String type = check.path("type").asString();
        Evaluation evaluation = new Evaluation(prefix + " [" + type + "] ");
        try {
            evaluation.sentence = switch (type) {
                case "rank" -> rank(claim, check, evaluation);
                case "topK" -> topK(claim, check, evaluation);
                case "metric" -> metric(claim, check, evaluation);
                case "ruleRow" -> ruleRow(claim, check, evaluation);
                case "phraseSpan", "membership" -> occurrence(claim, check, type, evaluation);
                case "candidate" -> candidate(claim, check, evaluation);
                case "bestFusedPosition" -> bestFusedPosition(claim, check, evaluation);
                case "candidateRecall" -> candidateRecall(claim, check, evaluation);
                case "removedAccepted" -> removedAccepted(claim, check, evaluation);
                case "blend" -> blend(claim, check, evaluation);
                case "candidateLists" -> candidateLists(claim, check, evaluation);
                case "diagnosticLine" -> diagnosticLine(claim, check, evaluation);
                case "diagnosticCount" -> diagnosticCount(claim, check, evaluation);
                case "sizeTable" -> sizeTable(claim, check, evaluation);
                case "sizeChoice" -> sizeChoice(claim, check, evaluation);
                case "matchedChunk" -> matchedChunk(claim, check, evaluation);
                case "rankHistogram" -> rankHistogram(claim, check, evaluation);
                case "rankedAbove" -> rankedAbove(claim, check, evaluation);
                case "fileValue" -> fileValue(claim, check, evaluation);
                case "heldPhrases" -> heldPhrases(claim, check, evaluation);
                case "subsetMetric" -> subsetMetric(claim, check, evaluation);
                case "questionEquality" -> questionEquality(claim, check, evaluation);
                case "notRecorded" -> notRecorded(check, evaluation);
                default -> throw new IllegalStateException(type);
            };
        } catch (Unreadable | IllegalArgumentException unreadable) {
            evaluation.fail(unreadable.getMessage());
            evaluation.sentence = null;
        }
        return evaluation;
    }

    /** Collects a check's differences into one problem line naming the subject, the files it read, and the sentence it renders. */
    private static final class Evaluation {
        private final String prefix;
        private String subject = "";
        private final List<String> details = new ArrayList<>();
        private final List<Loaded> references = new ArrayList<>();
        private String sentence;

        Evaluation(String prefix) {
            this.prefix = prefix;
        }

        void subject(String subject) {
            this.subject = subject;
        }

        void differs(String field, String expected, String found) {
            details.add(field + " expected " + expected + ", found " + found);
        }

        void fail(String detail) {
            details.add(detail);
        }

        List<String> problems() {
            if (details.isEmpty()) return List.of();
            return List.of(prefix.strip() + (subject.isEmpty() ? "" : " " + subject) + ": " + String.join("; ", details));
        }
    }

    /** A referenced file or value that cannot be read; ends the check with its message. */
    private static final class Unreadable extends RuntimeException {
        Unreadable(String message) {
            super(message);
        }
    }

    // Template (RAG.md, Claims, Sentences): "<Snapshot> records chunk <id> as the matched chunk of <question>." | "<Snapshot> records no matched
    // chunk for <question> (no matching chunk in the window)." | "<Snapshot> records a retrieval error for <question> and no matched chunk."
    /** The question's stored matchedChunkId (since 2026-09-17, plan 2026-09-17-chunk-size.md Milestone 3), an integer or null. */
    private String matchedChunk(Claim claim, JsonNode check, Evaluation evaluation) {
        String snapshotFile = requireText(check, "snapshot");
        String question = requireText(check, "question");
        evaluation.subject("matched chunk of " + question + " in " + snapshotFile);
        JsonNode expected = check.get("expected");
        if (expected == null || !(expected.isNull() || expected.isIntegralNumber())) {
            throw new Unreadable("check.expected must be a chunk id (integer) or null, found " + (expected == null ? "none" : expected));
        }
        Loaded loaded = load(snapshotFile, directory, evaluation);
        Snapshot snapshot = Snapshot.of(snapshotFile, loaded.json());
        Long found = snapshot.matchedChunkId(question);
        Long wanted = expected.isNull() ? null : expected.longValue();
        if (!Objects.equals(found, wanted)) evaluation.differs("matchedChunkId", String.valueOf(wanted), String.valueOf(found));
        basis(claim, "observed", evaluation);
        String reference = capitalize(snapshotReference(loaded, snapshot, null));
        if (found != null) return reference + " records chunk " + found + " as the matched chunk of " + question + ".";
        if (snapshot.error(question) != null) return reference + " records a retrieval error for " + question + " and no matched chunk.";
        return reference + " records no matched chunk for " + question + " (no matching chunk in the window).";
    }

    // Template: "In <Snapshot>, of the <total> questions <n> rank 1st, <n> 2nd, ..., and <n> rank outside the window[ of <w> results] (no matching
    // chunk[, <e> with a retrieval error])." (only ranks that occur, ascending; with no question outside, the last clause is "and none ranks outside
    // the window[ of <w> results]").
    /**
     * The rank histogram of a snapshot (since 2026-09-17): how many questions are stored at each rank and how many have no rank. {@code expected}
     * is an object whose keys are ranks (positive integers as strings) or {@code notInWindow}, each a non-negative integer; a key absent from it
     * counts as 0, and every rank that occurs must be listed, so the claim states the whole histogram.
     */
    private String rankHistogram(Claim claim, JsonNode check, Evaluation evaluation) {
        String snapshotFile = requireText(check, "snapshot");
        evaluation.subject("rank histogram of " + snapshotFile);
        JsonNode expected = check.get("expected");
        if (expected == null || !expected.isObject()) {
            throw new Unreadable("check.expected must be an object of counts keyed by rank or notInWindow, found " + (expected == null ? "none" : abbreviate(expected.toString())));
        }
        Map<String, Integer> wanted = new TreeMap<>(RANK_KEYS);
        for (Map.Entry<String, JsonNode> entry : expected.properties()) {
            String key = entry.getKey();
            if (!(key.equals("notInWindow") || key.matches("[1-9]\\d*"))) throw new Unreadable("check.expected key \"" + key + "\" is neither a rank nor notInWindow");
            if (!entry.getValue().isIntegralNumber() || entry.getValue().intValue() < 0) throw new Unreadable("check.expected." + key + " must be a non-negative count, found " + entry.getValue());
            wanted.put(key, entry.getValue().intValue());
        }
        Loaded loaded = load(snapshotFile, directory, evaluation);
        Snapshot snapshot = Snapshot.of(snapshotFile, loaded.json());
        Map<String, Integer> found = new TreeMap<>(RANK_KEYS);
        int total = 0;
        int errors = 0;
        for (JsonNode question : snapshot.questions()) {
            total++;
            JsonNode rank = question.get("rank");
            String key = rank == null || rank.isNull() ? "notInWindow" : rank.isIntegralNumber() && rank.intValue() > 0 ? rank.asString() : null;
            if (key == null) throw new Unreadable("question " + question.path("id").asString("(no id)") + " records a rank that is not a positive integer: " + rank);
            found.merge(key, 1, Integer::sum);
            JsonNode error = question.get("error");
            if (key.equals("notInWindow") && error != null && !error.isNull()) errors++;
        }
        Set<String> keys = new TreeSet<>(RANK_KEYS);
        keys.addAll(wanted.keySet());
        keys.addAll(found.keySet());
        for (String key : keys) {
            int w = wanted.getOrDefault(key, 0);
            int f = found.getOrDefault(key, 0);
            if (w != f) evaluation.differs(key.equals("notInWindow") ? "not in the window" : "rank " + key, String.valueOf(w), String.valueOf(f));
        }
        basis(claim, "derived", evaluation);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : found.entrySet()) {
            if (entry.getKey().equals("notInWindow")) continue;
            parts.add(entry.getValue() + " rank " + ordinal(Integer.parseInt(entry.getKey())));
        }
        int outside = found.getOrDefault("notInWindow", 0);
        String window = snapshot.windowSize() == null ? "" : " of " + snapshot.windowSize() + " results";
        parts.add(outside == 0 ? "none ranks outside the window" + window
                : outside + " rank outside the window" + window + " (no matching chunk" + (errors == 0 ? "" : ", " + errors + " with a retrieval error") + ")");
        return "In " + snapshotReference(loaded, snapshot, null) + ", of the " + total + " questions " + joinAnd(parts) + ".";
    }

    // Template (RAG.md, Claims, Sentences): "From the stored ranks of <Snapshot>, <name> over the <n> questions <ids> is <value> (<c> ranked 1 to <k>)."
    // for hit@1, hit@3, hit@5 ("ranked 1st" for hit@1), and "From the stored ranks of <Snapshot>, MRR over the <n> questions <ids> is <value>."
    /**
     * A metric over a listed subset of a snapshot's questions, computed from their stored ranks (since 2026-09-17, plan
     * 2026-09-17-chunk-size-pool.md Milestone 1: the tuning and held-out questions of a split). {@code metric} is hitAt1, hitAt3, hitAt5, or mrr,
     * computed as RetrievalEvaluationService computes them (questions ranked 1 to k divided by the listed questions, half up to six places; each
     * 1/rank half up to twelve places, their sum divided by the listed questions half up to six places, a null rank counting 0). The check reads
     * stored ranks only; which split, slice, or ticker the list stands for is not read.
     */
    private String subsetMetric(Claim claim, JsonNode check, Evaluation evaluation) {
        String snapshotFile = requireText(check, "snapshot");
        JsonNode questionsNode = check.get("questions");
        if (questionsNode == null || !questionsNode.isArray() || questionsNode.isEmpty()) {
            throw new Unreadable("check.questions must list at least one question id, found " + questionsNode);
        }
        List<String> questions = new ArrayList<>();
        for (JsonNode q : questionsNode) {
            if (!q.isString() || q.stringValue().isBlank() || questions.contains(q.stringValue())) {
                throw new Unreadable("check.questions must list distinct non-blank question ids, found " + abbreviate(questionsNode.toString()));
            }
            questions.add(q.stringValue());
        }
        String metric = requireText(check, "metric");
        if (!Set.of("hitAt1", "hitAt3", "hitAt5", "mrr").contains(metric)) throw new Unreadable("check.metric must be hitAt1, hitAt3, hitAt5, or mrr, found " + quote(metric));
        BigDecimal expected = scale6(check.get("expected"));
        if (expected == null) throw new Unreadable("check.expected must be a number with at most six decimal places, found " + check.get("expected"));
        evaluation.subject(metric + " over " + questions.size() + (questions.size() == 1 ? " question" : " questions") + " of " + snapshotFile);
        Loaded loaded = load(snapshotFile, directory, evaluation);
        Snapshot snapshot = Snapshot.of(snapshotFile, loaded.json());
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (String question : questions) {
            JsonNode rank = snapshot.question(question).get("rank");
            if (rank != null && !rank.isNull() && !(rank.isIntegralNumber() && rank.intValue() > 0)) {
                throw new Unreadable("question " + question + " records a rank that is not a positive integer: " + rank);
            }
            ranks.put(question, snapshot.rank(question));
        }
        basis(claim, "derived", evaluation);
        String lead = "From the stored ranks of " + snapshotReference(loaded, snapshot, null) + ", ";
        String over = " over the " + questions.size() + (questions.size() == 1 ? " question " : " questions ") + joinAnd(questions) + " is ";
        if (metric.equals("mrr")) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Integer r : ranks.values()) {
                if (r != null) sum = sum.add(BigDecimal.ONE.divide(BigDecimal.valueOf(r), 12, RoundingMode.HALF_UP));
            }
            BigDecimal found = sum.divide(BigDecimal.valueOf(questions.size()), 6, RoundingMode.HALF_UP);
            if (found.compareTo(expected) != 0) {
                List<String> listed = new ArrayList<>();
                ranks.forEach((q, r) -> listed.add(q + " " + rankText(r)));
                evaluation.differs("MRR", expected.toPlainString(), found.toPlainString() + " (" + String.join(", ", listed) + ")");
            }
            return lead + "MRR" + over + found.toPlainString() + ".";
        }
        int k = Integer.parseInt(metric.substring("hitAt".length()));
        long inside = ranks.values().stream().filter(r -> r != null && r <= k).count();
        BigDecimal found = BigDecimal.valueOf(inside).divide(BigDecimal.valueOf(questions.size()), 6, RoundingMode.HALF_UP);
        if (found.compareTo(expected) != 0) {
            List<String> outside = new ArrayList<>();
            ranks.forEach((q, r) -> { if (r == null || r > k) outside.add(q + " (" + rankText(r) + ")"); });
            evaluation.differs(metricName(metric), expected.toPlainString(), found.toPlainString() + " (" + inside + " of " + questions.size() + "; outside the top " + k + ": "
                    + (outside.isEmpty() ? "none" : String.join(", ", outside)) + ")");
        }
        return lead + metricName(metric) + over + found.toPlainString() + " (" + inside + (k == 1 ? " ranked 1st)." : " ranked 1 to " + k + ")."); 
    }

    // Template (RAG.md, Claims, Sentences): "Between <reference Snapshot> and <candidate Snapshot>, <what> is (are) identical for each of the <n>
    // questions." | "..., <what> differs (differ) for <m> of the <n> questions: <question> (<detail>), ...." with <what> "the stored rank", "the
    // stored rank and matched chunk id", or "the stored rank and the matched chunk's filing, chunk index, length, and content md5 (from <File> and
    // <File>)", and a detail naming both sides' rank, matched chunk id, or matched chunk entry.
    /**
     * Whether two snapshots store the same result per question (since 2026-09-17, plan 2026-09-17-chunk-size-pool.md Milestone 1, remediation
     * round 1: G2 and G8 state a per-question equality). {@code compare} {@code rank}: the stored ranks; {@code rankAndMatchedChunk}: the stored
     * ranks and matched chunk ids (two runs on one store); {@code rankAndMatchedContent}: the stored ranks and, for a question with a matched
     * chunk, the entries of the two matched chunks in the chunk-hash exports {@code referenceChunks} and {@code candidateChunks} (JSON arrays of
     * objects with {@code id}, {@code filingId}, {@code chunkIndex}, {@code chars}, and {@code contentMd5}, written by chunk-hashes.sql from the
     * store each run retrieved from), compared by those four values, for two runs on stores whose chunk ids differ. {@code expected} lists the
     * distinct questions that differ (empty for none); true when exactly those differ. Unreadable: the snapshots list different questions, a
     * question with a retrieval error, a rank that is not a positive integer, a rank without a matched chunk id or the reverse, the two export
     * keys given with another compare or missing with rankAndMatchedContent, a file that is not such an export, or a matched chunk absent from
     * its export. The check reads no chunk text: equal content rests on the md5 the export records.
     */
    private String questionEquality(Claim claim, JsonNode check, Evaluation evaluation) {
        String referenceFile = requireText(check, "reference");
        String candidateFile = requireText(check, "candidate");
        String compare = requireText(check, "compare");
        if (!Set.of("rank", "rankAndMatchedChunk", "rankAndMatchedContent").contains(compare)) {
            throw new Unreadable("check.compare must be rank, rankAndMatchedChunk, or rankAndMatchedContent, found " + quote(compare));
        }
        boolean content = compare.equals("rankAndMatchedContent");
        if (content != (check.has("referenceChunks") && check.has("candidateChunks")) || (!content && (check.has("referenceChunks") || check.has("candidateChunks")))) {
            throw new Unreadable("check.referenceChunks and check.candidateChunks are required with compare rankAndMatchedContent and refused otherwise");
        }
        JsonNode expected = check.get("expected");
        List<String> wanted = new ArrayList<>();
        boolean listed = expected != null && expected.isArray();
        if (listed) {
            for (JsonNode id : expected) {
                if (!id.isString() || id.stringValue().isBlank() || wanted.contains(id.stringValue())) listed = false;
                else wanted.add(id.stringValue());
            }
        }
        if (!listed) {
            throw new Unreadable("check.expected must list the distinct question ids that differ (an empty list for none), found "
                    + (expected == null ? "none" : abbreviate(expected.toString())));
        }
        evaluation.subject(compare + " of " + candidateFile + " against " + referenceFile);
        Loaded referenceLoaded = load(referenceFile, directory, evaluation);
        Snapshot reference = Snapshot.of(referenceFile, referenceLoaded.json());
        Loaded candidateLoaded = load(candidateFile, directory, evaluation);
        Snapshot candidate = Snapshot.of(candidateFile, candidateLoaded.json());
        if (referenceLoaded.path().equals(candidateLoaded.path())) throw new Unreadable("check.reference and check.candidate name the same file " + referenceFile);
        List<String> questions = new ArrayList<>();
        reference.questions().forEach(q -> questions.add(q.path("id").asString("")));
        List<String> candidateQuestions = new ArrayList<>();
        candidate.questions().forEach(q -> candidateQuestions.add(q.path("id").asString("")));
        if (!new HashSet<>(questions).equals(new HashSet<>(candidateQuestions)) || questions.size() != candidateQuestions.size()) {
            throw new Unreadable(referenceFile + " and " + candidateFile + " list different questions");
        }
        for (String id : wanted) {
            if (!questions.contains(id)) throw new Unreadable("check.expected lists " + id + ", which neither snapshot has");
        }
        Loaded referenceChunks = null;
        Loaded candidateChunks = null;
        Map<Long, String> referenceEntries = null;
        Map<Long, String> candidateEntries = null;
        if (content) {
            String referenceChunksFile = requireText(check, "referenceChunks");
            String candidateChunksFile = requireText(check, "candidateChunks");
            referenceChunks = load(referenceChunksFile, directory, evaluation);
            candidateChunks = load(candidateChunksFile, directory, evaluation);
            referenceEntries = hashEntries(referenceChunks, referenceChunksFile);
            candidateEntries = hashEntries(candidateChunks, candidateChunksFile);
        }
        List<String> differing = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (String question : questions) {
            Integer a = storedRank(reference, question, referenceFile);
            Integer b = storedRank(candidate, question, candidateFile);
            Long chunkA = reference.matchedChunkId(question);
            Long chunkB = candidate.matchedChunkId(question);
            if ((a == null) != (chunkA == null)) throw new Unreadable("question " + question + " records " + rankText(a) + " and matched chunk " + chunkA + " in " + referenceFile);
            if ((b == null) != (chunkB == null)) throw new Unreadable("question " + question + " records " + rankText(b) + " and matched chunk " + chunkB + " in " + candidateFile);
            String detail = null;
            if (!Objects.equals(a, b)) {
                detail = rankText(a) + " in snapshot " + reference.id() + ", " + rankText(b) + " in snapshot " + candidate.id();
            } else if (a != null && compare.equals("rankAndMatchedChunk") && !chunkA.equals(chunkB)) {
                detail = "matched chunk " + chunkA + " in snapshot " + reference.id() + ", " + chunkB + " in snapshot " + candidate.id();
            } else if (a != null && content) {
                String entryA = referenceEntries.get(chunkA);
                String entryB = candidateEntries.get(chunkB);
                if (entryA == null) throw new Unreadable("matched chunk " + chunkA + " of " + question + " is not in the chunk-hash export " + check.get("referenceChunks").stringValue());
                if (entryB == null) throw new Unreadable("matched chunk " + chunkB + " of " + question + " is not in the chunk-hash export " + check.get("candidateChunks").stringValue());
                if (!entryA.equals(entryB)) detail = "matched chunk " + chunkA + " is " + entryA + " in snapshot " + reference.id() + ", matched chunk " + chunkB + " is " + entryB + " in snapshot " + candidate.id();
            }
            if (detail != null) {
                differing.add(question);
                details.add(question + " (" + detail + ")");
            }
        }
        if (!new HashSet<>(wanted).equals(new HashSet<>(differing))) {
            evaluation.differs("questions that differ", wanted.isEmpty() ? "none" : String.join(", ", wanted), differing.isEmpty() ? "none" : String.join(", ", details));
        }
        basis(claim, "derived", evaluation);
        String what = switch (compare) {
            case "rank" -> "the stored rank";
            case "rankAndMatchedChunk" -> "the stored rank and matched chunk id";
            default -> "the stored rank and the matched chunk's filing, chunk index, length, and content md5 (from " + fileReference(referenceChunks, "the chunk-hash export")
                    + " and " + fileReference(candidateChunks, "the chunk-hash export") + ")";
        };
        boolean plural = !compare.equals("rank");
        String lead = "Between " + snapshotReference(referenceLoaded, reference, null) + " and " + snapshotReference(candidateLoaded, candidate, null) + ", " + what;
        return differing.isEmpty() ? lead + (plural ? " are" : " is") + " identical for each of the " + questions.size() + " questions."
                : lead + (plural ? " differ" : " differs") + " for " + differing.size() + " of the " + questions.size() + " questions: " + String.join(", ", details) + ".";
    }

    /** The stored rank of a question for questionEquality: null or a positive integer, and no retrieval error. */
    private static Integer storedRank(Snapshot snapshot, String question, String file) {
        if (snapshot.error(question) != null) throw new Unreadable("question " + question + " records a retrieval error in " + file + ": " + snapshot.error(question));
        JsonNode rank = snapshot.question(question).get("rank");
        if (rank != null && !rank.isNull() && !(rank.isIntegralNumber() && rank.intValue() > 0)) {
            throw new Unreadable("question " + question + " records a rank that is not a positive integer in " + file + ": " + rank);
        }
        return snapshot.rank(question);
    }

    /** Chunk id to "filing <f> chunk <i>, <n> characters, md5 <h>" of a chunk-hash export. */
    private static Map<Long, String> hashEntries(Loaded loaded, String file) {
        if (!loaded.json().isArray()) throw new Unreadable(file + " is not a chunk-hash export (a JSON array of chunks)");
        Map<Long, String> entries = new HashMap<>();
        for (JsonNode chunk : loaded.json()) {
            JsonNode id = chunk.get("id");
            JsonNode filing = chunk.get("filingId");
            JsonNode index = chunk.get("chunkIndex");
            JsonNode chars = chunk.get("chars");
            JsonNode md5 = chunk.get("contentMd5");
            if (id == null || !id.isIntegralNumber() || filing == null || !filing.isIntegralNumber() || index == null || !index.isIntegralNumber()
                    || chars == null || !chars.isIntegralNumber() || md5 == null || !md5.isString() || md5.stringValue().isBlank()) {
                throw new Unreadable(file + " holds a chunk without an integer id, filingId, chunkIndex, and chars and a contentMd5: " + abbreviate(chunk.toString()));
            }
            if (entries.put(id.longValue(), "filing " + filing.longValue() + " chunk " + index.longValue() + ", " + chars.longValue() + " characters, md5 " + md5.stringValue()) != null) {
                throw new Unreadable(file + " lists chunk " + id.longValue() + " twice");
            }
        }
        return entries;
    }

    /** Rank keys in numeric order with notInWindow last. */
    private static final Comparator<String> RANK_KEYS = (a, b) -> {
        if (a.equals(b)) return 0;
        if (a.equals("notInWindow")) return 1;
        if (b.equals("notInWindow")) return -1;
        return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
    };

    // Template: "In <Snapshot>, the <n> chunks returned above <question>'s matched chunk <m> (rank <r>) are chunk <id> (<sectionKey>, "<head>"),
    // ...; sections and text from <Chunks>." | "In <Snapshot>, no chunk is returned above <question>'s matched chunk <m> (rank 1)." | "In <Snapshot>,
    // the <n> chunks returned for <question>, none of them a matched chunk, are ...; sections and text from <Chunks>." ("chunk" and "is" for one)
    /**
     * The chunks returned above a question's matched chunk (since 2026-09-17, plan 2026-09-17-chunk-size.md Milestone 3, F7): from the
     * question's trace, its {@code returnedChunkIds} before the stored rank (all of them when the rank is null), each joined by id to the
     * committed chunk export {@code chunks} (a JSON array of objects with {@code id}, {@code sectionKey}, and {@code head}, the first characters
     * of the stored text, written by chunks.sql from the store the run retrieved from) for its section and text. {@code expected} lists the
     * chunk ids in returned order (empty for a question at rank 1); true when equal. Unreadable when the question has no trace or returned
     * list, the returned chunk at the stored rank is not the matched chunk, or a returned chunk is absent from the export. The check reads no
     * score and states nothing about why a chunk ranks where it does.
     */
    private String rankedAbove(Claim claim, JsonNode check, Evaluation evaluation) {
        String snapshotFile = requireText(check, "snapshot");
        String question = requireText(check, "question");
        String chunksFile = requireText(check, "chunks");
        evaluation.subject("chunks returned above the matched chunk of " + question + " in " + snapshotFile);
        JsonNode expected = check.get("expected");
        if (expected == null || !expected.isArray()) throw new Unreadable("check.expected must be a list of chunk ids in returned order, found " + (expected == null ? "none" : abbreviate(expected.toString())));
        List<Long> wanted = new ArrayList<>();
        for (JsonNode id : expected) {
            if (!id.isIntegralNumber()) throw new Unreadable("check.expected must list chunk ids (integers), found " + id);
            wanted.add(id.longValue());
        }
        Loaded loaded = load(snapshotFile, directory, evaluation);
        Snapshot snapshot = Snapshot.of(snapshotFile, loaded.json());
        Loaded chunks = load(chunksFile, directory, evaluation);
        if (!chunks.json().isArray()) throw new Unreadable(chunksFile + " is not a chunk export (a JSON array of chunks)");
        Map<Long, JsonNode> byId = new HashMap<>();
        for (JsonNode chunk : chunks.json()) {
            JsonNode id = chunk.get("id");
            if (id == null || !id.isIntegralNumber()) throw new Unreadable(chunksFile + " holds a chunk without an integer id: " + abbreviate(chunk.toString()));
            byId.put(id.longValue(), chunk);
        }
        Integer rank = snapshot.rank(question);
        Long matched = snapshot.matchedChunkId(question);
        if (snapshot.error(question) != null) throw new Unreadable("question " + question + " records a retrieval error: " + snapshot.error(question));
        JsonNode trace = snapshot.trace(question);
        if (trace == null) throw new Unreadable("question " + question + " has no trace in " + snapshotFile);
        JsonNode returned = trace.get("returnedChunkIds");
        if (returned == null || !returned.isArray()) throw new Unreadable("the trace of " + question + " records no returnedChunkIds list");
        List<Long> returnedIds = new ArrayList<>();
        for (JsonNode id : returned) {
            if (!id.isIntegralNumber()) throw new Unreadable("the trace of " + question + " records a returned chunk id that is not an integer: " + id);
            returnedIds.add(id.longValue());
        }
        int above = rank == null ? returnedIds.size() : rank - 1;
        if (rank != null && (rank > returnedIds.size() || !Objects.equals(returnedIds.get(rank - 1), matched))) {
            throw new Unreadable("question " + question + " records rank " + rank + " and matched chunk " + matched + ", but the trace returns "
                    + (rank > returnedIds.size() ? "only " + returnedIds.size() + " chunks" : "chunk " + returnedIds.get(rank - 1) + " at that rank"));
        }
        List<Long> foundIds = returnedIds.subList(0, above);
        if (!foundIds.equals(wanted)) evaluation.differs("chunks above", wanted.toString(), foundIds.toString());
        List<String> parts = new ArrayList<>();
        for (Long id : foundIds) {
            JsonNode chunk = byId.get(id);
            if (chunk == null) throw new Unreadable("returned chunk " + id + " is not in the chunk export " + chunksFile);
            parts.add("chunk " + id + " (" + chunk.path("sectionKey").asString("no section") + ", \"" + chunk.path("head").asString("") + "\")");
        }
        basis(claim, "derived", evaluation);
        String reference = snapshotReference(loaded, snapshot, null);
        String source = "; sections and text from " + fileReference(chunks, "the chunk export");
        if (rank != null && above == 0) return "In " + reference + ", no chunk is returned above " + question + "'s matched chunk " + matched + " (rank 1).";
        if (rank == null) {
            return "In " + reference + ", the " + counted(foundIds.size(), "chunk") + " returned for " + question + ", none of them a matched chunk, "
                    + (foundIds.size() == 1 ? "is " : "are ") + joinAnd(parts) + source + ".";
        }
        return "In " + reference + ", the " + counted(foundIds.size(), "chunk") + " returned above " + question + "'s matched chunk " + matched + " (rank " + rank + ") "
                + (foundIds.size() == 1 ? "is " : "are ") + joinAnd(parts) + source + ".";
    }

    private static String counted(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /** "<what> <path as written>" or "<label> (<what> <path as written>)". */
    private String fileReference(Loaded loaded, String what) {
        String label = labels.get(loaded.path());
        String reference = what + " " + loaded.written();
        return label == null ? reference : label + " (" + reference + ")";
    }

    // Template: "In <File>, <path> is <value>." with <File> "the file <path as written>" or "<label> (the file <path as written>)" and the value
    // as render writes it (a string without quotes, a list with brackets, null as null).
    /**
     * One value of a committed JSON file by path (since 2026-09-17, plan 2026-09-17-chunk-size.md Milestone 3, F8 and F9): {@code path} as
     * ReportPath reads it; {@code expected} any JSON value, compared as sameValue compares (numbers by value, lists element by element). A path
     * naming nothing is unreadable. The check reads the file as it is on disk and states nothing about how the value was produced; the file's
     * own description and the run log say that.
     */
    private String fileValue(Claim claim, JsonNode check, Evaluation evaluation) {
        String file = requireText(check, "file");
        String path = requireText(check, "path");
        evaluation.subject(path + " in " + file);
        if (!check.has("expected")) throw new Unreadable("check.expected is required (the value the file holds at the path)");
        JsonNode expected = check.get("expected");
        Loaded loaded = load(file, directory, evaluation);
        JsonNode found = ReportPath.resolve(loaded.json(), path);
        if (found == null) throw new Unreadable("path " + path + " names nothing in " + file);
        if (!sameValue(expected, found)) evaluation.differs("value", shown(expected, found), shown(found, expected));
        basis(claim, "observed", evaluation);
        return "In " + fileReference(loaded, "the file") + ", " + path + " is " + render(found) + ".";
    }

    // Template: "In <Report>, <held> of the <phrases> accepted phrases are held by a stored chunk[; not held: <question> "<phrase>", ...]."
    /**
     * The R1 gate of plan 2026-09-17-chunk-size.md (since 2026-09-17): over every accepted phrase of every question of an evidence report,
     * how many record {@code heldByStoredChunk} observed true. {@code expected: {phrases, held}}, both non-negative integers; true when both
     * counts equal. A phrase whose value is not an observed boolean makes the claim unreadable, naming it, so an unknown is never counted as
     * held or as not held. The report evaluates the rule against the store at report time (Evidence report, Limits), which the run log
     * ties to the store the run retrieved from.
     */
    private String heldPhrases(Claim claim, JsonNode check, Evaluation evaluation) {
        String reportFile = requireText(check, "report");
        evaluation.subject("accepted phrases held by a stored chunk in " + reportFile);
        JsonNode expected = expected(check, "heldPhrases");
        for (String key : EXPECTED_KEYS.get("heldPhrases")) {
            JsonNode value = expected.get(key);
            if (value == null || !value.isIntegralNumber() || value.intValue() < 0) throw new Unreadable("check.expected." + key + " must be a non-negative count, found " + value);
        }
        for (String key : expected.propertyNames()) {
            if (!EXPECTED_KEYS.get("heldPhrases").contains(key)) throw new Unreadable("unknown expected field \"" + key + "\" (one of phrases, held)");
        }
        Loaded report = report(reportFile, evaluation);
        int phrases = 0;
        int held = 0;
        List<String> notHeld = new ArrayList<>();
        for (JsonNode question : report.json().get("questions")) {
            String id = question.path("id").asString("(no id)");
            JsonNode list = question.get("phrases");
            if (list == null || !list.isArray()) throw new Unreadable("question " + id + " lists no phrases");
            for (JsonNode phrase : list) {
                phrases++;
                JsonNode value = phrase.get("heldByStoredChunk");
                if (value == null || !value.isObject() || !"observed".equals(value.path("basis").asString(null)) || !value.path("value").isBoolean()) {
                    throw new Unreadable("question " + id + " phrase \"" + phrase.path("phrase").asString("") + "\": heldByStoredChunk is not an observed boolean ("
                            + (value == null ? "not in the report" : abbreviate(value.toString())) + ")");
                }
                if (value.get("value").booleanValue()) held++;
                else notHeld.add(id + " \"" + phrase.path("phrase").asString("") + "\"");
            }
        }
        if (phrases != expected.get("phrases").intValue()) evaluation.differs("phrases", expected.get("phrases").asString(), String.valueOf(phrases));
        if (held != expected.get("held").intValue()) evaluation.differs("held", expected.get("held").asString(), String.valueOf(held) + (notHeld.isEmpty() ? "" : " (not held: " + String.join(", ", notHeld) + ")"));
        basis(claim, "derived", evaluation);
        return "In " + reportReference(report) + ", " + held + " of the " + phrases + " accepted phrases are held by a stored chunk" + (notHeld.isEmpty() ? "" : "; not held: " + String.join(", ", notHeld)) + ".";
    }

    // Template (RAG.md, Claims, Sentences): "<Snapshot> ranks <question> <ordinal>." | "<Snapshot> ranks <question> outside its window[ of <n>
    // results] (no matching chunk)." (the window size only when the snapshot records it) | "<Snapshot> records a retrieval error for <question>
    // and no rank."
    private String rank(Claim claim, JsonNode check, Evaluation evaluation) {
        String snapshotFile = requireText(check, "snapshot");
        String question = requireText(check, "question");
        JsonNode expected = check.get("expected");
        if (expected == null || !(expected.isNull() || expected.isIntegralNumber())) {
            throw new Unreadable("check.expected must be a rank (integer) or null, found " + (expected == null ? "none" : expected));
        }
        evaluation.subject("question " + question + " in " + snapshotFile);
        Loaded loaded = load(snapshotFile, directory, evaluation);
        Snapshot snapshot = Snapshot.of(snapshotFile, loaded.json());
        Integer found = snapshot.rank(question);
        Integer wanted = expected.isNull() ? null : expected.intValue();
        if (!Objects.equals(found, wanted)) evaluation.differs("rank", String.valueOf(wanted), String.valueOf(found));
        basis(claim, "observed", evaluation);
        String reference = capitalize(snapshotReference(loaded, snapshot, null));
        if (found != null) return reference + " ranks " + question + " " + ordinal(found) + ".";
        if (snapshot.error(question) != null) return reference + " records a retrieval error for " + question + " and no rank.";
        return reference + " ranks " + question + " outside its window" + (snapshot.windowSize() == null ? "" : " of " + snapshot.windowSize() + " results")
                + " (no matching chunk).";
    }

    // Template: "<question> is <inside|outside> the top <k> in <Snapshot (rank detail)>[, <Snapshot (rank detail)>, and <Snapshot (rank
    // detail)>][, and <inside|outside> the top <k> in ...]." Every listed row is named once, consecutive rows with the same outcome grouped;
    // never "every".
    private String topK(Claim claim, JsonNode check, Evaluation evaluation) {
        String question = requireText(check, "question");
        JsonNode k = check.get("k");
        if (k == null || !k.isIntegralNumber() || k.intValue() < 1) throw new Unreadable("check.k must be a positive integer, found " + k);
        JsonNode rows = check.get("rows");
        if (rows == null || !rows.isArray() || rows.isEmpty()) throw new Unreadable("check.rows must list at least one {snapshot, expected}, found " + rows);
        evaluation.subject("question " + question + ", k " + k.intValue());
        Set<Path> seen = new HashSet<>();
        List<String> outcomes = new ArrayList<>();
        List<String> references = new ArrayList<>();
        for (JsonNode row : rows) {
            String snapshotFile = requireText(row, "snapshot");
            String expected = requireText(row, "expected");
            if (!expected.equals("inside") && !expected.equals("outside")) throw new Unreadable("rows expected must be inside or outside, found " + expected);
            Loaded loaded = load(snapshotFile, directory, evaluation);
            if (!seen.add(loaded.path())) throw new Unreadable("rows list " + snapshotFile + " more than once");
            Snapshot snapshot = Snapshot.of(snapshotFile, loaded.json());
            Integer rank = snapshot.rank(question);
            String found = rank != null && rank <= k.intValue() ? "inside" : "outside";
            if (!found.equals(expected)) evaluation.differs(snapshotFile, expected, found + " (" + rankText(rank) + ")");
            outcomes.add(found);
            String detail = rank != null ? "rank " + rank : snapshot.error(question) != null ? "retrieval error, no rank" : "no matching chunk in the window";
            references.add(snapshotReference(loaded, snapshot, detail));
        }
        basis(claim, "derived", evaluation);
        List<String> groups = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= outcomes.size(); i++) {
            if (i == outcomes.size() || !outcomes.get(i).equals(outcomes.get(start))) {
                groups.add(outcomes.get(start) + " the top " + k.intValue() + " in " + joinAnd(references.subList(start, i)));
                start = i;
            }
        }
        return question + " is " + (groups.size() == 2 ? groups.get(0) + ", and " + groups.get(1) : joinAnd(groups)) + ".";
    }

    // Template: "The <metric name> of <Snapshot> is <value at six decimals>." | "The <metric name> of <Snapshot> is not recorded."
    private String metric(Claim claim, JsonNode check, Evaluation evaluation) {
        String snapshotFile = requireText(check, "snapshot");
        String metric = requireText(check, "metric");
        if (!METRIC.matcher(metric).matches()) {
            throw new Unreadable("check.metric must be hitAt1, hitAt3, hitAt5, mrr, slices.<figure|nonFigure>.<metric>, or tickerHitAt5.<TICKER>, found " + metric);
        }
        JsonNode expectedNode = check.get("expected");
        BigDecimal expected = scale6(expectedNode);
        if (expected == null) throw new Unreadable("check.expected must be a number with at most six decimal places, found " + expectedNode);
        evaluation.subject(metric + " in " + snapshotFile);
        Loaded loaded = load(snapshotFile, directory, evaluation);
        Snapshot snapshot = Snapshot.of(snapshotFile, loaded.json());
        BigDecimal stored = snapshot.metric(metric);
        String reference = snapshotReference(loaded, snapshot, null);
        if (stored == null) {
            evaluation.differs("value", expected.toPlainString(), "none recorded");
            basis(claim, "observed", evaluation);
            return "The " + metricName(metric) + " of " + reference + " is not recorded.";
        }
        String found = six(stored, "stored " + metric);
        if (new BigDecimal(found).compareTo(expected) != 0) evaluation.differs("value", expected.toPlainString(), found);
        basis(claim, "observed", evaluation);
        return "The " + metricName(metric) + " of " + reference + " is " + found + ".";
    }

    // Template: "Against <Snapshot>, <Snapshot> <meets|does not meet> the <criterion> criterion (<deciding values>)[, and ...]."
    private String ruleRow(Claim claim, JsonNode check, Evaluation evaluation) {
        String referenceFile = requireText(check, "reference");
        String candidateFile = requireText(check, "candidate");
        JsonNode criteria = check.get("criteria");
        if (criteria == null || !criteria.isObject() || criteria.isEmpty()) {
            throw new Unreadable("check.criteria must map at least one of " + String.join(", ", CRITERIA) + " to pass or fail, found " + criteria);
        }
        evaluation.subject(candidateFile + " against " + referenceFile);
        Loaded referenceLoaded = load(referenceFile, directory, evaluation);
        Loaded candidateLoaded = load(candidateFile, directory, evaluation);
        Snapshot reference = Snapshot.of(referenceFile, referenceLoaded.json());
        Snapshot candidate = Snapshot.of(candidateFile, candidateLoaded.json());
        if (!Objects.equals(reference.setVersion(), candidate.setVersion()) || !Objects.equals(reference.questionCount(), candidate.questionCount())) {
            throw new Unreadable("snapshots expected the same set version and question count, found reference " + reference.setVersion() + "/"
                    + reference.questionCount() + " and candidate " + candidate.setVersion() + "/" + candidate.questionCount());
        }
        List<String> clauses = new ArrayList<>();
        for (Map.Entry<String, JsonNode> criterion : criteria.properties()) {
            String expected = criterion.getValue().isString() ? criterion.getValue().stringValue() : "";
            if (!expected.equals("pass") && !expected.equals("fail")) throw new Unreadable("criterion " + criterion.getKey() + " must be pass or fail, found " + criterion.getValue());
            Criterion outcome = criterion(criterion.getKey(), reference, candidate);
            String found = outcome.pass() ? "pass" : "fail";
            if (!found.equals(expected)) evaluation.differs(criterion.getKey(), expected, found + " (" + outcome.detail() + ")");
            clauses.add((outcome.pass() ? "meets" : "does not meet") + " the " + CRITERION_NAMES.get(criterion.getKey()) + " criterion (" + outcome.detail() + ")");
        }
        basis(claim, "derived", evaluation);
        return "Against " + snapshotReference(referenceLoaded, reference, null) + ", " + snapshotReference(candidateLoaded, candidate, null) + " "
                + joinAnd(clauses) + ".";
    }

    private record Criterion(boolean pass, String detail) {
    }

    /** Whether the candidate row meets the criterion against the reference, with the values that decide it; absent data fails. */
    private static Criterion criterion(String name, Snapshot reference, Snapshot candidate) {
        return switch (name) {
            case "aggregateHitAt5" -> notBelow("hit@5", reference.metric("hitAt5"), candidate.metric("hitAt5"));
            case "nonFigureHitAt5" -> notBelow("non-figure-slice hit@5", reference.metric("slices.nonFigure.hitAt5"), candidate.metric("slices.nonFigure.hitAt5"));
            case "tickerHitAt5" -> {
                if (!reference.tickerHitAt5().isObject() || reference.tickerHitAt5().isEmpty()) yield new Criterion(false, "the reference records no tickerHitAt5");
                List<String> below = new ArrayList<>();
                List<String> held = new ArrayList<>();
                for (String ticker : reference.tickers()) {
                    Criterion one = notBelow(ticker, reference.metric("tickerHitAt5." + ticker), candidate.metric("tickerHitAt5." + ticker));
                    (one.pass() ? held : below).add(one.detail());
                }
                int count = reference.tickers().size();
                yield below.isEmpty() ? new Criterion(true, "at or above the reference's for " + count + (count == 1 ? " ticker: " : " tickers: ") + String.join(", ", held))
                        : new Criterion(false, "below the reference's or not recorded: " + String.join(", ", below) + ", of " + count + (count == 1 ? " ticker" : " tickers"));
            }
            case "figureKindTop5" -> {
                List<String> noKind = reference.questionsWithoutKind();
                if (!noKind.isEmpty()) yield new Criterion(false, "the reference records no kind for " + String.join(", ", noKind));
                List<String> left = new ArrayList<>();
                List<String> stayed = new ArrayList<>();
                List<String> ids = reference.figureKindInTop5();
                for (String id : ids) {
                    Integer rank = candidate.rank(id);
                    String text = id + " (" + (rank == null ? "no rank" : "rank " + rank) + ", was " + reference.rank(id) + ")";
                    (rank == null || rank > 5 ? left : stayed).add(text);
                }
                if (ids.isEmpty()) yield new Criterion(true, "the reference has no FIGURE question in its top 5");
                yield left.isEmpty() ? new Criterion(true, "the reference's FIGURE questions in its top 5 stay in the top 5: " + String.join(", ", stayed))
                        : new Criterion(false, "left the top 5: " + String.join(", ", left));
            }
            default -> throw new IllegalStateException(name);
        };
    }

    private static Criterion notBelow(String label, BigDecimal reference, BigDecimal candidate) {
        if (reference == null || candidate == null) {
            return new Criterion(false, label + " not recorded (reference " + (reference == null ? "none" : six(reference, label)) + ", candidate "
                    + (candidate == null ? "none" : six(candidate, label)) + ")");
        }
        return new Criterion(candidate.compareTo(reference) >= 0, label + " " + six(candidate, label) + " against " + six(reference, label));
    }

    // Templates: "In <Report>, occurrence <n> of <total> of <question>'s accepted phrase "<phrase>" in chunk <chunk> spans <tokens [a, b)|tokens
    // not recorded (<reason>)>[ and <characters [c, d)|characters not recorded (<reason>)>]." and, for membership, "... <head>, and <rows>."
    // (head alone: "... <head>."; rows alone: "...: <rows>."), rows being "of the <1 row|m rows|rows> <scored for this chunk|the recorded
    // scoring would score for this chunk (not rows that were scored: <why>)>, <no row holds|row r holds|rows r and s hold> it wholly" or "the
    // rows holding it wholly are unknown (<reason>)". Every branch: RAG.md, Claims, Sentences.
    private String occurrence(Claim claim, JsonNode check, String type, Evaluation evaluation) {
        String reportFile = requireText(check, "report");
        String question = requireText(check, "question");
        String phrase = requireText(check, "phrase");
        long chunk = requireLong(check, "chunk");
        JsonNode occurrenceNode = check.get("occurrence");
        if (occurrenceNode != null && (!occurrenceNode.isIntegralNumber() || occurrenceNode.intValue() < 1)) {
            throw new Unreadable("check.occurrence must be a positive integer, found " + occurrenceNode);
        }
        int occurrence = occurrenceNode == null ? 1 : occurrenceNode.intValue();
        JsonNode expected = expected(check, type);
        evaluation.subject("question " + question + ", chunk " + chunk + ", phrase \"" + phrase + "\", occurrence " + occurrence + " in " + reportFile);
        Loaded report = report(reportFile, evaluation);
        JsonNode questionNode = reportQuestion(report, question);
        JsonNode chunkNode = null;
        boolean phraseFound = false;
        for (JsonNode phraseNode : questionNode.path("phrases")) {
            if (!phrase.equals(phraseNode.path("phrase").asString(null))) continue;
            phraseFound = true;
            for (JsonNode candidate : phraseNode.path("chunks")) {
                if (candidate.path("chunkId").asLong(-1) == chunk) chunkNode = candidate;
            }
        }
        if (!phraseFound) throw new Unreadable("question " + question + " has no accepted phrase \"" + phrase + "\" in the report");
        if (chunkNode == null) throw new Unreadable("the report lists no chunk " + chunk + " holding that phrase");
        JsonNode occurrences = chunkNode.path("occurrences");
        if (occurrences.size() < occurrence) throw new Unreadable("chunk " + chunk + " has " + occurrences.size() + " occurrences of the phrase, not " + occurrence);
        JsonNode owner = occurrences.get(occurrence - 1);
        compareValues(claim, owner, expected, evaluation);
        String prefix = "In " + reportReference(report) + ", occurrence " + occurrence + " of " + occurrences.size() + " of " + question + "'s accepted phrase \""
                + phrase + "\" in chunk " + chunk;
        if (type.equals("phraseSpan")) {
            List<String> parts = new ArrayList<>();
            for (String field : EXPECTED_KEYS.get("phraseSpan")) {
                if (!expected.has(field)) continue;
                String what = field.equals("tokenSpan") ? "tokens" : "characters";
                JsonNode value = owner.get(field);
                parts.add(isUnknown(value) ? what + " not recorded (" + reason(value) + ")" : what + " " + span(value.get("value")));
            }
            return prefix + " spans " + joinAnd(parts) + ".";
        }
        String head = null;
        if (expected.has("head")) {
            JsonNode value = owner.get("head");
            head = isUnknown(value) ? "has an unknown head membership (" + reason(value) + ")" : switch (value.get("value").asString("")) {
                case "wholly" -> "lies wholly inside the head window";
                case "partly" -> "lies partly inside the head window";
                case "not" -> "lies outside the head window";
                default -> "has head membership " + render(value.get("value"));
            };
        }
        String rows = null;
        if (expected.has("windowsHoldingWholly")) {
            JsonNode value = owner.get("windowsHoldingWholly");
            if (isUnknown(value)) {
                rows = "the rows holding it wholly are unknown (" + reason(value) + ")";
            } else {
                JsonNode starts = chunkNode.get("windowStarts");
                int count = !isUnknown(starts) && starts.path("value").isArray() ? starts.get("value").size() : -1;
                String counted = count < 0 ? "rows" : count == 1 ? "1 row" : count + " rows";
                String unscored = unscored(questionNode, chunkNode, count);
                String which = unscored == null ? "of the " + counted + " scored for this chunk"
                        : "of the " + counted + " the recorded scoring would score for this chunk (not rows that were scored: " + unscored + ")";
                List<String> held = new ArrayList<>();
                value.get("value").forEach(row -> held.add(row.asString()));
                rows = which + ", " + (held.isEmpty() ? "no row holds it wholly" : held.size() == 1 ? "row " + held.get(0) + " holds it wholly"
                        : "rows " + joinAnd(held) + " hold it wholly");
            }
        }
        if (head != null && rows != null) return prefix + " " + head + ", and " + rows + ".";
        return head != null ? prefix + " " + head + "." : prefix + ": " + rows + ".";
    }

    /**
     * Null when the report records rows as scored for this chunk (the question's rerank outcome observed RERANKED and the chunk's rerank
     * input observed true, the evidence report's rule, with the trace's observed windowCount equal to the arithmetic's row count); otherwise
     * why the rows are only the rows the recorded scoring would score.
     */
    private static String unscored(JsonNode questionNode, JsonNode chunkNode, int rowCount) {
        JsonNode outcome = questionNode.get("rerankOutcome");
        JsonNode input = chunkNode.get("rerankInput");
        if (outcome == null || isUnknown(outcome)) return "rerank outcome unknown, " + (outcome == null ? "not in the report" : reason(outcome));
        String value = outcome.path("value").asString("");
        if (value.equals("OFF")) return "reranking was off";
        if (value.equals("FALLBACK")) return "reranking fell back";
        if (!value.equals("RERANKED")) return "rerank outcome " + render(outcome.get("value"));
        if (!outcome.path("basis").asString("").equals("observed")) return "rerank outcome RERANKED is " + outcome.path("basis").asString("") + ", not observed";
        if (input == null || isUnknown(input)) return "rerank input unknown, " + (input == null ? "not in the report" : reason(input));
        JsonNode inputValue = input.get("value");
        if (inputValue == null || !inputValue.isBoolean()) return "rerank input " + render(inputValue);
        if (!inputValue.booleanValue()) return "the chunk was not a rerank input";
        if (!input.path("basis").asString("").equals("observed")) return "rerank input true is " + input.path("basis").asString("") + ", not observed";
        JsonNode windowCount = chunkNode.get("windowCount");
        if (windowCount == null || isUnknown(windowCount) || !windowCount.path("value").isIntegralNumber()) {
            return "the trace records no window count, " + (windowCount == null ? "not in the report" : reason(windowCount));
        }
        int scored = windowCount.get("value").intValue();
        String records = "the trace records " + scored + (scored == 1 ? " scored row" : " scored rows");
        if (rowCount < 0) return records + ", and the row count of this arithmetic is unknown";
        if (scored != rowCount) return records + ", not the " + rowCount + " of this arithmetic";
        return null;
    }

    // Template: "In <Report>, chunk <chunk>, which holds an accepted phrase of <question>, <was at fused position n|was not in the fused list>,
    // <was|was not> a rerank input, and <was reranked <ordinal>|has no reranked position>." Only the expected fields, in that order; a field
    // the chunk lacks, an unknown value, and a value of another type have their own wordings (RAG.md, Claims, Sentences).
    private String candidate(Claim claim, JsonNode check, Evaluation evaluation) {
        String reportFile = requireText(check, "report");
        String question = requireText(check, "question");
        long chunk = requireLong(check, "chunk");
        JsonNode expected = expected(check, "candidate");
        evaluation.subject("question " + question + ", chunk " + chunk + " in " + reportFile);
        Loaded report = report(reportFile, evaluation);
        JsonNode questionNode = reportQuestion(report, question);
        JsonNode chunkNode = null;
        for (JsonNode phraseNode : questionNode.path("phrases")) {
            for (JsonNode candidate : phraseNode.path("chunks")) {
                if (chunkNode == null && candidate.path("chunkId").asLong(-1) == chunk) chunkNode = candidate;
            }
        }
        if (chunkNode == null) throw new Unreadable("the report lists no chunk " + chunk + " holding an accepted phrase of " + question);
        compareValues(claim, chunkNode, expected, evaluation);
        List<String> parts = new ArrayList<>();
        for (String field : EXPECTED_KEYS.get("candidate")) {
            if (!expected.has(field)) continue;
            JsonNode value = chunkNode.get(field);
            if (value == null || !value.isObject()) {
                parts.add("has no " + field + " in the report");
                continue;
            }
            JsonNode found = value.get("value");
            boolean unknown = isUnknown(value);
            parts.add(switch (field) {
                case "fusedPosition" -> unknown ? "has an unknown fused position (" + reason(value) + ")"
                        : found == null || found.isNull() ? "was not in the fused list" : "was at fused position " + found.asString();
                case "rerankInput" -> unknown ? "has an unknown rerank input membership (" + reason(value) + ")"
                        : found != null && found.isBoolean() ? (found.booleanValue() ? "was a rerank input" : "was not a rerank input") : "has rerank input " + render(found);
                default -> unknown ? "has an unknown reranked position (" + reason(value) + ")"
                        : found == null || found.isNull() ? "has no reranked position" : found.isIntegralNumber() ? "was reranked " + ordinal(found.intValue())
                        : "has reranked position " + render(found);
            });
        }
        return "In " + reportReference(report) + ", chunk " + chunk + ", which holds an accepted phrase of " + question + ", " + joinAnd(parts) + ".";
    }

    /** A question's smallest fused position over every chunk holding any of its accepted phrases; null position when none is fused. */
    private record Best(Integer position, Long chunk, int holdingChunks) {
    }

    /**
     * The best fused position of the question in the report (plan {@code 2026-09-14-retrieval-recall.md}, Milestone 1): the smallest
     * {@code fusedPosition} over every chunk listed under any accepted phrase, null when no such chunk is in the fused list. Every chunk whose
     * fused position is unknown, missing, or not an integer makes the value unreadable, naming the question and each such chunk; a question
     * whose accepted phrases are unknown (its {@code acceptedPhraseCount} not observed, so the report lists no phrases) is unreadable too, so
     * an unknown is never read as not fused.
     */
    private static Best best(JsonNode questionNode, String question) {
        JsonNode count = questionNode.get("acceptedPhraseCount");
        if (count == null || !count.isObject() || !count.path("basis").asString("").equals("observed")) {
            throw new Unreadable("question " + question + " has unknown accepted phrases (acceptedPhraseCount " + (isUnknown(count) ? reason(count)
                    : "has basis " + count.path("basis").asString("none")) + ")");
        }
        Integer position = null;
        Long chunk = null;
        Set<Long> holding = new LinkedHashSet<>();
        Map<Long, String> unreadable = new LinkedHashMap<>();
        for (JsonNode phraseNode : questionNode.path("phrases")) {
            for (JsonNode candidate : phraseNode.path("chunks")) {
                long id = candidate.path("chunkId").asLong(-1);
                holding.add(id);
                JsonNode value = candidate.get("fusedPosition");
                if (value == null || !value.isObject()) {
                    unreadable.putIfAbsent(id, "chunk " + id + " has no fusedPosition in the report");
                    continue;
                }
                if (isUnknown(value)) {
                    unreadable.putIfAbsent(id, "chunk " + id + " has an unknown fused position (" + reason(value) + ")");
                    continue;
                }
                JsonNode found = value.get("value");
                if (found == null || found.isNull()) continue;
                if (!found.isIntegralNumber()) {
                    unreadable.putIfAbsent(id, "chunk " + id + " has fused position " + render(found) + ", not an integer");
                    continue;
                }
                if (position == null || found.intValue() < position) {
                    position = found.intValue();
                    chunk = id;
                }
            }
        }
        if (!unreadable.isEmpty()) throw new Unreadable("question " + question + " " + String.join(", ", unreadable.values()));
        return new Best(position, chunk, holding.size());
    }

    // Template: "In <Report>, the best fused position of a chunk holding an accepted phrase of <question> is <n> (chunk <id>)." | "In <Report>,
    // none of the <m> chunks holding an accepted phrase of <question> was in the fused list." ("the chunk" for one) | "In <Report>, no stored
    // chunk holds an accepted phrase of <question>."
    private String bestFusedPosition(Claim claim, JsonNode check, Evaluation evaluation) {
        String reportFile = requireText(check, "report");
        String question = requireText(check, "question");
        JsonNode expected = check.get("expected");
        if (expected == null || !(expected.isNull() || expected.isIntegralNumber() && expected.intValue() >= 1)) {
            throw new Unreadable("check.expected must be a fused position (positive integer) or null, found " + (expected == null ? "none" : expected));
        }
        evaluation.subject("question " + question + " in " + reportFile);
        Loaded report = report(reportFile, evaluation);
        Best best = best(reportQuestion(report, question), question);
        Integer wanted = expected.isNull() ? null : expected.intValue();
        if (!Objects.equals(best.position(), wanted)) {
            evaluation.differs("best fused position", String.valueOf(wanted), best.position() == null ? "null (not fused)" : best.position() + " (chunk " + best.chunk() + ")");
        }
        basis(claim, "derived", evaluation);
        String in = "In " + reportReference(report) + ", ";
        if (best.position() != null) return in + "the best fused position of a chunk holding an accepted phrase of " + question + " is " + best.position() + " (chunk " + best.chunk() + ").";
        if (best.holdingChunks() == 0) return in + "no stored chunk holds an accepted phrase of " + question + ".";
        return in + (best.holdingChunks() == 1 ? "the chunk" : "none of the " + best.holdingChunks() + " chunks") + " holding an accepted phrase of " + question + " was"
                + (best.holdingChunks() == 1 ? " not" : "") + " in the fused list.";
    }

    // Template: "In <Report>, candidate recall@<k> is <share>: <n> of <total> questions have a chunk holding an accepted phrase at fused position
    // <k> or earlier." | with k "all": "In <Report>, candidate recall over the whole fused list is <share>: <n> of <total> questions have a chunk
    // holding an accepted phrase in the fused list." The share is n / total rounded half up to six decimal places.
    private String candidateRecall(Claim claim, JsonNode check, Evaluation evaluation) {
        String reportFile = requireText(check, "report");
        JsonNode k = check.get("k");
        boolean all = k != null && k.isString() && k.stringValue().equals("all");
        if (!all && (k == null || !k.isIntegralNumber() || k.intValue() < 1)) throw new Unreadable("check.k must be a positive integer or \"all\", found " + k);
        JsonNode expectedNode = check.get("expected");
        BigDecimal expected = scale6(expectedNode);
        if (expected == null) throw new Unreadable("check.expected must be a number with at most six decimal places, found " + expectedNode);
        evaluation.subject("k " + (all ? "all" : k.intValue()) + " in " + reportFile);
        Loaded report = report(reportFile, evaluation);
        int total = 0;
        int within = 0;
        List<String> beyond = new ArrayList<>();
        for (JsonNode questionNode : report.json().get("questions")) {
            String question = questionNode.path("id").asString("");
            Best best = best(questionNode, question);
            total++;
            if (best.position() != null && (all || best.position() <= k.intValue())) within++;
            else beyond.add(question + " (" + (best.position() == null ? "not fused" : "fused position " + best.position()) + ")");
        }
        if (total == 0) throw new Unreadable(reportFile + " lists no questions");
        BigDecimal share = BigDecimal.valueOf(within).divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
        if (share.compareTo(expected) != 0) {
            evaluation.differs("share", expected.toPlainString(), share.toPlainString() + " (" + within + " of " + total + "; not counted: " + String.join(", ", beyond) + ")");
        }
        basis(claim, "derived", evaluation);
        String in = "In " + reportReference(report) + ", candidate recall";
        return all ? in + " over the whole fused list is " + share.toPlainString() + ": " + within + " of " + total + " questions have a chunk holding an accepted phrase in the fused list."
                : in + "@" + k.intValue() + " is " + share.toPlainString() + ": " + within + " of " + total + " questions have a chunk holding an accepted phrase at fused position "
                + k.intValue() + " or earlier.";
    }

    // Template: "In <Report>, diversification removed no chunk holding an accepted phrase of any of the <total> questions." | "In <Report>,
    // diversification removed a chunk holding an accepted phrase of <n> of the <total> questions: <question> (chunk <id>, redundant with chunk
    // <id>), ...." (several removed holding chunks of one question are listed in its parentheses, separated by semicolons)
    /**
     * The number of the report's questions for which the trace's {@code removed} list (plan {@code 2026-09-14-retrieval-recall.md}, Milestone 3)
     * holds a chunk listed under any accepted phrase of the question. Read from each question's {@code removed} list and its phrases' chunk ids,
     * not from the report's {@code acceptedChunkRemoved} flag. A question whose removals are unknown or missing (a trace recorded before removals
     * were traced says {@code no trace of removals}) or whose accepted phrases are unknown makes the claim unreadable, naming every such question,
     * so an unknown is never counted as nothing removed. A failure names the count found and every counted question with its removed chunks.
     */
    private String removedAccepted(Claim claim, JsonNode check, Evaluation evaluation) {
        String reportFile = requireText(check, "report");
        JsonNode expected = check.get("expected");
        if (expected == null || !expected.isIntegralNumber() || expected.intValue() < 0) {
            throw new Unreadable("check.expected must be a question count (non-negative integer), found " + (expected == null ? "none" : expected));
        }
        evaluation.subject("removals in " + reportFile);
        Loaded report = report(reportFile, evaluation);
        JsonNode questions = report.json().get("questions");
        if (questions == null || !questions.isArray() || questions.isEmpty()) throw new Unreadable(reportFile + " lists no questions");
        List<String> unreadable = new ArrayList<>();
        List<String> counted = new ArrayList<>();
        for (JsonNode questionNode : questions) {
            String question = questionNode.path("id").asString("");
            JsonNode count = questionNode.get("acceptedPhraseCount");
            if (count == null || !count.isObject() || !count.path("basis").asString("").equals("observed")) {
                unreadable.add("question " + question + " has unknown accepted phrases (acceptedPhraseCount " + (isUnknown(count) ? reason(count)
                        : "has basis " + (count == null ? "none" : count.path("basis").asString("none"))) + ")");
                continue;
            }
            JsonNode removed = questionNode.get("removed");
            if (removed == null || !removed.isObject() || !removed.has("basis")) {
                unreadable.add("question " + question + " has no removed value in the report");
                continue;
            }
            if (isUnknown(removed)) {
                unreadable.add("question " + question + " has unknown removals (" + reason(removed) + ")");
                continue;
            }
            JsonNode entries = removed.get("value");
            if (entries == null || !entries.isArray()) {
                unreadable.add("question " + question + " has removals " + (entries == null ? "none" : abbreviate(entries.toString())) + ", not a list");
                continue;
            }
            Set<Long> holding = new LinkedHashSet<>();
            for (JsonNode phraseNode : questionNode.path("phrases")) {
                for (JsonNode chunkNode : phraseNode.path("chunks")) holding.add(chunkNode.path("chunkId").asLong(-1));
            }
            List<String> hits = new ArrayList<>();
            for (JsonNode entry : entries) {
                long chunk = entry.path("chunkId").asLong(-1);
                if (holding.contains(chunk)) hits.add("chunk " + chunk + ", redundant with chunk " + render(entry.get("redundantWith")));
            }
            if (!hits.isEmpty()) counted.add(question + " (" + String.join("; ", hits) + ")");
        }
        if (!unreadable.isEmpty()) throw new Unreadable(String.join(", ", unreadable));
        if (counted.size() != expected.intValue()) {
            evaluation.differs("count", String.valueOf(expected.intValue()), counted.size() + " (" + (counted.isEmpty() ? "none" : String.join(", ", counted)) + ")");
        }
        basis(claim, "derived", evaluation);
        String in = "In " + reportReference(report) + ", diversification removed ";
        return counted.isEmpty() ? in + "no chunk holding an accepted phrase of any of the " + questions.size() + " questions."
                : in + "a chunk holding an accepted phrase of " + counted.size() + " of the " + questions.size() + " questions: " + String.join(", ", counted) + ".";
    }

    private record BlendInput(long chunk, int fused, int reranked) {
    }

    // Template: "Blending reranked and fused positions in <Snapshot> at k <k> and w <w> (accepted chunks from <Report>), <question> ranks
    // <ordinal>." | "..., <question> ranks outside the window of <n> results (no chunk holding an accepted phrase)." | "..., hit@5 over the <n>
    // question(s) <ids> is <v> (<c> ranked 1 to 5)." | "..., MRR over the <n> question(s) <ids> is <v>." | "..., top-5 membership of the <n>
    // question(s) <ids> against the stored ranks of <Reference>: <a> inside the top 5 in both and <b> outside it in both; entering the top 5:
    // <none | q (rank r to rank s), ...>; leaving the top 5: <none | ...>."
    /**
     * A blended order recomputed from a traced snapshot (plan {@code 2026-09-14-rerank-blend.md}, Milestone 1): for each listed question, the
     * trace's rerank inputs, each with fused position f and reranked position r, are ordered by {@code w / (k + r) + (1 - w) / (k + f)} descending
     * (exact arithmetic; ties by smaller f), the fused chunks after the inputs keep fused order, and the window is the snapshot's window size. The
     * rank is the 1-based position of the first window chunk the evidence report lists under an accepted phrase of the question, null when none.
     * hit@5 and MRR follow RetrievalEvaluationService (hit@5 half up to six places; each 1/rank half up to twelve places, their sum over the
     * listed questions half up to six places). Unreadable: a report whose snapshotId is not the snapshot's id, a question without a trace, a
     * rerank outcome other than RERANKED, inputs that are not the first fused chunks with positions 1 to n, an input count other than
     * min(fused, max(rerankCandidates, window)) when the snapshot records rerankCandidates, unknown accepted phrases, an accepted phrase whose
     * heldByStoredChunk is not true and observed, a fused, rerank input, or phrase chunk without an integer chunk id, or a fused chunk id listed
     * twice. Metric top5Membership (since 2026-09-15) also reads a reference snapshot's stored ranks and compares, per listed question, whether the
     * blended rank and the stored rank are in the top 5; expected lists the questions whose membership differs.
     */
    private String blend(Claim claim, JsonNode check, Evaluation evaluation) {
        String snapshotFile = requireText(check, "snapshot");
        String reportFile = requireText(check, "report");
        JsonNode kNode = check.get("k");
        if (kNode == null || !kNode.isIntegralNumber() || kNode.intValue() < 1) throw new Unreadable("check.k must be a positive integer, found " + kNode);
        BigDecimal w = scale6(check.get("w"));
        if (w == null || w.signum() < 0 || w.compareTo(BigDecimal.ONE) > 0) {
            throw new Unreadable("check.w must be a number from 0 to 1 with at most six decimal places, found " + check.get("w"));
        }
        JsonNode questionsNode = check.get("questions");
        if (questionsNode == null || !questionsNode.isArray() || questionsNode.isEmpty()) {
            throw new Unreadable("check.questions must list at least one question id, found " + questionsNode);
        }
        List<String> questions = new ArrayList<>();
        for (JsonNode q : questionsNode) {
            if (!q.isString() || q.stringValue().isBlank() || questions.contains(q.stringValue())) {
                throw new Unreadable("check.questions must list distinct non-blank question ids, found " + abbreviate(questionsNode.toString()));
            }
            questions.add(q.stringValue());
        }
        String metric = requireText(check, "metric");
        JsonNode expected = check.get("expected");
        BigDecimal expectedValue = null;
        switch (metric) {
            case "rank" -> {
                if (questions.size() != 1) throw new Unreadable("check.metric rank takes exactly one question, found " + questions.size());
                if (expected == null || !(expected.isNull() || expected.isIntegralNumber() && expected.intValue() >= 1)) {
                    throw new Unreadable("check.expected must be a rank (positive integer) or null, found " + (expected == null ? "none" : expected));
                }
            }
            case "hitAt5", "mrr" -> {
                expectedValue = scale6(expected);
                if (expectedValue == null) throw new Unreadable("check.expected must be a number with at most six decimal places, found " + expected);
            }
            case "top5Membership" -> {
                if (blank(check.get("reference"))) throw new Unreadable("check.reference must name the reference snapshot file for metric top5Membership, found " + check.get("reference"));
                boolean listed = expected != null && expected.isArray();
                Set<String> seen = new HashSet<>();
                if (listed) {
                    for (JsonNode id : expected) {
                        if (!id.isString() || !questions.contains(id.stringValue()) || !seen.add(id.stringValue())) listed = false;
                    }
                }
                if (!listed) {
                    throw new Unreadable("check.expected must list the distinct listed questions whose top-5 membership differs from the reference (an empty list for none), found "
                            + (expected == null ? "none" : abbreviate(expected.toString())));
                }
            }
            default -> throw new Unreadable("check.metric must be rank, hitAt5, mrr, or top5Membership, found " + quote(metric));
        }
        if (!metric.equals("top5Membership") && check.get("reference") != null) {
            throw new Unreadable("check.reference is read only with metric top5Membership, found it with metric " + metric);
        }
        int k = kNode.intValue();
        evaluation.subject(metric + " at k " + k + ", w " + w.stripTrailingZeros().toPlainString() + " over " + questions.size()
                + (questions.size() == 1 ? " question" : " questions") + " in " + snapshotFile);
        Loaded loaded = load(snapshotFile, directory, evaluation);
        Snapshot snapshot = Snapshot.of(snapshotFile, loaded.json());
        Loaded report = report(reportFile, evaluation);
        if (snapshot.id() == null || snapshot.id() != report.json().get("snapshotId").longValue()) {
            throw new Unreadable(reportFile + " is the evidence report of snapshot " + report.json().get("snapshotId").asString() + ", not of " + snapshotFile
                    + " (snapshot " + snapshot.id() + ")");
        }
        Integer window = snapshot.windowSize();
        if (window == null || window < 1) throw new Unreadable(snapshotFile + " records no window size");
        JsonNode results = loaded.json().path("results");
        JsonNode traces = results.isObject() ? results.path("traces") : loaded.json().path("traces");
        JsonNode rerankCandidates = snapshot.properties() == null ? null : snapshot.properties().get("rerankCandidates");
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (String question : questions) {
            snapshot.question(question);
            JsonNode trace = null;
            for (JsonNode entry : traces) {
                if (question.equals(entry.path("id").asString(null))) trace = entry.get("trace");
            }
            if (trace == null || !trace.isObject()) throw new Unreadable("question " + question + " has no trace in " + snapshotFile);
            ranks.put(question, blendedRank(question, trace, reportQuestion(report, question), k, w, window,
                    rerankCandidates != null && rerankCandidates.isIntegralNumber() ? rerankCandidates.intValue() : null));
        }
        basis(claim, "derived", evaluation);
        String lead = "Blending reranked and fused positions in " + snapshotReference(loaded, snapshot, null) + " at k " + k + " and w "
                + w.stripTrailingZeros().toPlainString() + " (accepted chunks from " + reportReference(report) + "), ";
        String over = " over the " + questions.size() + (questions.size() == 1 ? " question " : " questions ") + joinAnd(questions) + " is ";
        switch (metric) {
            case "rank" -> {
                String question = questions.get(0);
                Integer found = ranks.get(question);
                Integer wanted = expected.isNull() ? null : expected.intValue();
                if (!Objects.equals(found, wanted)) evaluation.differs("rank", String.valueOf(wanted), String.valueOf(found));
                return lead + question + (found != null ? " ranks " + ordinal(found) + "."
                        : " ranks outside the window of " + window + " results (no chunk holding an accepted phrase).");
            }
            case "hitAt5" -> {
                long inside = ranks.values().stream().filter(r -> r != null && r <= 5).count();
                BigDecimal found = BigDecimal.valueOf(inside).divide(BigDecimal.valueOf(questions.size()), 6, RoundingMode.HALF_UP);
                if (found.compareTo(expectedValue) != 0) {
                    List<String> outside = new ArrayList<>();
                    ranks.forEach((q, r) -> { if (r == null || r > 5) outside.add(q + " (" + rankText(r) + ")"); });
                    evaluation.differs("hit@5", expectedValue.toPlainString(), found.toPlainString() + " (" + inside + " of " + questions.size() + "; outside the top 5: "
                            + (outside.isEmpty() ? "none" : String.join(", ", outside)) + ")");
                }
                return lead + "hit@5" + over + found.toPlainString() + " (" + inside + " ranked 1 to 5).";
            }
            case "top5Membership" -> {
                String referenceFile = requireText(check, "reference");
                Loaded referenceLoaded = load(referenceFile, directory, evaluation);
                Snapshot reference = Snapshot.of(referenceFile, referenceLoaded.json());
                int insideBoth = 0;
                int outsideBoth = 0;
                List<String> entering = new ArrayList<>();
                List<String> leaving = new ArrayList<>();
                List<String> differing = new ArrayList<>();
                for (String question : questions) {
                    reference.question(question);
                    Integer stored = reference.rank(question);
                    Integer blended = ranks.get(question);
                    boolean was = stored != null && stored <= 5;
                    boolean is = blended != null && blended <= 5;
                    String move = question + " (" + rankText(stored) + " to " + rankText(blended) + ")";
                    if (was && is) insideBoth++;
                    else if (!was && !is) outsideBoth++;
                    else {
                        (is ? entering : leaving).add(move);
                        differing.add(question);
                    }
                }
                List<String> wanted = new ArrayList<>();
                expected.forEach(id -> wanted.add(id.stringValue()));
                if (!new HashSet<>(wanted).equals(new HashSet<>(differing))) {
                    evaluation.differs("questions whose top-5 membership differs", wanted.isEmpty() ? "none" : String.join(", ", wanted),
                            (differing.isEmpty() ? "none" : String.join(", ", differing)) + " (entering: " + (entering.isEmpty() ? "none" : String.join(", ", entering))
                                    + "; leaving: " + (leaving.isEmpty() ? "none" : String.join(", ", leaving)) + ")");
                }
                return lead + "top-5 membership of the " + questions.size() + (questions.size() == 1 ? " question " : " questions ") + joinAnd(questions)
                        + " against the stored ranks of " + snapshotReference(referenceLoaded, reference, null) + ": " + insideBoth + " inside the top 5 in both and "
                        + outsideBoth + " outside it in both; entering the top 5: " + (entering.isEmpty() ? "none" : String.join(", ", entering))
                        + "; leaving the top 5: " + (leaving.isEmpty() ? "none" : String.join(", ", leaving)) + ".";
            }
            default -> {
                BigDecimal sum = BigDecimal.ZERO;
                for (Integer r : ranks.values()) {
                    if (r != null) sum = sum.add(BigDecimal.ONE.divide(BigDecimal.valueOf(r), 12, RoundingMode.HALF_UP));
                }
                BigDecimal found = sum.divide(BigDecimal.valueOf(questions.size()), 6, RoundingMode.HALF_UP);
                if (found.compareTo(expectedValue) != 0) {
                    List<String> listed = new ArrayList<>();
                    ranks.forEach((q, r) -> listed.add(q + " " + rankText(r)));
                    evaluation.differs("MRR", expectedValue.toPlainString(), found.toPlainString() + " (" + String.join(", ", listed) + ")");
                }
                return lead + "MRR" + over + found.toPlainString() + ".";
            }
        }
    }

    // Template: "Between <reference Snapshot> and <candidate Snapshot>, the <fused order|rerank input set> is (the fused positions of the rerank
    // inputs are) identical for each of the <n> questions." | "..., the <...> differs (differ) for <m> of the <n> questions: <question>[ (<detail>)], ...." with a
    // detail only for rerankInputSet: "only in snapshot <id>: chunks <ids>; only in snapshot <id>: chunks <ids>".
    /**
     * Whether two traced snapshots gave the same candidate lists per question (plan {@code 2026-09-15-reranker-ettin.md}, amendment 3, G7).
     * {@code compare} {@code fusedOrder}: the trace's fused chunk ids ordered by fused position; {@code rerankInputSet}: the set of the trace's
     * rerank input chunk ids; {@code rerankInputPositions}: each rerank input's chunk id with its fused position (a question whose input set
     * differs also differs here). {@code expected} lists the distinct questions that differ (empty for none); true when exactly those differ.
     * Unreadable: the two snapshots list different question ids, a question without a trace in either, a fused list or rerank input list that
     * is not a list, a fused chunk or rerank input without an integer chunk id and fused position, or a chunk id listed twice in one list.
     */
    private String candidateLists(Claim claim, JsonNode check, Evaluation evaluation) {
        String referenceFile = requireText(check, "reference");
        String candidateFile = requireText(check, "candidate");
        String compare = requireText(check, "compare");
        String what = switch (compare) {
            case "fusedOrder" -> "fused order";
            case "rerankInputSet" -> "rerank input set";
            case "rerankInputPositions" -> "fused positions of the rerank inputs";
            default -> throw new Unreadable("check.compare must be fusedOrder, rerankInputSet, or rerankInputPositions, found " + quote(compare));
        };
        boolean plural = compare.equals("rerankInputPositions");
        JsonNode expected = check.get("expected");
        List<String> wanted = new ArrayList<>();
        boolean listed = expected != null && expected.isArray();
        if (listed) {
            for (JsonNode id : expected) {
                if (!id.isString() || id.stringValue().isBlank() || wanted.contains(id.stringValue())) listed = false;
                else wanted.add(id.stringValue());
            }
        }
        if (!listed) {
            throw new Unreadable("check.expected must list the distinct question ids that differ (an empty list for none), found "
                    + (expected == null ? "none" : abbreviate(expected.toString())));
        }
        evaluation.subject(compare + " of " + candidateFile + " against " + referenceFile);
        Loaded referenceLoaded = load(referenceFile, directory, evaluation);
        Snapshot reference = Snapshot.of(referenceFile, referenceLoaded.json());
        Loaded candidateLoaded = load(candidateFile, directory, evaluation);
        Snapshot candidate = Snapshot.of(candidateFile, candidateLoaded.json());
        if (referenceLoaded.path().equals(candidateLoaded.path())) throw new Unreadable("check.reference and check.candidate name the same file " + referenceFile);
        List<String> questions = new ArrayList<>();
        reference.questions().forEach(q -> questions.add(q.path("id").asString("")));
        List<String> candidateQuestions = new ArrayList<>();
        candidate.questions().forEach(q -> candidateQuestions.add(q.path("id").asString("")));
        if (!new HashSet<>(questions).equals(new HashSet<>(candidateQuestions)) || questions.size() != candidateQuestions.size()) {
            throw new Unreadable(referenceFile + " and " + candidateFile + " list different questions");
        }
        for (String id : wanted) {
            if (!questions.contains(id)) throw new Unreadable("check.expected lists " + id + ", which neither snapshot has");
        }
        Map<String, JsonNode> referenceTraces = traces(referenceLoaded, referenceFile);
        Map<String, JsonNode> candidateTraces = traces(candidateLoaded, candidateFile);
        List<String> differing = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (String question : questions) {
            JsonNode a = referenceTraces.get(question);
            JsonNode b = candidateTraces.get(question);
            if (a == null) throw new Unreadable("question " + question + " has no trace in " + referenceFile);
            if (b == null) throw new Unreadable("question " + question + " has no trace in " + candidateFile);
            String detail = null;
            boolean differs;
            switch (compare) {
                case "fusedOrder" -> differs = !new ArrayList<>(positions(a.get("fused"), question, "fused chunk", referenceFile).keySet())
                        .equals(new ArrayList<>(positions(b.get("fused"), question, "fused chunk", candidateFile).keySet()));
                case "rerankInputSet" -> {
                    Set<Long> x = inputs(a, question, referenceFile).keySet();
                    Set<Long> y = inputs(b, question, candidateFile).keySet();
                    differs = !x.equals(y);
                    if (differs) {
                        List<Long> onlyX = x.stream().filter(c -> !y.contains(c)).sorted().toList();
                        List<Long> onlyY = y.stream().filter(c -> !x.contains(c)).sorted().toList();
                        detail = "only in snapshot " + reference.id() + ": " + chunks(onlyX) + "; only in snapshot " + candidate.id() + ": " + chunks(onlyY);
                    }
                }
                default -> differs = !inputs(a, question, referenceFile).equals(inputs(b, question, candidateFile));
            }
            if (differs) {
                differing.add(question);
                details.add(detail == null ? question : question + " (" + detail + ")");
            }
        }
        if (!new HashSet<>(wanted).equals(new HashSet<>(differing))) {
            evaluation.differs("questions whose " + what + (plural ? " differ" : " differs"), wanted.isEmpty() ? "none" : String.join(", ", wanted),
                    differing.isEmpty() ? "none" : String.join(", ", details));
        }
        basis(claim, "derived", evaluation);
        String lead = "Between " + snapshotReference(referenceLoaded, reference, null) + " and " + snapshotReference(candidateLoaded, candidate, null) + ", the " + what;
        return differing.isEmpty() ? lead + (plural ? " are" : " is") + " identical for each of the " + questions.size() + " questions."
                : lead + (plural ? " differ" : " differs") + " for " + differing.size() + " of the " + questions.size() + " questions: " + String.join(", ", details) + ".";
    }

    /** Question id to trace object of a traced snapshot in either shape. */
    private static Map<String, JsonNode> traces(Loaded loaded, String file) {
        JsonNode results = loaded.json().path("results");
        JsonNode traces = results.isObject() ? results.path("traces") : loaded.json().path("traces");
        if (!traces.isArray()) throw new Unreadable(file + " records no traces");
        Map<String, JsonNode> byQuestion = new HashMap<>();
        for (JsonNode entry : traces) {
            JsonNode trace = entry.get("trace");
            if (trace != null && trace.isObject()) byQuestion.put(entry.path("id").asString(""), trace);
        }
        return byQuestion;
    }

    /** A trace's rerank inputs as chunk id to fused position, in input order. */
    private static Map<Long, Integer> inputs(JsonNode trace, String question, String file) {
        JsonNode rerank = trace.get("rerank");
        return positions(rerank == null ? null : rerank.get("candidates"), question, "rerank input", file);
    }

    /** Chunk id to fused position for a list of trace entries, ordered by fused position. */
    private static Map<Long, Integer> positions(JsonNode list, String question, String what, String file) {
        if (list == null || !list.isArray()) throw new Unreadable("question " + question + " has no " + what + " list in " + file);
        Map<Long, Integer> byChunk = new HashMap<>();
        for (JsonNode entry : list) {
            JsonNode chunk = entry.get("chunkId");
            JsonNode position = entry.get("fusedPosition");
            if (chunk == null || !chunk.isIntegralNumber() || position == null || !position.isIntegralNumber()) {
                throw new Unreadable("question " + question + " has a " + what + " without an integer chunk id and fused position in " + file);
            }
            if (byChunk.put(chunk.longValue(), position.intValue()) != null) {
                throw new Unreadable("question " + question + " lists " + what + " " + chunk.longValue() + " twice in " + file);
            }
        }
        Map<Long, Integer> ordered = new LinkedHashMap<>();
        byChunk.entrySet().stream().sorted(Map.Entry.<Long, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));
        return ordered;
    }

    private static String chunks(List<Long> ids) {
        if (ids.isEmpty()) return "no chunk";
        return (ids.size() == 1 ? "chunk " : "chunks ") + ids.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    /** The question's rank in the blended window (see {@link #blend}); null when no window chunk holds an accepted phrase. */
    private static Integer blendedRank(String question, JsonNode trace, JsonNode reportQuestion, int k, BigDecimal w, int window, Integer rerankCandidates) {
        JsonNode rerank = trace.path("rerank");
        String outcome = rerank.path("outcome").asString("none");
        if (!outcome.equals("RERANKED")) throw new Unreadable("question " + question + " has rerank outcome " + outcome + ", not RERANKED, so no reranked positions to blend");
        JsonNode fused = trace.path("fused");
        Map<Integer, Long> byPosition = new HashMap<>();
        Map<Long, Integer> positionOf = new HashMap<>();
        for (JsonNode entry : fused) {
            JsonNode chunk = entry.get("chunkId");
            int position = entry.path("fusedPosition").asInt(-1);
            if (chunk == null || !chunk.isIntegralNumber()) throw new Unreadable("question " + question + " has a fused chunk without an integer chunk id at fused position " + position);
            Integer earlier = positionOf.putIfAbsent(chunk.longValue(), position);
            if (earlier != null) {
                throw new Unreadable("question " + question + " lists fused chunk id " + chunk.longValue() + " twice, at fused positions " + earlier + " and " + position);
            }
            byPosition.put(position, chunk.longValue());
        }
        for (int position = 1; position <= fused.size(); position++) {
            if (!byPosition.containsKey(position)) throw new Unreadable("question " + question + " has fused positions that are not 1 to " + fused.size());
        }
        List<BlendInput> inputs = new ArrayList<>();
        Set<Integer> fusedSeen = new HashSet<>();
        Set<Integer> rerankedSeen = new HashSet<>();
        for (JsonNode candidate : rerank.path("candidates")) {
            JsonNode f = candidate.get("fusedPosition");
            JsonNode r = candidate.get("rerankedPosition");
            if (f == null || !f.isIntegralNumber() || r == null || !r.isIntegralNumber()) {
                throw new Unreadable("question " + question + " has a rerank input without integer fused and reranked positions: " + abbreviate(candidate.toString()));
            }
            JsonNode chunk = candidate.get("chunkId");
            if (chunk == null || !chunk.isIntegralNumber()) {
                throw new Unreadable("question " + question + " has a rerank input without an integer chunk id at fused position " + f.intValue());
            }
            inputs.add(new BlendInput(chunk.longValue(), f.intValue(), r.intValue()));
            fusedSeen.add(f.intValue());
            rerankedSeen.add(r.intValue());
        }
        int n = inputs.size();
        boolean positions = n > 0 && fusedSeen.size() == n && rerankedSeen.size() == n && fusedSeen.stream().allMatch(p -> p >= 1 && p <= n)
                && rerankedSeen.stream().allMatch(p -> p >= 1 && p <= n) && inputs.stream().allMatch(i -> Objects.equals(byPosition.get(i.fused()), i.chunk()));
        if (!positions) throw new Unreadable("question " + question + " has rerank inputs that are not the first fused chunks with fused and reranked positions 1 to " + n);
        if (rerankCandidates != null && n != Math.min(fused.size(), Math.max(rerankCandidates, window))) {
            throw new Unreadable("question " + question + " has " + n + " rerank inputs, expected min(fused " + fused.size() + ", max(rerankCandidates " + rerankCandidates
                    + ", window " + window + "))");
        }
        JsonNode count = reportQuestion.get("acceptedPhraseCount");
        if (count == null || !count.isObject() || !count.path("basis").asString("").equals("observed")) {
            throw new Unreadable("question " + question + " has unknown accepted phrases in the report");
        }
        Set<Long> accepted = new HashSet<>();
        for (JsonNode phraseNode : reportQuestion.path("phrases")) {
            String phrase = quote(text(phraseNode, "phrase"));
            JsonNode held = phraseNode.get("heldByStoredChunk");
            if (held == null || !held.isObject() || !held.path("value").isBoolean() || !held.path("value").booleanValue()
                    || !held.path("basis").asString("").equals("observed")) {
                throw new Unreadable("question " + question + " has accepted phrase " + phrase + " whose heldByStoredChunk is not true and observed, found "
                        + (held == null ? "none" : abbreviate(held.toString())));
            }
            if (!phraseNode.path("chunks").isArray() || phraseNode.path("chunks").isEmpty()) {
                throw new Unreadable("question " + question + " has accepted phrase " + phrase + " held by a stored chunk but listing no chunk");
            }
            for (JsonNode chunkNode : phraseNode.path("chunks")) {
                JsonNode chunk = chunkNode.get("chunkId");
                if (chunk == null || !chunk.isIntegralNumber()) {
                    throw new Unreadable("question " + question + " has accepted phrase " + phrase + " with a chunk without an integer chunk id");
                }
                accepted.add(chunk.longValue());
            }
        }
        BigDecimal kd = BigDecimal.valueOf(k);
        BigDecimal rest = BigDecimal.ONE.subtract(w);
        inputs.sort((a, b) -> {
            // score = (w (k + f) + (1 - w) (k + r)) / ((k + r) (k + f)); compare a and b by cross-multiplying, higher first
            BigDecimal numA = w.multiply(kd.add(BigDecimal.valueOf(a.fused()))).add(rest.multiply(kd.add(BigDecimal.valueOf(a.reranked()))));
            BigDecimal denA = kd.add(BigDecimal.valueOf(a.reranked())).multiply(kd.add(BigDecimal.valueOf(a.fused())));
            BigDecimal numB = w.multiply(kd.add(BigDecimal.valueOf(b.fused()))).add(rest.multiply(kd.add(BigDecimal.valueOf(b.reranked()))));
            BigDecimal denB = kd.add(BigDecimal.valueOf(b.reranked())).multiply(kd.add(BigDecimal.valueOf(b.fused())));
            int byScore = numB.multiply(denA).compareTo(numA.multiply(denB));
            return byScore != 0 ? byScore : Integer.compare(a.fused(), b.fused());
        });
        List<Long> order = new ArrayList<>();
        inputs.forEach(input -> order.add(input.chunk()));
        for (int position = n + 1; position <= fused.size(); position++) order.add(byPosition.get(position));
        for (int i = 0; i < Math.min(window, order.size()); i++) {
            if (accepted.contains(order.get(i))) return i + 1;
        }
        return null;
    }

    /**
     * Compares each expected field with the report's evidence value of that name. An unknown value never matches. The claim's basis must
     * be the basis of the compared values (derived if any is derived, otherwise observed), except under basis experiment.
     */
    private static void compareValues(Claim claim, JsonNode owner, JsonNode expected, Evaluation evaluation) {
        boolean anyUnknown = false;
        boolean anyDerived = false;
        for (Map.Entry<String, JsonNode> field : expected.properties()) {
            JsonNode value = owner.get(field.getKey());
            if (value == null || !value.isObject() || !value.has("basis")) {
                evaluation.differs(field.getKey(), render(field.getValue()), "no such value in the report");
                anyUnknown = true;
                continue;
            }
            String basis = value.path("basis").asString("");
            if (basis.equals("unknown")) {
                anyUnknown = true;
                evaluation.differs(field.getKey(), render(field.getValue()), "unknown (" + value.path("reason").asString("") + ")");
                continue;
            }
            anyDerived |= basis.equals("derived");
            if (!sameValue(field.getValue(), value.get("value"))) {
                evaluation.differs(field.getKey(), shown(field.getValue(), value.get("value")), shown(value.get("value"), field.getValue()) + " (" + basis + ")");
            }
        }
        if (!anyUnknown) basis(claim, anyDerived ? "derived" : "observed", evaluation);
    }

    // Printed after the unknown claim's text: "(not recorded in <Report>: <reason or 'no reason given'>)", or "(recorded in <Report> as <value>,
    // <basis>)".
    private String notRecorded(JsonNode check, Evaluation evaluation) {
        String reportFile = requireText(check, "report");
        String path = requireText(check, "path");
        JsonNode reason = check.get("reason");
        if (reason != null && !reason.isString()) throw new Unreadable("check.reason must be a string, found " + reason);
        evaluation.subject(path + " in " + reportFile);
        Loaded report = report(reportFile, evaluation);
        JsonNode value = ReportPath.resolve(report.json(), path);
        if (value == null) throw new Unreadable("path names no value in the report");
        if (!value.isObject() || !value.has("basis")) throw new Unreadable("path names " + abbreviate(value.toString()) + ", not an evidence value");
        String basis = value.path("basis").asString("");
        if (!basis.equals("unknown")) {
            evaluation.fail("expected not recorded (unknown), found " + render(value.get("value")) + " (" + basis
                    + "): the value is now recorded, so the claim must be rewritten");
            return "(recorded in " + reportReference(report) + " as " + render(value.get("value")) + ", " + basis + ")";
        }
        if (reason != null && !reason.stringValue().equals(value.path("reason").asString(""))) {
            evaluation.differs("reason", quote(reason.stringValue()), quote(value.path("reason").asString("")));
        }
        return "(not recorded in " + reportReference(report) + ": " + reason(value) + ")";
    }

    private static void basis(Claim claim, String evidenceBasis, Evaluation evaluation) {
        if (!claim.basis().equals("experiment") && !claim.basis().equals(evidenceBasis)) evaluation.differs("basis", claim.basis(), evidenceBasis);
    }

    private static JsonNode expected(JsonNode check, String type) {
        JsonNode expected = check.get("expected");
        if (expected == null || !expected.isObject() || expected.isEmpty()) {
            throw new Unreadable("check.expected must be an object with at least one of " + String.join(", ", EXPECTED_KEYS.get(type)) + ", found " + expected);
        }
        return expected;
    }

    /** "snapshot <id>" or "<label> (snapshot <id>)", with an optional detail inside the parentheses. */
    private String snapshotReference(Loaded loaded, Snapshot snapshot, String detail) {
        if (snapshot.id() == null) throw new Unreadable(loaded.written() + " records no snapshot id");
        String label = labels.get(loaded.path());
        if (label == null) return "snapshot " + snapshot.id() + (detail == null ? "" : " (" + detail + ")");
        return label + " (snapshot " + snapshot.id() + (detail == null ? "" : ", " + detail) + ")";
    }

    /** "the evidence report of snapshot <id>" or "<label> (the evidence report of snapshot <id>)". */
    private String reportReference(Loaded report) {
        String label = labels.get(report.path());
        String reference = "the evidence report of snapshot " + report.json().get("snapshotId").asString();
        return label == null ? reference : label + " (" + reference + ")";
    }

    /** Expected [start, end] against a report span {start, end}; everything else by JSON value (numbers by numeric value). */
    static boolean sameValue(JsonNode expected, JsonNode found) {
        if (found == null) return expected.isNull();
        if (expected.isArray() && expected.size() == 2 && found.isObject() && found.has("start") && found.has("end")) {
            return sameValue(expected.get(0), found.get("start")) && sameValue(expected.get(1), found.get("end"));
        }
        if (expected.isNumber() && found.isNumber()) return expected.decimalValue().compareTo(found.decimalValue()) == 0;
        if (expected.isArray() && found.isArray()) {
            if (expected.size() != found.size()) return false;
            for (int i = 0; i < expected.size(); i++) {
                if (!sameValue(expected.get(i), found.get(i))) return false;
            }
            return true;
        }
        if (expected.isObject() && found.isObject()) {
            if (!expected.propertyNames().equals(found.propertyNames())) return false;
            for (String key : expected.propertyNames()) {
                if (!sameValue(expected.get(key), found.get(key))) return false;
            }
            return true;
        }
        return expected.equals(found);
    }

    static String render(JsonNode value) {
        if (value == null || value.isNull()) return "null";
        if (value.isObject() && value.has("start") && value.has("end") && value.size() == 2) return "[" + value.get("start") + ", " + value.get("end") + "]";
        if (value.isString()) return value.stringValue();
        if (value.isArray()) {
            List<String> items = new ArrayList<>();
            value.forEach(item -> items.add(render(item)));
            return "[" + String.join(", ", items) + "]";
        }
        return value.toString();
    }

    /**
     * The value as {@link #render} writes it; when {@code other} renders the same but is a different JSON type (the number 20 against the
     * string "20"), with its type, so a message never reads "expected 20, found 20".
     */
    static String shown(JsonNode value, JsonNode other) {
        if (value == null || other == null || !render(value).equals(render(other)) || value.getNodeType() == other.getNodeType()) return render(value);
        if (value.isString()) return "the string \"" + value.stringValue() + "\"";
        if (value.isNumber()) return "the number " + value;
        if (value.isBoolean()) return "the boolean " + value;
        if (value.isArray()) return "the array " + render(value);
        if (value.isObject()) return "the object " + render(value);
        return render(value);
    }

    private static String span(JsonNode value) {
        if (value != null && value.isObject() && value.has("start") && value.has("end")) return "[" + value.get("start") + ", " + value.get("end") + ")";
        return render(value);
    }

    private static boolean isUnknown(JsonNode value) {
        return value == null || !value.isObject() || value.path("basis").asString("").equals("unknown");
    }

    private static String reason(JsonNode value) {
        return value == null || !value.isObject() ? "not in the report" : value.path("reason").asString("no reason given");
    }

    private JsonNode reportQuestion(Loaded report, String question) {
        for (JsonNode node : report.json().get("questions")) {
            if (question.equals(node.path("id").asString(null))) return node;
        }
        throw new Unreadable("the report has no question " + question);
    }

    private Loaded report(String relative, Evaluation evaluation) {
        Loaded loaded = load(relative, directory, evaluation);
        JsonNode json = loaded.json();
        if (!json.path("questions").isArray() || !json.path("snapshotId").isIntegralNumber()) {
            throw new Unreadable(relative + " is not an evidence report (no snapshotId and questions)");
        }
        return loaded;
    }

    /** The file's JSON, read once per resolved path; the path must lie inside the evidence root. Records the read on the evaluation. */
    private Loaded load(String relative, Path base, Evaluation evaluation) {
        Path resolved = resolve(relative, base);
        Object read = files.computeIfAbsent(resolved, path -> {
            try {
                return JSON.readTree(Files.readString(path));
            } catch (IOException | JacksonException failure) {
                return "file " + relative + " cannot be read as JSON: " + firstLine(failure.getMessage());
            }
        });
        if (read instanceof String error) throw new Unreadable(error);
        if (!(read instanceof JsonNode json)) throw new Unreadable("file " + relative + " was read as diagnostic output by another check, not as JSON evidence");
        Loaded loaded = new Loaded(resolved, relative, json);
        referenced.add(resolved);
        if (evaluation != null) evaluation.references.add(loaded);
        return loaded;
    }

    /**
     * The file's {@code ANSWER_VISIBILITY} lines ({@link DiagnosticOutput}), read once per resolved path; the path must lie inside the
     * evidence root. Recorded on the evaluation as a reference with no snapshot id, so an experiment claim reading it is refused.
     */
    private DiagnosticOutput diagnostic(String relative, Evaluation evaluation) {
        Path resolved = resolve(relative, directory);
        Object read = files.computeIfAbsent(resolved, path -> {
            try {
                return DiagnosticOutput.parse(Files.readString(path));
            } catch (IOException failure) {
                return "file " + relative + " cannot be read: " + firstLine(failure.getMessage());
            }
        });
        if (read instanceof String error) throw new Unreadable(error);
        if (!(read instanceof DiagnosticOutput output)) throw new Unreadable("file " + relative + " was read as JSON evidence by another check, not as diagnostic output");
        if (output.lines().isEmpty()) throw new Unreadable("file " + relative + " holds no ANSWER_VISIBILITY line");
        referenced.add(resolved);
        evaluation.references.add(new Loaded(resolved, relative, JSON.createObjectNode()));
        return output;
    }

    /** "the diagnostic output <path as written>" or "<label> (the diagnostic output <path as written>)". */
    private String outputReference(String relative) {
        String label = labels.get(resolve(relative, directory));
        String reference = "the diagnostic output " + relative;
        return label == null ? reference : label + " (" + reference + ")";
    }

    /**
     * The one line of the experiment matching the selectors; none or several is unreadable, naming the count found. With a line number, that
     * line, which must be a line of the experiment and match the other selectors.
     */
    private static DiagnosticOutput.Line oneLine(DiagnosticOutput output, String relative, String experiment, Integer number, String question, Long chunk, Integer sizeChars) {
        List<DiagnosticOutput.Line> lines = output.select(experiment, question, chunk, sizeChars).stream().filter(line -> number == null || line.number() == number).toList();
        if (lines.size() != 1) {
            throw new Unreadable(relative + " has " + (lines.isEmpty() ? "no " + experiment + " line" : lines.size() + " " + experiment + " lines") + " matching "
                    + selectors(number, question, chunk, sizeChars) + (lines.isEmpty() ? "" : " (lines " + lines.stream().map(line -> String.valueOf(line.number())).collect(Collectors.joining(", ")) + ")")
                    + ", expected exactly one");
        }
        return lines.get(0);
    }

    private static String selectors(Integer number, String question, Long chunk, Integer sizeChars) {
        List<String> parts = new ArrayList<>();
        if (number != null) parts.add("line " + number);
        if (question != null) parts.add("question " + question);
        if (chunk != null) parts.add("chunk " + chunk);
        if (sizeChars != null) parts.add(sizeChars + " characters");
        return parts.isEmpty() ? "no selector" : String.join(", ", parts);
    }

    /** "the <experiment>[ TOTALS] line[ of <question>][, chunk <chunk>][, at <n> characters]". */
    private static String lineDescription(String experiment, boolean totals, String question, Long chunk, Integer sizeChars) {
        return "the " + experiment + (totals ? " TOTALS" : "") + " line" + (question == null ? "" : " of " + question) + (chunk == null ? "" : ", chunk " + chunk)
                + (sizeChars == null ? "" : ", at " + sizeChars + " characters");
    }

    // Template (RAG.md, Claims, Sentences): "Line <n> of <Output>, the <experiment>[ TOTALS] line[ of <question>][, chunk <id>][, at <size>
    // characters], records <field> <value as printed>."
    /**
     * One field of one printed line, compared as printed: a number expected against a value that reads as a number by numeric value,
     * anything else as text against the printed text (a boolean as {@code true} or {@code false}, a list with its brackets, a quoted phrase
     * with its quotes). The selectors (a line number, the question, the chunk, the size) must match exactly one line of the experiment.
     */
    private String diagnosticLine(Claim claim, JsonNode check, Evaluation evaluation) {
        String outputFile = requireText(check, "output");
        String experiment = requireText(check, "experiment");
        String field = requireText(check, "field");
        Integer number = optionalSize(check, "line");
        String question = optionalText(check, "question");
        Long chunk = optionalLong(check, "chunk");
        Integer sizeChars = optionalSize(check, "sizeChars");
        JsonNode expected = check.get("expected");
        if (expected == null || expected.isNull() || expected.isArray() || expected.isObject()) {
            throw new Unreadable("check.expected must be a number, string, or boolean as the line prints it, found " + (expected == null ? "none" : expected));
        }
        evaluation.subject(field + " of " + lineDescription(experiment, false, question, chunk, sizeChars) + (number == null ? "" : " (line " + number + ")") + " in " + outputFile);
        DiagnosticOutput output = diagnostic(outputFile, evaluation);
        DiagnosticOutput.Line line = oneLine(output, outputFile, experiment, number, question, chunk, sizeChars);
        String value = line.field(field);
        if (value == null) throw new Unreadable("line " + line.number() + " has no field " + field + " (its fields: " + String.join(", ", line.fields().keySet()) + ")");
        if (!printedEquals(expected, value)) evaluation.differs(field, render(expected), value + " (line " + line.number() + ")");
        basis(claim, "observed", evaluation);
        return "Line " + line.number() + " of " + outputReference(outputFile) + ", " + lineDescription(experiment, line.fields().containsKey("TOTALS"), question, chunk, sizeChars)
                + ", records " + field + " " + value + ".";
    }

    /** A number against a printed number by value; otherwise the rendered expectation against the printed text. */
    private static boolean printedEquals(JsonNode expected, String printed) {
        if (expected.isNumber()) {
            try {
                return expected.decimalValue().compareTo(new BigDecimal(printed)) == 0;
            } catch (NumberFormatException notANumber) {
                return false;
            }
        }
        return render(expected).equals(printed);
    }

    // Template: "<Output> holds <n> <experiment> lines naming a question[ at <size> characters]."
    /** The number of the experiment's lines that name a question (the setup line and a TOTALS line are not counted), at one size when given. */
    private String diagnosticCount(Claim claim, JsonNode check, Evaluation evaluation) {
        String outputFile = requireText(check, "output");
        String experiment = requireText(check, "experiment");
        Integer sizeChars = optionalSize(check, "sizeChars");
        JsonNode expected = check.get("expected");
        if (expected == null || !expected.isIntegralNumber() || expected.intValue() < 0) {
            throw new Unreadable("check.expected must be a line count (non-negative integer), found " + (expected == null ? "none" : expected));
        }
        evaluation.subject(experiment + " lines" + (sizeChars == null ? "" : " at " + sizeChars + " characters") + " in " + outputFile);
        DiagnosticOutput output = diagnostic(outputFile, evaluation);
        long count = output.select(experiment, null, null, sizeChars).stream().filter(DiagnosticOutput.Line::measurement).count();
        if (count != expected.intValue()) evaluation.differs("count", String.valueOf(expected.intValue()), String.valueOf(count));
        basis(claim, "derived", evaluation);
        return capitalize(outputReference(outputFile)) + " holds " + count + " " + experiment + " lines naming a question" + (sizeChars == null ? "" : " at " + sizeChars + " characters") + ".";
    }

    /** The counts of the frozen size rule at one size (plan {@code 2026-09-17-chunk-size.md}), over the chunkSize lines at that size. */
    private record SizeCounts(int sizeChars, Integer overlapChars, int phrases, int firstRanked, int split, int unseen, List<String> notFirst,
            List<String> splitPhrases, List<String> unseenPhrases) {
    }

    /**
     * Over the chunkSize lines at {@code sizeChars}: a line is one located phrase; first-ranked when its {@code bestRank} is 1; split when it
     * prints {@code noPieceHoldsPhrase=true} or {@code piecesHoldingPhrase=0}; unseen when it is not split and no holder prints
     * {@code phraseSeen=true}. A line that is neither split nor prints an integer {@code bestRank} is unreadable, as is a size with no line.
     */
    private static SizeCounts sizeCounts(DiagnosticOutput output, String relative, int sizeChars) {
        List<DiagnosticOutput.Line> lines = output.select("chunkSize", null, null, sizeChars).stream().filter(DiagnosticOutput.Line::measurement).toList();
        if (lines.isEmpty()) throw new Unreadable(relative + " has no chunkSize line at " + sizeChars + " characters");
        int firstRanked = 0;
        int split = 0;
        int unseen = 0;
        Integer overlap = null;
        List<String> notFirst = new ArrayList<>();
        List<String> splitPhrases = new ArrayList<>();
        List<String> unseenPhrases = new ArrayList<>();
        for (DiagnosticOutput.Line line : lines) {
            String phrase = line.field("question") + " (stored chunk " + line.field("storedChunk") + ", line " + line.number() + ")";
            if ("true".equals(line.field("noPieceHoldsPhrase")) || "0".equals(line.field("piecesHoldingPhrase"))) {
                split++;
                splitPhrases.add(phrase);
                continue;
            }
            String printedOverlap = line.field("overlapChars");
            if (printedOverlap != null) {
                int value = parseInt(printedOverlap, "overlapChars on line " + line.number());
                if (overlap != null && overlap != value) throw new Unreadable("overlapChars differs among the chunkSize lines at " + sizeChars + " characters (" + overlap + " and " + value + " on line " + line.number() + ")");
                overlap = value;
            }
            String bestRank = line.field("bestRank");
            if (bestRank == null) throw new Unreadable("line " + line.number() + " prints neither bestRank nor noPieceHoldsPhrase=true");
            int rank = parseInt(bestRank, "bestRank on line " + line.number());
            if (rank == 1) firstRanked++;
            else notFirst.add(phrase + " bestRank " + rank);
            if (line.holders().stream().noneMatch(holder -> "true".equals(holder.get("phraseSeen")))) {
                unseen++;
                unseenPhrases.add(phrase);
            }
        }
        return new SizeCounts(sizeChars, overlap, lines.size(), firstRanked, split, unseen, notFirst, splitPhrases, unseenPhrases);
    }

    private static int parseInt(String printed, String what) {
        try {
            return Integer.parseInt(printed);
        } catch (NumberFormatException notAnInteger) {
            throw new Unreadable(what + " expected an integer, found " + printed);
        }
    }

    // Template: "At <size> characters[ (overlap <o>)], <Output> records <p> phrases: <f> with the holding piece ranked first in its section
    // pool, <s> split by a piece boundary, and <u> whose holding pieces are seen by no scored row."
    /** The size rule's counts at one size; every expected field is required, and a failure names the phrases behind each differing count. */
    private String sizeTable(Claim claim, JsonNode check, Evaluation evaluation) {
        String outputFile = requireText(check, "output");
        Integer sizeChars = optionalSize(check, "sizeChars");
        if (sizeChars == null) throw new Unreadable("check.sizeChars must be a positive integer, found none");
        JsonNode expected = expected(check, "sizeTable");
        for (String key : EXPECTED_KEYS.get("sizeTable")) {
            JsonNode value = expected.get(key);
            if (value == null || !value.isIntegralNumber() || value.intValue() < 0) throw new Unreadable("check.expected." + key + " must be a non-negative integer, found " + value);
        }
        evaluation.subject(sizeChars + " characters in " + outputFile);
        DiagnosticOutput output = diagnostic(outputFile, evaluation);
        SizeCounts counts = sizeCounts(output, outputFile, sizeChars);
        if (counts.phrases() != expected.get("phrases").intValue()) evaluation.differs("phrases", expected.get("phrases").toString(), String.valueOf(counts.phrases()));
        if (counts.firstRanked() != expected.get("firstRanked").intValue()) {
            evaluation.differs("firstRanked", expected.get("firstRanked").toString(), counts.firstRanked() + (counts.notFirst().isEmpty() ? "" : " (not first: " + String.join(", ", counts.notFirst()) + ")"));
        }
        if (counts.split() != expected.get("split").intValue()) {
            evaluation.differs("split", expected.get("split").toString(), counts.split() + (counts.split() == 0 ? "" : " (" + String.join(", ", counts.splitPhrases()) + ")"));
        }
        if (counts.unseen() != expected.get("unseen").intValue()) {
            evaluation.differs("unseen", expected.get("unseen").toString(), counts.unseen() + (counts.unseen() == 0 ? "" : " (" + String.join(", ", counts.unseenPhrases()) + ")"));
        }
        basis(claim, "derived", evaluation);
        return "At " + sizeChars + " characters" + (counts.overlapChars() == null ? "" : " (overlap " + counts.overlapChars() + ")") + ", " + outputReference(outputFile) + " records "
                + counts.phrases() + " phrases: " + counts.firstRanked() + " with the holding piece ranked first in its section pool, " + counts.split()
                + " split by a piece boundary, and " + counts.unseen() + " whose holding pieces are seen by no scored row.";
    }

    // Template: "Under the frozen size rule over <Output> (first-ranked phrases: <size> characters <n>, ...; split phrases: <size> characters <n>,
    // ...; a size with a split phrase is excluded), the chosen size is <size> characters." | "..., no size is chosen: no remaining size has more
    // first-ranked phrases than the stored <size> characters." | "..., no size is chosen: no candidate size remains."
    /**
     * The frozen size-choice rule of plan {@code 2026-09-17-chunk-size.md}, computed from the chunkSize lines: among the candidate sizes
     * without a split phrase, the one with the most first-ranked phrases, the larger on a tie; none when no remaining size has more
     * first-ranked phrases than the stored size. {@code expected} is the chosen size or the string {@code none}.
     */
    private String sizeChoice(Claim claim, JsonNode check, Evaluation evaluation) {
        String outputFile = requireText(check, "output");
        Integer stored = optionalSize(check, "stored");
        if (stored == null) throw new Unreadable("check.stored must be a positive integer, found none");
        JsonNode candidatesNode = check.get("candidates");
        if (candidatesNode == null || !candidatesNode.isArray() || candidatesNode.isEmpty()) throw new Unreadable("check.candidates must list at least one size, found " + candidatesNode);
        List<Integer> candidates = new ArrayList<>();
        for (JsonNode candidate : candidatesNode) {
            if (!candidate.isIntegralNumber() || candidate.intValue() < 1) throw new Unreadable("check.candidates must hold positive integers, found " + candidate);
            if (candidate.intValue() == stored || candidates.contains(candidate.intValue())) throw new Unreadable("check.candidates must be distinct sizes other than the stored " + stored + ", found " + candidatesNode);
            candidates.add(candidate.intValue());
        }
        JsonNode expected = check.get("expected");
        boolean none = expected != null && expected.isString() && expected.stringValue().equals("none");
        if (!none && (expected == null || !expected.isIntegralNumber() || !candidates.contains(expected.intValue()))) {
            throw new Unreadable("check.expected must be one of the candidate sizes or \"none\", found " + (expected == null ? "none given" : expected));
        }
        evaluation.subject("stored " + stored + ", candidates " + candidates + " in " + outputFile);
        DiagnosticOutput output = diagnostic(outputFile, evaluation);
        SizeCounts storedCounts = sizeCounts(output, outputFile, stored);
        List<SizeCounts> all = new ArrayList<>();
        all.add(storedCounts);
        SizeCounts best = null;
        for (int candidate : candidates) {
            SizeCounts counts = sizeCounts(output, outputFile, candidate);
            all.add(counts);
            if (counts.split() > 0) continue;
            if (best == null || counts.firstRanked() > best.firstRanked() || counts.firstRanked() == best.firstRanked() && counts.sizeChars() > best.sizeChars()) best = counts;
        }
        Integer chosen = best != null && best.firstRanked() > storedCounts.firstRanked() ? best.sizeChars() : null;
        Integer wanted = none ? null : expected.intValue();
        if (!Objects.equals(chosen, wanted)) {
            evaluation.differs("chosen size", wanted == null ? "none" : String.valueOf(wanted), (chosen == null ? "none" : String.valueOf(chosen)) + " (first-ranked: "
                    + all.stream().map(c -> c.sizeChars() + " " + c.firstRanked()).collect(Collectors.joining(", ")) + "; split: "
                    + all.stream().map(c -> c.sizeChars() + " " + c.split()).collect(Collectors.joining(", ")) + ")");
        }
        basis(claim, "derived", evaluation);
        String counts = "first-ranked phrases: " + all.stream().map(c -> c.sizeChars() + " characters " + c.firstRanked()).collect(Collectors.joining(", "))
                + "; split phrases: " + all.stream().map(c -> c.sizeChars() + " characters " + c.split()).collect(Collectors.joining(", ")) + "; a size with a split phrase is excluded";
        String start = "Under the frozen size rule over " + outputReference(outputFile) + " (" + counts + "), ";
        if (chosen != null) return start + "the chosen size is " + chosen + " characters.";
        if (best == null) return start + "no size is chosen: no candidate size remains.";
        return start + "no size is chosen: no remaining size has more first-ranked phrases than the stored " + stored + " characters.";
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) return null;
        if (!value.isString() || value.stringValue().isBlank()) throw new Unreadable("check." + field + " must be a non-blank string, found " + value);
        return value.stringValue();
    }

    private static Long optionalLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) return null;
        if (!value.isIntegralNumber()) throw new Unreadable("check." + field + " must be an integer, found " + value);
        return value.longValue();
    }

    private static Integer optionalSize(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) return null;
        if (!value.isIntegralNumber() || value.intValue() < 1) throw new Unreadable("check." + field + " must be a positive integer, found " + value);
        return value.intValue();
    }

    private Path resolve(String relative, Path base) {
        if (Path.of(relative).isAbsolute()) throw new Unreadable("file " + relative + " must be relative to the claims file");
        Path resolved = base.resolve(relative).normalize();
        String outside = outside(resolved, root);
        if (outside != null) throw new Unreadable("file " + relative + " " + outside);
        if (!Files.isRegularFile(resolved)) throw new Unreadable("file " + relative + " not found (resolved to " + display(resolved) + ")");
        return resolved;
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) throw new Unreadable("check." + field + " must be a non-blank string, found " + value);
        return value.stringValue();
    }

    private static long requireLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) throw new Unreadable("check." + field + " must be an integer, found " + value);
        return value.longValue();
    }

    private static BigDecimal scale6(JsonNode node) {
        if (node == null) return null;
        BigDecimal value;
        try {
            if (node.isNumber()) value = node.decimalValue();
            else if (node.isString()) value = new BigDecimal(node.stringValue());
            else return null;
            return value.setScale(6, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException | NumberFormatException notScale6) {
            return null;
        }
    }

    /** The value at six decimal places; a stored value with more decimal places is a problem, never rounded. */
    private static String six(BigDecimal value, String what) {
        try {
            return value.setScale(6, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException moreDecimals) {
            throw new Unreadable(what + " expected at most six decimal places, found " + value.toPlainString());
        }
    }

    static String metricName(String metric) {
        if (metric.startsWith("tickerHitAt5.")) return metric.substring("tickerHitAt5.".length()) + " hit@5";
        if (metric.startsWith("slices.")) {
            String[] parts = metric.split("\\.", 3);
            return (parts[1].equals("figure") ? "figure-slice " : "non-figure-slice ") + metricName(parts[2]);
        }
        return switch (metric) {
            case "hitAt1" -> "hit@1";
            case "hitAt3" -> "hit@3";
            case "hitAt5" -> "hit@5";
            default -> "MRR";
        };
    }

    static String ordinal(int value) {
        int lastTwo = Math.abs(value) % 100;
        int last = Math.abs(value) % 10;
        String suffix = lastTwo >= 11 && lastTwo <= 13 ? "th" : last == 1 ? "st" : last == 2 ? "nd" : last == 3 ? "rd" : "th";
        return value + suffix;
    }

    static String joinAnd(List<String> items) {
        if (items.size() <= 1) return String.join("", items);
        if (items.size() == 2) return items.get(0) + " and " + items.get(1);
        return String.join(", ", items.subList(0, items.size() - 1)) + ", and " + items.get(items.size() - 1);
    }

    private static boolean oneLine(String text) {
        return !text.isBlank() && !text.contains("\n") && !text.contains("\r") && !text.contains("<!--") && !text.contains("-->");
    }

    private static boolean blank(JsonNode node) {
        return node == null || !node.isString() || node.stringValue().isBlank();
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** The first letter lower-cased when the second is lower case ("Snapshot 4 ranks" but not "NVDA hit@5"). */
    private static String decapitalize(String text) {
        return text.length() < 2 || !Character.isLowerCase(text.charAt(1)) ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    private static String withoutPeriod(String sentence) {
        return sentence.endsWith(".") ? sentence.substring(0, sentence.length() - 1) : sentence;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : value == null || value.isNull() ? null : value.toString();
    }

    static String rankText(Integer rank) {
        return rank == null ? "rank null" : "rank " + rank;
    }

    private static String firstLine(String message) {
        return message == null ? "" : message.lines().findFirst().orElse("");
    }

    private static String quote(String text) {
        return text == null ? "none" : "\"" + text + "\"";
    }

    private static String abbreviate(String text) {
        return text.length() <= 120 ? text : text.substring(0, 117) + "...";
    }
}
