package project.stockrecommendationengine.rag.evaluation.claims;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    static final List<String> CHECK_TYPES = List.of("rank", "topK", "metric", "ruleRow", "phraseSpan", "membership", "candidate", "notRecorded");
    static final List<String> CRITERIA = List.of("aggregateHitAt5", "nonFigureHitAt5", "tickerHitAt5", "figureKindTop5");
    static final Pattern ID = Pattern.compile("C-\\d{3,}");
    static final Pattern BLOCK = Pattern.compile("[A-Za-z0-9_-]+");

    private static final Pattern METRIC = Pattern.compile("(hitAt1|hitAt3|hitAt5|mrr)|slices\\.(figure|nonFigure)\\.(hitAt1|hitAt3|hitAt5|mrr)"
            + "|tickerHitAt5\\.([A-Z0-9.-]{1,16})");
    private static final Set<String> FILE_KEYS = Set.of("claims", "description", "labels");
    private static final Set<String> CLAIM_KEYS = Set.of("id", "basis", "block", "check", "text", "from", "experiment");
    private static final Map<String, Set<String>> CHECK_KEYS = Map.of(
            "rank", Set.of("type", "snapshot", "question", "expected"),
            "topK", Set.of("type", "question", "k", "rows"),
            "metric", Set.of("type", "snapshot", "metric", "expected"),
            "ruleRow", Set.of("type", "reference", "candidate", "criteria"),
            "phraseSpan", Set.of("type", "report", "question", "phrase", "chunk", "occurrence", "expected"),
            "membership", Set.of("type", "report", "question", "phrase", "chunk", "occurrence", "expected"),
            "candidate", Set.of("type", "report", "question", "chunk", "expected"),
            "notRecorded", Set.of("type", "report", "path", "reason"));
    private static final Set<String> ROW_KEYS = Set.of("snapshot", "expected");
    private static final Map<String, List<String>> EXPECTED_KEYS = Map.of(
            "phraseSpan", List.of("tokenSpan", "characterSpan"),
            "membership", List.of("head", "windowsHoldingWholly"),
            "candidate", List.of("fusedPosition", "rerankInput", "rerankedPosition"));
    private static final Set<String> EXPERIMENT_REFERENCE_KEYS = Set.of("file", "factor");
    private static final Set<String> EXPERIMENT_FILE_KEYS = Set.of("experiment", "description");
    private static final Set<String> EXPERIMENT_KEYS = Set.of("factor", "settings", "snapshots");
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
        List<String> others = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(recorded.get(0).keySet());
        keys.addAll(recorded.get(1).keySet());
        for (String key : keys) {
            if (key.equals(factor)) continue;
            JsonNode a = recorded.get(0).get(key);
            JsonNode b = recorded.get(1).get(key);
            if (a == null || b == null || !sameValue(a, b)) {
                others.add(key + " " + (a == null ? "not recorded" : shown(a, b)) + " against " + (b == null ? "not recorded" : shown(b, a)));
            }
        }
        if (!others.isEmpty()) {
            found.add(experimentFile + " snapshots " + first + " and " + second + " expected to differ only in " + factor + ", found " + others.size()
                    + (others.size() == 1 ? " other recorded property differing: " : " other recorded properties differing: ") + String.join(", ", others));
        }
        if (!found.isEmpty()) {
            found.forEach(problem -> problems.add(label + problem));
            return null;
        }
        String rendered = factor + " " + render(settings.get(0)) + " in snapshot " + ids.get(0) + " against " + render(settings.get(1)) + " in snapshot " + ids.get(1);
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
        Loaded loaded = new Loaded(resolved, relative, (JsonNode) read);
        referenced.add(resolved);
        if (evaluation != null) evaluation.references.add(loaded);
        return loaded;
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
