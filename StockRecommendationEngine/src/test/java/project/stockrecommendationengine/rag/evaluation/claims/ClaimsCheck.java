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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks one measurement's {@code claims.json} against committed evidence files only (RAG.md, Claims): evaluation snapshots as
 * {@code row_to_json} exports or API JSON, and evidence reports ({@code GET /api/rag/evaluate/{id}/evidence}) as JSON. It never reads the
 * database or calls a model, so it runs in {@code verify}. Paths in a claim resolve against the claims file's directory.
 * <p>
 * {@link #check} returns every problem of the file, one line each, naming the claim id, the check type, and what the claim expects beside
 * what the evidence holds; nothing stops at the first problem.
 */
public final class ClaimsCheck {
    static final List<String> BASES = List.of("observed", "derived", "inferred", "unknown", "experiment");
    static final List<String> CHECK_TYPES = List.of("rank", "topK", "metric", "ruleRow", "phraseSpan", "membership", "candidate", "notRecorded");
    static final List<String> CRITERIA = List.of("aggregateHitAt5", "nonFigureHitAt5", "tickerHitAt5", "figureKindTop5");
    static final Pattern ID = Pattern.compile("C-\\d{3,}");
    static final Pattern BLOCK = Pattern.compile("[A-Za-z0-9_-]+");
    /**
     * Causal wording (RAG.md, Claims, Causes): a sentence matching this needs basis {@code experiment}. It lists words, not meanings, so a
     * cause stated another way ("X rose once Y changed") is not caught; Scrutiny still reads every sentence.
     */
    static final Pattern CAUSAL = Pattern.compile("\\b(because|cause|caused|causes|causing|due to|owing to|explain|explains|explained|lift|lifts|lifted"
            + "|led to|lead to|leads to|as a result|resulted in|so that|therefore|hence|thanks to|drove|driven by|attributable to)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern METRIC = Pattern.compile("(hitAt1|hitAt3|hitAt5|mrr)|slices\\.(figure|nonFigure)\\.(hitAt1|hitAt3|hitAt5|mrr)"
            + "|tickerHitAt5\\.([A-Z0-9.-]{1,16})");
    private static final Set<String> CLAIM_KEYS = Set.of("id", "text", "basis", "block", "check", "from", "experiment");
    private static final Map<String, Set<String>> CHECK_KEYS = Map.of(
            "rank", Set.of("type", "snapshot", "question", "expected"),
            "topK", Set.of("type", "question", "k", "rows"),
            "metric", Set.of("type", "snapshot", "metric", "expected"),
            "ruleRow", Set.of("type", "reference", "candidate", "criteria"),
            "phraseSpan", Set.of("type", "report", "question", "phrase", "chunk", "occurrence", "expected"),
            "membership", Set.of("type", "report", "question", "phrase", "chunk", "occurrence", "expected"),
            "candidate", Set.of("type", "report", "question", "chunk", "expected"),
            "notRecorded", Set.of("type", "report", "path", "reason"));
    private static final Map<String, Set<String>> EXPECTED_KEYS = Map.of(
            "phraseSpan", Set.of("tokenSpan", "characterSpan"),
            "membership", Set.of("head", "windowsHoldingWholly"),
            "candidate", Set.of("fusedPosition", "rerankInput", "rerankedPosition"));

    static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    /** One claim as written; {@code index} is its 1-based position in the file. */
    public record Claim(int index, String id, String text, String basis, String block, JsonNode check, JsonNode from, JsonNode experiment) {
        String label() {
            return id != null ? id : "claim #" + index;
        }
    }

    /** The claims of a file in order, and the problems that stopped the file or a claim from being read. */
    public record Parsed(Path file, List<Claim> claims, List<String> problems) {
    }

    private final Path file;
    private final Path directory;
    private final String name;
    private final Map<Path, Object> files = new HashMap<>();
    private final Map<String, Claim> byId = new LinkedHashMap<>();
    private final Map<String, List<String>> evaluated = new HashMap<>();

    private ClaimsCheck(Path file) {
        this.file = file;
        this.directory = file.toAbsolutePath().getParent();
        this.name = display(file);
    }

    /** Every problem of the claims file, in claim order; empty when every claim holds. */
    public static List<String> check(Path claimsFile) {
        return new ClaimsCheck(claimsFile).run();
    }

    /** The claims of a file as written, for the generator; problems name what could not be read. */
    public static Parsed parse(Path claimsFile) {
        ClaimsCheck check = new ClaimsCheck(claimsFile);
        List<String> problems = new ArrayList<>();
        List<Claim> claims = check.read(problems);
        return new Parsed(claimsFile, claims, problems);
    }

    /** The path as shown in problem lines: relative to the working directory when under it. */
    static String display(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path cwd = Path.of("").toAbsolutePath();
        return absolute.startsWith(cwd) ? cwd.relativize(absolute).toString() : absolute.toString();
    }

    private List<String> run() {
        List<String> problems = new ArrayList<>();
        List<Claim> claims = read(problems);
        if (claims == null) return problems;
        for (Claim claim : claims) problems.addAll(evaluate(claim, new ArrayDeque<>()));
        return problems;
    }

    private List<Claim> read(List<String> problems) {
        JsonNode root;
        try {
            root = JSON.readTree(Files.readString(file));
        } catch (IOException | JacksonException failure) {
            problems.add(name + ": cannot be read as JSON: " + failure.getMessage().lines().findFirst().orElse(""));
            return null;
        }
        if (!root.isObject() || !root.path("claims").isArray()) {
            problems.add(name + ": expected an object with a \"claims\" array, found " + abbreviate(root.toString()));
            return null;
        }
        for (String key : root.propertyNames()) {
            if (!key.equals("claims") && !key.equals("description")) problems.add(name + ": unknown top-level key \"" + key + "\"");
        }
        List<Claim> claims = new ArrayList<>();
        int index = 0;
        for (JsonNode node : root.get("claims")) {
            index++;
            if (!node.isObject()) {
                problems.add(name + " claim #" + index + ": expected an object, found " + abbreviate(node.toString()));
                continue;
            }
            Claim claim = new Claim(index, text(node, "id"), text(node, "text"), text(node, "basis"), text(node, "block"), node.get("check"),
                    node.get("from"), node.get("experiment"));
            claims.add(claim);
            if (claim.id() != null && ID.matcher(claim.id()).matches() && byId.putIfAbsent(claim.id(), claim) != null) {
                problems.add(name + " claim #" + index + ": id " + claim.id() + " is already used by claim #" + byId.get(claim.id()).index());
            }
            for (String key : node.propertyNames()) {
                if (!CLAIM_KEYS.contains(key)) problems.add(name + " " + claim.label() + ": unknown key \"" + key + "\"");
            }
        }
        return claims;
    }

    /** The claim's own problems; premises of an inferred claim are evaluated once and reused. */
    private List<String> evaluate(Claim claim, Deque<String> visiting) {
        if (claim.id() != null && evaluated.containsKey(claim.id()) && byId.get(claim.id()) == claim) return evaluated.get(claim.id());
        List<String> problems = new ArrayList<>();
        String prefix = name + " " + claim.label();
        List<String> structure = structure(claim);
        if (!structure.isEmpty()) {
            structure.forEach(problem -> problems.add(prefix + " " + problem));
        } else {
            Matcher causal = CAUSAL.matcher(claim.text());
            if (causal.find() && !claim.basis().equals("experiment")) {
                problems.add(prefix + " [causal wording]: \"" + causal.group(1) + "\" states a cause: basis expected experiment (with an experiment file"
                        + " varying only that factor), found " + claim.basis());
            }
            switch (claim.basis()) {
                case "inferred" -> problems.addAll(premises(claim, prefix, visiting));
                case "experiment" -> {
                    problems.addAll(experiment(claim, prefix));
                    problems.addAll(evaluateCheck(claim, prefix));
                }
                default -> problems.addAll(evaluateCheck(claim, prefix));
            }
        }
        if (claim.id() != null && byId.get(claim.id()) == claim) evaluated.put(claim.id(), problems);
        return problems;
    }

    /** Shape problems that stop a claim from being evaluated. */
    private List<String> structure(Claim claim) {
        List<String> problems = new ArrayList<>();
        if (claim.id() == null || !ID.matcher(claim.id()).matches()) {
            problems.add("[format]: id expected C- followed by at least three digits, found " + quote(claim.id()));
        }
        if (claim.text() == null || claim.text().isBlank() || claim.text().contains("\n") || claim.text().contains("\r")
                || claim.text().contains("<!--") || claim.text().contains("-->")) {
            problems.add("[format]: text expected one non-blank line without comment markers, found " + quote(claim.text()));
        }
        if (claim.basis() == null || !BASES.contains(claim.basis())) {
            problems.add("[format]: basis expected one of " + String.join(", ", BASES) + ", found " + quote(claim.basis()));
            return problems;
        }
        if (claim.block() != null && !BLOCK.matcher(claim.block()).matches()) {
            problems.add("[format]: block expected letters, digits, _ or -, found " + quote(claim.block()));
        }
        boolean inferred = claim.basis().equals("inferred");
        if (claim.from() != null && !inferred) problems.add("[format]: \"from\" is only for basis inferred, found on basis " + claim.basis());
        if (claim.experiment() != null && !claim.basis().equals("experiment")) {
            problems.add("[format]: \"experiment\" is only for basis experiment, found on basis " + claim.basis());
        }
        if (inferred) {
            if (claim.check() != null) problems.add("[inferred]: check expected none (an inference is not evaluated), found " + abbreviate(claim.check().toString()));
            if (claim.from() == null || !claim.from().isArray() || claim.from().isEmpty()) {
                problems.add("[inferred]: premises expected at least one claim id in \"from\", found " + (claim.from() == null ? "none" : claim.from().toString()));
            }
            return problems;
        }
        if (claim.check() == null || !claim.check().isObject()) {
            problems.add("[format]: check expected an object with a type, found " + (claim.check() == null ? "none" : abbreviate(claim.check().toString())));
            return problems;
        }
        String type = claim.check().path("type").asString("");
        if (!CHECK_TYPES.contains(type)) {
            problems.add("[format]: check type expected one of " + String.join(", ", CHECK_TYPES) + ", found " + quote(type));
            return problems;
        }
        if (claim.basis().equals("unknown") && !type.equals("notRecorded")) {
            problems.add("[" + type + "]: basis unknown expected check type notRecorded, found " + type);
        }
        if (type.equals("notRecorded") && !claim.basis().equals("unknown")) {
            problems.add("[notRecorded]: check type notRecorded expected basis unknown, found " + claim.basis());
        }
        if (claim.basis().equals("experiment") && (claim.experiment() == null || !claim.experiment().isObject())) {
            problems.add("[experiment]: experiment expected an object with file and factor, found " + (claim.experiment() == null ? "none" : claim.experiment().toString()));
        }
        for (String key : claim.check().propertyNames()) {
            if (!CHECK_KEYS.get(type).contains(key)) problems.add("[" + type + "]: unknown check key \"" + key + "\"");
        }
        return problems;
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
                List<String> failing = evaluate(byId.get(id), visiting);
                if (!failing.isEmpty()) {
                    problems.add(prefix + " [inferred]: premise " + id + " expected to pass, found " + failing.size() + (failing.size() == 1 ? " problem" : " problems")
                            + " (listed under " + id + ")");
                }
            }
        }
        visiting.pop();
        return problems;
    }

    private List<String> experiment(Claim claim, String prefix) {
        List<String> problems = new ArrayList<>();
        JsonNode reference = claim.experiment();
        String label = prefix + " [experiment]: ";
        for (String key : reference.propertyNames()) {
            if (!key.equals("file") && !key.equals("factor")) problems.add(label + "unknown experiment key \"" + key + "\"");
        }
        String experimentFile = text(reference, "file");
        String factor = text(reference, "factor");
        if (experimentFile == null || factor == null || factor.isBlank()) {
            problems.add(label + "experiment expected a file and a factor, found " + reference);
            return problems;
        }
        Object read = read(experimentFile);
        if (read instanceof String error) {
            problems.add(label + error);
            return problems;
        }
        JsonNode declared = ((JsonNode) read).path("experiment");
        String declaredFactor = declared.path("factor").isString() ? declared.path("factor").stringValue() : null;
        JsonNode settings = declared.path("settings");
        if (declaredFactor == null) {
            problems.add(label + experimentFile + " expected an \"experiment\" object naming its factor, found " + abbreviate(declared.toString()));
        } else if (!declaredFactor.equals(factor)) {
            problems.add(label + experimentFile + " factor expected \"" + factor + "\", found \"" + declaredFactor + "\"");
        }
        if (!settings.isArray() || settings.size() != 2 || settings.get(0).equals(settings.get(1))) {
            problems.add(label + experimentFile + " settings expected exactly two different settings of the factor, found "
                    + (settings.isMissingNode() ? "none" : settings.toString()));
        }
        return problems;
    }

    private List<String> evaluateCheck(Claim claim, String prefix) {
        JsonNode check = claim.check();
        String type = check.path("type").asString();
        Evaluation evaluation = new Evaluation(prefix + " [" + type + "] ");
        try {
            switch (type) {
                case "rank" -> rank(claim, check, evaluation);
                case "topK" -> topK(claim, check, evaluation);
                case "metric" -> metric(claim, check, evaluation);
                case "ruleRow" -> ruleRow(claim, check, evaluation);
                case "phraseSpan", "membership" -> occurrence(claim, check, type, evaluation);
                case "candidate" -> candidate(claim, check, evaluation);
                case "notRecorded" -> notRecorded(check, evaluation);
                default -> throw new IllegalStateException(type);
            }
        } catch (Unreadable | IllegalArgumentException unreadable) {
            evaluation.fail(unreadable.getMessage());
        }
        return evaluation.problems();
    }

    /** Collects a check's differences into one problem line naming the subject. */
    private static final class Evaluation {
        private final String prefix;
        private String subject = "";
        private final List<String> details = new ArrayList<>();

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

    private void rank(Claim claim, JsonNode check, Evaluation evaluation) {
        String snapshotFile = requireText(check, "snapshot");
        String question = requireText(check, "question");
        JsonNode expected = check.get("expected");
        if (expected == null || !(expected.isNull() || expected.isIntegralNumber())) {
            throw new Unreadable("check.expected must be a rank (integer) or null, found " + (expected == null ? "none" : expected));
        }
        evaluation.subject("question " + question + " in " + snapshotFile);
        Snapshot snapshot = snapshot(snapshotFile);
        Integer found = snapshot.rank(question);
        Integer wanted = expected.isNull() ? null : expected.intValue();
        if (!java.util.Objects.equals(found, wanted)) evaluation.differs("rank", String.valueOf(wanted), String.valueOf(found));
        basis(claim, "observed", evaluation);
    }

    private void topK(Claim claim, JsonNode check, Evaluation evaluation) {
        String question = requireText(check, "question");
        JsonNode k = check.get("k");
        if (k == null || !k.isIntegralNumber() || k.intValue() < 1) throw new Unreadable("check.k must be a positive integer, found " + k);
        JsonNode rows = check.get("rows");
        if (rows == null || !rows.isArray() || rows.isEmpty()) throw new Unreadable("check.rows must list at least one {snapshot, expected}, found " + rows);
        evaluation.subject("question " + question + ", k " + k.intValue());
        for (JsonNode row : rows) {
            String snapshotFile = requireText(row, "snapshot");
            String expected = requireText(row, "expected");
            if (!expected.equals("inside") && !expected.equals("outside")) throw new Unreadable("rows expected must be inside or outside, found " + expected);
            Integer rank = snapshot(snapshotFile).rank(question);
            String found = rank != null && rank <= k.intValue() ? "inside" : "outside";
            if (!found.equals(expected)) evaluation.differs(snapshotFile, expected, found + " (" + rankText(rank) + ")");
        }
        basis(claim, "derived", evaluation);
    }

    private void metric(Claim claim, JsonNode check, Evaluation evaluation) {
        String snapshotFile = requireText(check, "snapshot");
        String metric = requireText(check, "metric");
        if (!METRIC.matcher(metric).matches()) {
            throw new Unreadable("check.metric must be hitAt1, hitAt3, hitAt5, mrr, slices.<figure|nonFigure>.<metric>, or tickerHitAt5.<TICKER>, found " + metric);
        }
        JsonNode expectedNode = check.get("expected");
        BigDecimal expected = scale6(expectedNode);
        if (expected == null) throw new Unreadable("check.expected must be a number with at most six decimal places, found " + expectedNode);
        evaluation.subject(metric + " in " + snapshotFile);
        BigDecimal stored = snapshot(snapshotFile).metric(metric);
        BigDecimal found = stored == null ? null : stored.setScale(6, RoundingMode.UNNECESSARY);
        if (found == null || found.compareTo(expected) != 0) evaluation.differs("value", expected.toPlainString(), found == null ? "none recorded" : found.toPlainString());
        basis(claim, "observed", evaluation);
    }

    private void ruleRow(Claim claim, JsonNode check, Evaluation evaluation) {
        String referenceFile = requireText(check, "reference");
        String candidateFile = requireText(check, "candidate");
        JsonNode criteria = check.get("criteria");
        if (criteria == null || !criteria.isObject() || criteria.isEmpty()) {
            throw new Unreadable("check.criteria must map at least one of " + String.join(", ", CRITERIA) + " to pass or fail, found " + criteria);
        }
        evaluation.subject(candidateFile + " against " + referenceFile);
        Snapshot reference = snapshot(referenceFile);
        Snapshot candidate = snapshot(candidateFile);
        if (!java.util.Objects.equals(reference.setVersion(), candidate.setVersion()) || !java.util.Objects.equals(reference.questionCount(), candidate.questionCount())) {
            evaluation.fail("snapshots expected the same set version and question count, found reference " + reference.setVersion() + "/"
                    + reference.questionCount() + " and candidate " + candidate.setVersion() + "/" + candidate.questionCount());
            return;
        }
        for (Map.Entry<String, JsonNode> criterion : criteria.properties()) {
            String expected = criterion.getValue().asString("");
            if (!CRITERIA.contains(criterion.getKey())) throw new Unreadable("unknown criterion " + criterion.getKey() + " (one of " + String.join(", ", CRITERIA) + ")");
            if (!expected.equals("pass") && !expected.equals("fail")) throw new Unreadable("criterion " + criterion.getKey() + " must be pass or fail, found " + criterion.getValue());
            String detail = criterion(criterion.getKey(), reference, candidate);
            String found = detail == null ? "pass" : "fail";
            if (!found.equals(expected)) evaluation.differs(criterion.getKey(), expected, found + (detail == null ? "" : " (" + detail + ")"));
        }
        basis(claim, "derived", evaluation);
    }

    /** Null when the candidate row meets the criterion against the reference; otherwise what fails it. */
    private static String criterion(String name, Snapshot reference, Snapshot candidate) {
        return switch (name) {
            case "aggregateHitAt5" -> notBelow("hitAt5", reference.metric("hitAt5"), candidate.metric("hitAt5"));
            case "nonFigureHitAt5" -> notBelow("slices.nonFigure.hitAt5", reference.metric("slices.nonFigure.hitAt5"), candidate.metric("slices.nonFigure.hitAt5"));
            case "tickerHitAt5" -> {
                List<String> lower = new ArrayList<>();
                for (String ticker : reference.tickers()) {
                    String detail = notBelow(ticker, reference.metric("tickerHitAt5." + ticker), candidate.metric("tickerHitAt5." + ticker));
                    if (detail != null) lower.add(detail);
                }
                yield lower.isEmpty() ? null : String.join(", ", lower);
            }
            case "figureKindTop5" -> {
                List<String> left = new ArrayList<>();
                for (String id : reference.figureKindInTop5()) {
                    Integer rank = candidate.rank(id);
                    if (rank == null || rank > 5) left.add(id + " (" + rankText(rank) + ", was " + reference.rank(id) + ")");
                }
                yield left.isEmpty() ? null : "left the top 5: " + String.join(", ", left);
            }
            default -> throw new IllegalStateException(name);
        };
    }

    private static String notBelow(String label, BigDecimal reference, BigDecimal candidate) {
        if (reference == null || candidate == null) return label + " not recorded (reference " + reference + ", candidate " + candidate + ")";
        return candidate.compareTo(reference) >= 0 ? null : label + " " + candidate.toPlainString() + " below " + reference.toPlainString();
    }

    private void occurrence(Claim claim, JsonNode check, String type, Evaluation evaluation) {
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
        JsonNode questionNode = reportQuestion(reportFile, question);
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
        compareValues(claim, occurrences.get(occurrence - 1), expected, evaluation);
    }

    private void candidate(Claim claim, JsonNode check, Evaluation evaluation) {
        String reportFile = requireText(check, "report");
        String question = requireText(check, "question");
        long chunk = requireLong(check, "chunk");
        JsonNode expected = expected(check, "candidate");
        evaluation.subject("question " + question + ", chunk " + chunk + " in " + reportFile);
        JsonNode questionNode = reportQuestion(reportFile, question);
        JsonNode chunkNode = null;
        for (JsonNode phraseNode : questionNode.path("phrases")) {
            for (JsonNode candidate : phraseNode.path("chunks")) {
                if (chunkNode == null && candidate.path("chunkId").asLong(-1) == chunk) chunkNode = candidate;
            }
        }
        if (chunkNode == null) throw new Unreadable("the report lists no chunk " + chunk + " holding an accepted phrase of " + question);
        compareValues(claim, chunkNode, expected, evaluation);
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
                evaluation.differs(field.getKey(), render(field.getValue()), render(value.get("value")) + " (" + basis + ")");
            }
        }
        if (!anyUnknown) basis(claim, anyDerived ? "derived" : "observed", evaluation);
    }

    private void notRecorded(JsonNode check, Evaluation evaluation) {
        String reportFile = requireText(check, "report");
        String path = requireText(check, "path");
        JsonNode reason = check.get("reason");
        if (reason != null && !reason.isString()) throw new Unreadable("check.reason must be a string, found " + reason);
        evaluation.subject(path + " in " + reportFile);
        JsonNode value = ReportPath.resolve(json(reportFile), path);
        if (value == null) throw new Unreadable("path names no value in the report");
        if (!value.isObject() || !value.has("basis")) throw new Unreadable("path names " + abbreviate(value.toString()) + ", not an evidence value");
        String basis = value.path("basis").asString("");
        if (!basis.equals("unknown")) {
            evaluation.fail("expected not recorded (unknown), found " + render(value.get("value")) + " (" + basis
                    + "): the value is now recorded, so the claim must be rewritten");
        } else if (reason != null && !reason.stringValue().equals(value.path("reason").asString(""))) {
            evaluation.differs("reason", quote(reason.stringValue()), quote(value.path("reason").asString("")));
        }
    }

    private static void basis(Claim claim, String evidenceBasis, Evaluation evaluation) {
        if (!claim.basis().equals("experiment") && !claim.basis().equals(evidenceBasis)) evaluation.differs("basis", claim.basis(), evidenceBasis);
    }

    private static JsonNode expected(JsonNode check, String type) {
        JsonNode expected = check.get("expected");
        if (expected == null || !expected.isObject() || expected.isEmpty()) {
            throw new Unreadable("check.expected must be an object with at least one of " + String.join(", ", EXPECTED_KEYS.get(type)) + ", found " + expected);
        }
        for (String key : expected.propertyNames()) {
            if (!EXPECTED_KEYS.get(type).contains(key)) throw new Unreadable("check.expected has unknown field " + key + " (one of " + String.join(", ", EXPECTED_KEYS.get(type)) + ")");
        }
        return expected;
    }

    /** Expected [start, end] against a report span {start, end}; everything else by JSON value (numbers by numeric value). */
    private static boolean sameValue(JsonNode expected, JsonNode found) {
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
        return expected.equals(found);
    }

    private static String render(JsonNode value) {
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

    private JsonNode reportQuestion(String reportFile, String question) {
        JsonNode report = json(reportFile);
        if (!report.path("questions").isArray() || !report.has("snapshotId")) throw new Unreadable(reportFile + " is not an evidence report (no snapshotId and questions)");
        for (JsonNode node : report.get("questions")) {
            if (question.equals(node.path("id").asString(null))) return node;
        }
        throw new Unreadable("the report has no question " + question);
    }

    private Snapshot snapshot(String relative) {
        return Snapshot.of(relative, json(relative));
    }

    private JsonNode json(String relative) {
        Object read = read(relative);
        if (read instanceof String error) throw new Unreadable(error);
        return (JsonNode) read;
    }

    /** A referenced file's JSON, or the reason it cannot be read; cached per resolved path. */
    private Object read(String relative) {
        if (Path.of(relative).isAbsolute()) return "file " + relative + " must be relative to the claims file";
        Path resolved = directory.resolve(relative).normalize();
        return files.computeIfAbsent(resolved, path -> {
            if (!Files.isRegularFile(path)) return "file " + relative + " not found (resolved to " + display(path) + ")";
            try {
                return JSON.readTree(Files.readString(path));
            } catch (IOException | JacksonException failure) {
                return "file " + relative + " cannot be read as JSON: " + failure.getMessage().lines().findFirst().orElse("");
            }
        });
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

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : value == null || value.isNull() ? null : value.toString();
    }

    static String rankText(Integer rank) {
        return rank == null ? "rank null" : "rank " + rank;
    }

    private static String quote(String text) {
        return text == null ? "none" : "\"" + text + "\"";
    }

    private static String abbreviate(String text) {
        return text.length() <= 120 ? text : text.substring(0, 117) + "...";
    }
}
