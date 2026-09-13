package project.stockrecommendationengine.rag.evaluation.claims;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * Evaluation evidence Milestone 3, E1 and E2, over committed fixtures under {@code src/test/resources/evaluation/claims} (snapshots in both
 * file shapes, the scripted evidence reports with and without traces, experiment files): every check type passes on a true claim and fails
 * on a false one, and one message names every failing claim with its id, check type, expected value, and the value found.
 */
class ClaimsCheckTests {
    static final Path FIXTURES = Path.of("src/test/resources/evaluation/claims");
    static final String FALSE = "src/test/resources/evaluation/claims/false-claims.json";

    @Test
    void everyCheckTypeAndBasisPassesOnTrueClaims() {
        assertThat(ClaimsCheck.check(FIXTURES.resolve("true-claims.json"))).isEmpty();
    }

    @Test
    void eachFalseClaimFailsAndOneMessageNamesEveryOneWithExpectedAndFound() {
        List<String> problems = ClaimsCheck.check(FIXTURES.resolve("false-claims.json"));
        problems.forEach(problem -> System.out.println("CLAIMS_PROBLEM " + problem));
        assertThat(problems).containsExactly(
                FALSE + " C-101 [topK] question msft-04, k 5: snapshots/row-c.json expected outside, found inside (rank 1)",
                FALSE + " C-102 [rank] question nvda-01 in snapshots/reference.json: rank expected 1, found 3",
                FALSE + " C-103 [metric] mrr in snapshots/reference.json: value expected 0.400000, found 0.395833",
                FALSE + " C-104 [ruleRow] snapshots/row-b.json against snapshots/reference.json: aggregateHitAt5 expected pass, found fail (hitAt5 0.500000 below 0.750000); tickerHitAt5 expected pass, found fail (MSFT 0.0 below 1.0); figureKindTop5 expected pass, found fail (left the top 5: msft-04 (rank 8, was 4), nvda-01 (rank null, was 3))",
                FALSE + " C-105 [candidate] question q1, chunk 101 in ../evidence/scripted-report-untraced.json: rerankInput expected true, found unknown (no trace); fusedPosition expected 1, found unknown (no trace)",
                FALSE + " C-106 [phraseSpan] question q1, chunk 102, phrase \"w012 w013 w014 w015\", occurrence 1 in ../evidence/scripted-report.json: tokenSpan expected [1, 4], found [1, 5] (derived)",
                FALSE + " C-107 [membership] question q1, chunk 102, phrase \"w012 w013 w014 w015\", occurrence 1 in ../evidence/scripted-report.json: basis expected observed, found derived",
                FALSE + " C-108 [inferred]: premises expected at least one claim id in \"from\", found none",
                FALSE + " C-109 [inferred]: premise C-102 expected to pass, found 1 problem (listed under C-102)",
                FALSE + " C-110 [notRecorded] questions[id=q1].phrases[0].chunks[chunkId=101].rerankInput in ../evidence/scripted-report.json: expected not recorded (unknown), found true (observed): the value is now recorded, so the claim must be rewritten",
                FALSE + " C-111 [causal wording]: \"caused\" states a cause: basis expected experiment (with an experiment file varying only that factor), found derived",
                FALSE + " C-112 [experiment]: experiments/three-settings.json settings expected exactly two different settings of the factor, found [0,64,224]",
                FALSE + " C-113 [rank] question aapl-01 in snapshots/row-d.json: file snapshots/row-d.json not found (resolved to src/test/resources/evaluation/claims/snapshots/row-d.json)",
                FALSE + " C-114 [rank]: basis unknown expected check type notRecorded, found rank");
        String message = DocumentationClaims.message(problems);
        assertThat(message).startsWith("Claims check failed: " + problems.size() + " problems");
        for (String id : List.of("C-101", "C-102", "C-103", "C-104", "C-105", "C-106", "C-107", "C-108", "C-109", "C-110", "C-111", "C-112", "C-113", "C-114")) {
            assertThat(message).contains(FALSE + " " + id + " [");
        }
    }

    @Test
    void theMsft04CaseFailsOnlyOnTheRowRankingTheQuestionFirst() {
        // "Outside the top 5 in every row" is false when one row ranks msft-04 first; the problem names that row and its rank, not the others.
        assertThat(problemsOf("C-101")).singleElement().asString().endsWith(": snapshots/row-c.json expected outside, found inside (rank 1)")
                .doesNotContain("row-a.json").doesNotContain("row-b.json");
    }

    @Test
    void anObservedCandidateClaimFailsOnAReportWithoutATraceAndPassesOnTheTracedOne() {
        assertThat(problemsOf("C-105")).singleElement().asString().contains("rerankInput expected true, found unknown (no trace)");
        assertThat(ClaimsCheck.check(FIXTURES.resolve("true-claims.json"))).noneMatch(problem -> problem.contains("C-010"));
    }

    @Test
    void structuralRulesRejectMalformedClaims(@TempDir Path temp) throws Exception {
        Path claims = temp.resolve("claims.json");
        Files.writeString(claims, """
                {"claims": [
                  {"id": "C-001", "text": "A premise.", "basis": "inferred", "from": ["C-002"]},
                  {"id": "C-002", "text": "Another premise.", "basis": "inferred", "from": ["C-001"]},
                  {"id": "C-003", "text": "Self.", "basis": "inferred", "from": ["C-003", "C-404"]},
                  {"id": "C-004", "text": "An inference with a check.", "basis": "inferred", "from": ["C-001"], "check": {"type": "rank"}},
                  {"id": "C-001", "text": "A duplicate id.", "basis": "observed", "check": {"type": "rank"}},
                  {"id": "C-5", "text": "Line one\\nline two", "basis": "seen", "check": {"type": "rank"}},
                  {"id": "C-006", "text": "Premises on an observed claim.", "basis": "observed", "from": ["C-001"], "note": "x", "check": {"type": "rank", "extra": 1}},
                  {"id": "C-007", "text": "An observed claim using notRecorded.", "basis": "observed", "check": {"type": "notRecorded", "report": "r.json", "path": "a"}},
                  {"id": "C-008", "text": "No check.", "basis": "derived"},
                  {"id": "C-009", "text": "An unknown check type.", "basis": "derived", "check": {"type": "median"}},
                  {"id": "C-010", "text": "An experiment without its file.", "basis": "experiment", "check": {"type": "rank"}}
                ], "extra": true}
                """);
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims)).containsExactly(
                name + ": unknown top-level key \"extra\"",
                name + " claim #5: id C-001 is already used by claim #1",
                name + " C-006: unknown key \"note\"",
                name + " C-001 [inferred]: premise C-002 expected to pass, found 1 problem (listed under C-002)",
                name + " C-002 [inferred]: premise C-001 expected no cycle, found C-001 inferred from C-002 in turn",
                name + " C-003 [inferred]: premise expected another claim, found the claim itself",
                name + " C-003 [inferred]: premise C-404 expected an existing claim, found none with that id",
                name + " C-004 [inferred]: check expected none (an inference is not evaluated), found {\"type\":\"rank\"}",
                name + " C-001 [rank] : check.snapshot must be a non-blank string, found null".replace(" [rank] :", " [rank]:"),
                name + " C-5 [format]: id expected C- followed by at least three digits, found \"C-5\"",
                name + " C-5 [format]: text expected one non-blank line without comment markers, found \"Line one\nline two\"",
                name + " C-5 [format]: basis expected one of observed, derived, inferred, unknown, experiment, found \"seen\"",
                name + " C-006 [format]: \"from\" is only for basis inferred, found on basis observed",
                name + " C-006 [rank]: unknown check key \"extra\"",
                name + " C-007 [notRecorded]: check type notRecorded expected basis unknown, found observed",
                name + " C-008 [format]: check expected an object with a type, found none",
                name + " C-009 [format]: check type expected one of rank, topK, metric, ruleRow, phraseSpan, membership, candidate, notRecorded, found \"median\"",
                name + " C-010 [experiment]: experiment expected an object with file and factor, found none");
    }

    @Test
    void checkSpecificRulesNameWhatIsWrong(@TempDir Path temp) throws Exception {
        Path fixtures = FIXTURES.toAbsolutePath();
        Path claims = temp.resolve("claims.json");
        String snapshots = fixtures.resolve("snapshots").toString();
        Files.createDirectories(temp.resolve("experiments"));
        Files.writeString(temp.resolve("experiments/other-factor.json"), "{\"experiment\": {\"factor\": \"rerank-candidates\", \"settings\": [20, 20]}}");
        Files.createDirectories(temp.resolve("snapshots"));
        Files.writeString(temp.resolve("snapshots/other-set.json"), Files.readString(fixtures.resolve("snapshots/row-c.json")).replace("\"v-claims\"", "\"v-other\""));
        for (String file : List.of("reference.json", "row-a.json")) Files.copy(Path.of(snapshots, file), temp.resolve("snapshots").resolve(file));
        Files.createDirectories(temp.resolve("evidence"));
        Files.copy(fixtures.resolveSibling("evidence/scripted-report.json"), temp.resolve("evidence/scripted-report.json"));
        Files.writeString(claims, """
                {"claims": [
                  {"id": "C-001", "text": "A metric path outside the closed set.", "basis": "observed",
                   "check": {"type": "metric", "snapshot": "snapshots/reference.json", "metric": "properties.rerank", "expected": 1}},
                  {"id": "C-002", "text": "A metric beyond scale 6.", "basis": "observed",
                   "check": {"type": "metric", "snapshot": "snapshots/reference.json", "metric": "hitAt5", "expected": 0.7500001}},
                  {"id": "C-003", "text": "Rows of different sets.", "basis": "derived",
                   "check": {"type": "ruleRow", "reference": "snapshots/reference.json", "candidate": "snapshots/other-set.json", "criteria": {"aggregateHitAt5": "pass"}}},
                  {"id": "C-004", "text": "A factor the experiment file does not declare, with the same setting twice.", "basis": "experiment",
                   "experiment": {"file": "experiments/other-factor.json", "factor": "window-overlap-tokens"},
                   "check": {"type": "rank", "snapshot": "snapshots/reference.json", "question": "aapl-01", "expected": 1}},
                  {"id": "C-005", "text": "A question the snapshot does not have.", "basis": "observed",
                   "check": {"type": "rank", "snapshot": "snapshots/row-a.json", "question": "tsla-01", "expected": 1}},
                  {"id": "C-006", "text": "A reason that differs.", "basis": "unknown",
                   "check": {"type": "notRecorded", "report": "evidence/scripted-report.json", "path": "questions[id=q3].rerankOutcome", "reason": "no trace"}},
                  {"id": "C-007", "text": "A path naming nothing.", "basis": "unknown",
                   "check": {"type": "notRecorded", "report": "evidence/scripted-report.json", "path": "questions[id=q9].rerankOutcome"}},
                  {"id": "C-008", "text": "A chunk that does not hold the phrase.", "basis": "derived",
                   "check": {"type": "phraseSpan", "report": "evidence/scripted-report.json", "question": "q1", "phrase": "w012 w013 w014 w015", "chunk": 201, "expected": {"tokenSpan": [0, 1]}}},
                  {"id": "C-009", "text": "A snapshot used as a report.", "basis": "observed",
                   "check": {"type": "candidate", "report": "snapshots/reference.json", "question": "q1", "chunk": 101, "expected": {"rerankInput": true}}},
                  {"id": "C-010", "text": "The rerank outcome of q1 is not recorded.", "basis": "unknown",
                   "check": {"type": "notRecorded", "report": "evidence/scripted-report.json", "path": "questions[id=q1].rerankOutcome"}}
                ]}
                """);
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims)).containsExactly(
                name + " C-001 [metric]: check.metric must be hitAt1, hitAt3, hitAt5, mrr, slices.<figure|nonFigure>.<metric>, or tickerHitAt5.<TICKER>, found properties.rerank",
                name + " C-002 [metric]: check.expected must be a number with at most six decimal places, found 0.7500001",
                name + " C-003 [ruleRow] snapshots/other-set.json against snapshots/reference.json: snapshots expected the same set version and question count, found reference v-claims/4 and candidate v-other/4",
                name + " C-004 [experiment]: experiments/other-factor.json factor expected \"window-overlap-tokens\", found \"rerank-candidates\"",
                name + " C-004 [experiment]: experiments/other-factor.json settings expected exactly two different settings of the factor, found [20,20]",
                name + " C-005 [rank] question tsla-01 in snapshots/row-a.json: snapshots/row-a.json has no question tsla-01",
                name + " C-006 [notRecorded] questions[id=q3].rerankOutcome in evidence/scripted-report.json: reason expected \"no trace\", found \"no trace for this question (its retrieval failed)\"",
                name + " C-007 [notRecorded] questions[id=q9].rerankOutcome in evidence/scripted-report.json: path names no value in the report",
                name + " C-008 [phraseSpan] question q1, chunk 201, phrase \"w012 w013 w014 w015\", occurrence 1 in evidence/scripted-report.json: the report lists no chunk 201 holding that phrase",
                name + " C-009 [candidate] question q1, chunk 101 in snapshots/reference.json: snapshots/reference.json is not an evidence report (no snapshotId and questions)",
                name + " C-010 [notRecorded] questions[id=q1].rerankOutcome in evidence/scripted-report.json: expected not recorded (unknown), found RERANKED (observed): the value is now recorded, so the claim must be rewritten");
    }

    @Test
    void causalWordingIsDetectedByTheDocumentedWordList() {
        for (String sentence : List.of("Hit@5 fell because of reranking.", "The timeout caused two fallbacks.", "Overlap 224 lifted NVDA.", "This is due to the head cut.",
                "The window explains the miss.", "It led to a loss.", "As a result msft-04 left.", "Scores resulted in a new order.", "Reranking drove the change.",
                "The gain is attributable to windows.", "Hence the rule fails.", "Therefore it failed.", "Thanks to overlap it passed.", "Driven by windows.",
                "Owing to timeouts it fell back.", "Set so that it passes.", "The cause is chunk 515.")) {
            assertThat(ClaimsCheck.CAUSAL.matcher(sentence).find()).as(sentence).isTrue();
        }
        for (String sentence : List.of("The results in snapshot 297 rank msft-05 10th.", "Chunk 515 was a rerank input at fused position 14.",
                "msft-04 is outside the top 5 in rows 296 to 299.", "The because-free sentence")) {
            assertThat(ClaimsCheck.CAUSAL.matcher(sentence).find()).as(sentence).isEqualTo(sentence.contains("because"));
        }
    }

    @Test
    void reportPathsSelectByIndexOrKeyAndADotInsideASelectorDoesNotSplit() throws Exception {
        tools.jackson.databind.JsonNode report = ClaimsCheck.JSON.readTree(Files.readString(FIXTURES.resolveSibling("evidence/scripted-report.json")));
        assertThat(ReportPath.resolve(report, "questions[1].phrases[phrase=w030 w031].chunks[chunkId=201].rerankInput.basis").asString()).isEqualTo("derived");
        assertThat(ReportPath.resolve(report, "settings.maxLength.value").intValue()).isEqualTo(20);
        assertThat(ReportPath.resolve(report, "questions[id=q1].phrases[9]")).isNull();
        assertThat(ReportPath.split("a[k=x.y].b")).containsExactly("a[k=x.y]", "b");
        assertThatThrownBy(() -> ReportPath.resolve(report, "questions[=q1]")).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> problemsOf(String id) {
        return ClaimsCheck.check(FIXTURES.resolve("false-claims.json")).stream().filter(problem -> problem.startsWith(FALSE + " " + id + " ")).toList();
    }
}
