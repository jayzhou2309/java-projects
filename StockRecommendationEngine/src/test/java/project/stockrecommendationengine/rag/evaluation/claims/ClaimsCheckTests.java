package project.stockrecommendationengine.rag.evaluation.claims;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/**
 * Evaluation evidence Milestone 3 with plan amendments 4 and 6, E1 and E2, over committed fixtures under
 * {@code src/test/resources/evaluation/claims} (snapshots in both file shapes, the scripted evidence reports with and without traces,
 * experiment files; the evidence root is {@code src/test/resources/evaluation}): every check type passes on a true claim and fails on a false
 * one, each observed and derived sentence is rendered from its check by its template, free text and labels are screened for causal and absolute
 * wording (after removing soft hyphens, invisible characters, and markdown and HTML marks), numbers in labels, and citations, experiments are checked against their two snapshots, and one message names every failing claim with its id, check type,
 * expected value, and the value found. The validator's cases over the committed evidence are in ClaimsCheckValidatorCasesTests.
 */
class ClaimsCheckTests {
    static final Path FIXTURES = Path.of("src/test/resources/evaluation/claims");
    static final Path ROOT = FIXTURES.getParent();
    static final String FALSE = "src/test/resources/evaluation/claims/false-claims.json";

    @Test
    void everyCheckTypeAndBasisPassesOnTrueClaimsAndEachSentenceIsRenderedFromItsCheck() {
        ClaimsCheck.Result result = ClaimsCheck.evaluate(FIXTURES.resolve("true-claims.json"), ROOT);
        assertThat(result.problems()).isEmpty();
        List<String> sentences = new ArrayList<>();
        for (ClaimsCheck.Claim claim : result.claims()) sentences.add(claim.label() + " " + result.sentences().get(claim.index()));
        assertThat(sentences).containsExactly(
                "C-001 The reference row (snapshot 1) ranks aapl-01 1st.",
                "C-002 Row A (snapshot 2) ranks msft-04 outside its window of 10 results (no matching chunk).",
                "C-003 msft-04 is inside the top 5 in the reference row (snapshot 1, rank 4), outside the top 5 in row A (snapshot 2, no matching chunk in the window) and row B (snapshot 3, rank 8), and inside the top 5 in row C (snapshot 4, rank 1).",
                "C-004 The hit@5 of the reference row (snapshot 1) is 0.750000.",
                "C-005 The figure-slice hit@5 of row C (snapshot 4) is 0.666667.",
                "C-006 The NVDA hit@5 of row B (snapshot 3) is 0.500000.",
                "C-007 Against the reference row (snapshot 1), row C (snapshot 4) meets the aggregate hit@5 criterion (hit@5 0.750000 against 0.750000), meets the non-figure hit@5 criterion (non-figure-slice hit@5 1.000000 against 0.000000), meets the per-ticker hit@5 criterion (at or above the reference's for 3 tickers: AAPL 1.000000 against 1.000000, MSFT 1.000000 against 1.000000, NVDA 0.500000 against 0.500000), and does not meet the FIGURE top-5 criterion (left the top 5: nvda-01 (no rank, was 3)).",
                "C-008 In the traced scripted report (the evidence report of snapshot 459), occurrence 1 of 1 of q1's accepted phrase \"w012 w013 w014 w015\" in chunk 101 spans tokens [12, 16) and characters [60, 79).",
                "C-009 In the traced scripted report (the evidence report of snapshot 459), occurrence 1 of 1 of q1's accepted phrase \"w012 w013 w014 w015\" in chunk 101 lies partly inside the head window, and of the 3 rows scored for this chunk, no row holds it wholly.",
                "C-010 In the traced scripted report (the evidence report of snapshot 459), chunk 101, which holds an accepted phrase of q1, was at fused position 1, was a rerank input, and was reranked 3rd.",
                "C-011 In the traced scripted report (the evidence report of snapshot 459), chunk 201, which holds an accepted phrase of q2, was not in the fused list and was not a rerank input.",
                "C-012 The untraced report does not record whether chunk 101 was a rerank input for q1. (not recorded in the untraced scripted report (the evidence report of snapshot 459): no trace)",
                "C-013 The traced report does not record q3's rerank outcome. (not recorded in the traced scripted report (the evidence report of snapshot 459): no trace for this question (its retrieval failed))",
                "C-014 msft-04 left the top 5 in rows A and B while q1's chunk 101 was reranked in the traced report. (inferred from C-003 and C-010)",
                "C-015 Raising the window overlap from 64 to 224 lifted msft-04 from rank 8 into the top 5. (experiment: rerankerScoring max-window/overlap=64/maxWindows=4 in snapshot 3 against max-window/overlap=224/maxWindows=4 in snapshot 4; checked: msft-04 is outside the top 5 in row B (snapshot 3, rank 8), and inside the top 5 in row C (snapshot 4, rank 1))",
                "C-016 In the untraced scripted report (the evidence report of snapshot 459), occurrence 1 of 1 of q2's accepted phrase \"w030 w031\" in chunk 201 lies outside the head window, and of the 3 rows the recorded scoring would score for this chunk (not rows that were scored: rerank outcome unknown, no trace), row 3 holds it wholly.",
                "C-017 In the traced scripted report (the evidence report of snapshot 459), occurrence 1 of 1 of q2's accepted phrase \"w030 w031\" in chunk 201: of the 3 rows the recorded scoring would score for this chunk (not rows that were scored: reranking fell back), row 3 holds it wholly.",
                "C-018 Row B (snapshot 3) ranks msft-04 8th.");
    }

    @Test
    void eachFalseClaimFailsAndOneMessageNamesEveryOneWithExpectedAndFound() {
        List<String> problems = ClaimsCheck.check(FIXTURES.resolve("false-claims.json"), ROOT);
        problems.forEach(problem -> System.out.println("CLAIMS_PROBLEM " + problem));
        String outside = "../../../../../../../../../../../../../../../../private/tmp/claims-outside.json";
        assertThat(problems).containsExactly(
                FALSE + " C-101 [topK] question msft-04, k 5: snapshots/row-c.json expected outside, found inside (rank 1)",
                FALSE + " C-102 [rank] question nvda-01 in snapshots/reference.json: rank expected 1, found 3",
                FALSE + " C-103 [metric] mrr in snapshots/reference.json: value expected 0.400000, found 0.395833",
                FALSE + " C-104 [ruleRow] snapshots/row-b.json against snapshots/reference.json: aggregateHitAt5 expected pass, found fail (hit@5 0.500000 against 0.750000); tickerHitAt5 expected pass, found fail (below the reference's or not recorded: MSFT 0.000000 against 1.000000, of 3 tickers); figureKindTop5 expected pass, found fail (left the top 5: msft-04 (rank 8, was 4), nvda-01 (no rank, was 3))",
                FALSE + " C-105 [candidate] question q1, chunk 101 in ../evidence/scripted-report-untraced.json: rerankInput expected true, found unknown (no trace); fusedPosition expected 1, found unknown (no trace)",
                FALSE + " C-106 [phraseSpan] question q1, chunk 102, phrase \"w012 w013 w014 w015\", occurrence 1 in ../evidence/scripted-report.json: tokenSpan expected [1, 4], found [1, 5] (derived)",
                FALSE + " C-107 [membership] question q1, chunk 102, phrase \"w012 w013 w014 w015\", occurrence 1 in ../evidence/scripted-report.json: basis expected observed, found derived",
                FALSE + " C-108 [inferred]: premises expected at least one claim id in \"from\", found none",
                FALSE + " C-109 [inferred]: premise C-102 expected to pass, found 1 problem (listed under C-102)",
                FALSE + " C-110 [notRecorded] questions[id=q1].phrases[0].chunks[chunkId=101].rerankInput in ../evidence/scripted-report.json: expected not recorded (unknown), found true (observed): the value is now recorded, so the claim must be rewritten",
                FALSE + " C-111 [format]: text expected none on basis derived (its sentence is rendered from its check), found \"Reranking caused msft-04 to leave the top 5 in row A.\"",
                FALSE + " C-111 [causal wording]: \"caused\" states a cause: basis expected experiment (with an experiment file whose two snapshots differ only in that factor), found derived",
                FALSE + " C-112 [experiment]: experiments/three-settings.json settings expected exactly two different settings of the factor, found [\"max-window/overlap=0/maxWindows=4\",\"max-window/overlap=64/maxWindows=4\",\"max-window/overlap=224/maxWindows=4\"]",
                FALSE + " C-112 [experiment]: experiments/three-settings.json snapshots expected two committed snapshot files, one per setting, found none",
                FALSE + " C-113 [rank] question aapl-01 in snapshots/row-d.json: file snapshots/row-d.json not found (resolved to src/test/resources/evaluation/claims/snapshots/row-d.json)",
                FALSE + " C-114 [rank]: basis unknown expected check type notRecorded, found rank",
                FALSE + " C-115 [format]: text expected none on basis observed (its sentence is rendered from its check), found \"The reference row ranks msft-04 first.\"",
                FALSE + " C-116 [absolute wording]: \"will\", \"always\" expected no absolute or predictive wording in free text, found in \"The window overlap will always keep msft-04 in the top 5.\"",
                FALSE + " C-116 [inferred]: premise C-115 expected to pass, found 1 problem (listed under C-115)",
                FALSE + " C-117 [experiment]: experiments/two-factors.json snapshots ../snapshots/row-a.json and ../snapshots/row-c.json expected to differ only in rerankCandidates, found 1 other recorded property differing: rerankerScoring max-window/overlap=64/maxWindows=4 against max-window/overlap=224/maxWindows=4",
                FALSE + " C-118 [experiment]: experiments/bare.json snapshots expected two committed snapshot files, one per setting, found none",
                FALSE + " C-119 [experiment]: the check reads snapshots/reference.json, expected one of the snapshots of experiments/overlap-64-224.json (snapshots 3 and 4) or an evidence report of one of them",
                FALSE + " C-120 [topK]: unknown key \"rank\" in row 1",
                FALSE + " C-121 [metric]: check.expected must be a number with at most six decimal places, found 0.7857142",
                FALSE + " C-122 [metric] hitAt5 in snapshots/seven-decimals.json: stored hitAt5 expected at most six decimal places, found 0.7857142",
                FALSE + " C-123 [ruleRow] snapshots/row-c.json against snapshots/no-kind-no-tickers.json: tickerHitAt5 expected pass, found fail (the reference records no tickerHitAt5); figureKindTop5 expected pass, found fail (the reference records no kind for aapl-01, msft-04, nvda-01, nvda-02)",
                FALSE + " C-124 [rank] question aapl-01 in " + outside + ": file " + outside + " resolves to "
                        + ClaimsCheck.display(FIXTURES.toAbsolutePath().resolve(outside)) + ", outside the evidence root src/test/resources/evaluation",
                FALSE + " C-125 [error]: evaluating the claim threw JsonNodeException: 'StringNode' method `intValue()` cannot coerce value \"fourth\" to `int`: value type not numeric",
                FALSE + " C-126 [rank] question nvda-02 in snapshots/reference.json: rank expected 2, found null");
        String message = DocumentationClaims.message(problems);
        assertThat(message).startsWith("Claims check failed: " + problems.size() + " problems");
        for (int id = 101; id <= 126; id++) assertThat(message).contains(FALSE + " C-" + id + " [");
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
        assertThat(ClaimsCheck.check(FIXTURES.resolve("true-claims.json"), ROOT)).noneMatch(problem -> problem.contains("C-010"));
    }

    @Test
    void oneClaimThatCannotBeEvaluatedNeverStopsTheOthers() {
        // A metric beyond six decimals (in the claim or in the snapshot) and a snapshot value that makes Jackson throw each become a problem of
        // their own claim; the claim after them is still evaluated, and its sentence rendered.
        ClaimsCheck.Result result = ClaimsCheck.evaluate(FIXTURES.resolve("false-claims.json"), ROOT);
        assertThat(result.problems()).filteredOn(problem -> problem.contains(" C-12")).extracting(problem -> problem.substring(FALSE.length() + 1, FALSE.length() + 13))
                .containsExactly("C-120 [topK]", "C-121 [metri", "C-122 [metri", "C-123 [ruleR", "C-124 [rank]", "C-125 [error", "C-126 [rank]");
        assertThat(result.sentences().get(26)).isEqualTo("Snapshot 1 ranks nvda-02 outside its window of 10 results (no matching chunk).");
        assertThat(result.sentences()).doesNotContainKeys(21, 22, 25);
    }

    @Test
    void anObservedOrDerivedClaimCannotCarryTextAndItsSentenceStatesTheValueFound() {
        // C-115 wrote "The reference row ranks msft-04 first." beside a check expecting 4: the text is rejected, and the sentence is rendered
        // from the snapshot's rank.
        ClaimsCheck.Result result = ClaimsCheck.evaluate(FIXTURES.resolve("false-claims.json"), ROOT);
        assertThat(problemsOf("C-115")).singleElement().asString().contains("[format]: text expected none on basis observed");
        assertThat(result.sentences().get(15)).isEqualTo("Snapshot 1 ranks msft-04 4th.");
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
                  {"id": "C-001", "basis": "observed", "check": {"type": "rank"}},
                  {"id": "C-5", "text": "Line one\\nline two", "basis": "seen", "check": {"type": "rank"}},
                  {"id": "C-006", "basis": "observed", "from": ["C-001"], "note": "x", "check": {"type": "rank", "extra": 1}},
                  {"id": "C-007", "text": "An observed claim using notRecorded.", "basis": "observed", "check": {"type": "notRecorded", "report": "r.json", "path": "a"}},
                  {"id": "C-008", "basis": "derived", "note": "x"},
                  {"id": "C-009", "basis": "derived", "check": {"type": "median"}},
                  {"id": "C-010", "text": "An experiment without its file.", "basis": "experiment", "check": {"type": "rank"}},
                  {"id": "C-011", "basis": "derived", "check": {"type": "topK", "question": "q", "k": 5, "rows": [{"snapshot": "s.json", "expected": "inside", "rank": 7}, 3]}},
                  {"id": "C-012", "basis": "derived", "check": {"type": "candidate", "report": "r.json", "question": "q", "chunk": 1, "expected": {"score": 1}}},
                  {"id": "C-013", "basis": "derived", "check": {"type": "ruleRow", "reference": "a.json", "candidate": "b.json", "criteria": {"mrr": "pass"}}},
                  {"id": "C-014", "text": "Overlap 224 lifted it.", "basis": "experiment", "experiment": {"file": "e.json", "factor": "x", "runs": 2}, "check": {"type": "rank"}},
                  {"id": "C-015", "basis": "unknown", "check": {"type": "notRecorded", "report": "r.json", "path": "a"}},
                  {"id": "C-016", "text": null, "basis": "derived", "check": {"type": "rank"}}
                ], "extra": true, "description": 3}
                """);
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(
                name + ": unknown top-level key \"extra\"",
                name + ": description expected a string, found 3",
                name + " claim #5: id C-001 is already used by claim #1",
                name + " C-006 [rank]: unknown key \"note\"",
                name + " C-008 [format]: unknown key \"note\"",
                name + " C-001 [inferred]: premise C-002 expected to pass, found 1 problem (listed under C-002)",
                name + " C-002 [inferred]: premise C-001 expected no cycle, found C-001 inferred from C-002 in turn",
                name + " C-003 [inferred]: premise expected another claim, found the claim itself",
                name + " C-003 [inferred]: premise C-404 expected an existing claim, found none with that id",
                name + " C-004 [inferred]: check expected none (an inference is not evaluated), found {\"type\":\"rank\"}",
                name + " C-001 [rank]: check.snapshot must be a non-blank string, found null",
                name + " C-5 [format]: id expected C- followed by at least three digits, found \"C-5\"",
                name + " C-5 [format]: basis expected one of observed, derived, inferred, unknown, experiment, found \"seen\"",
                name + " C-006 [format]: \"from\" is only for basis inferred, found on basis observed",
                name + " C-006 [rank]: unknown check key \"extra\"",
                name + " C-007 [format]: text expected none on basis observed (its sentence is rendered from its check), found \"An observed claim using notRecorded.\"",
                name + " C-007 [notRecorded]: check type notRecorded expected basis unknown, found observed",
                name + " C-008 [format]: check expected an object with a type, found none",
                name + " C-009 [format]: check type expected one of rank, topK, metric, ruleRow, phraseSpan, membership, candidate, bestFusedPosition, candidateRecall, removedAccepted, blend, candidateLists, diagnosticLine, diagnosticCount, sizeTable, sizeChoice, notRecorded, found \"median\"",
                name + " C-010 [experiment]: experiment expected an object with file and factor, found none",
                name + " C-011 [topK]: unknown key \"rank\" in row 1",
                name + " C-011 [topK]: row 2 expected an object {snapshot, expected}, found 3",
                name + " C-012 [candidate]: unknown expected field \"score\" (one of fusedPosition, rerankInput, rerankedPosition)",
                name + " C-013 [ruleRow]: unknown criterion \"mrr\" (one of aggregateHitAt5, nonFigureHitAt5, tickerHitAt5, figureKindTop5)",
                name + " C-014 [experiment]: unknown experiment key \"runs\"",
                name + " C-015 [format]: text expected one non-blank line without comment markers on basis unknown, found none",
                name + " C-015 [notRecorded] a in r.json: file r.json not found (resolved to " + ClaimsCheck.display(temp.resolve("r.json")) + ")",
                name + " C-016 [format]: text expected none on basis derived (its sentence is rendered from its check), found none",
                name + " C-016 [rank]: check.snapshot must be a non-blank string, found null");
    }

    @Test
    void checkSpecificRulesNameWhatIsWrong(@TempDir Path temp) throws Exception {
        Path fixtures = FIXTURES.toAbsolutePath();
        Path claims = temp.resolve("claims.json");
        Files.createDirectories(temp.resolve("snapshots"));
        Files.writeString(temp.resolve("snapshots/other-set.json"), Files.readString(fixtures.resolve("snapshots/row-c.json")).replace("\"v-claims\"", "\"v-other\""));
        Files.writeString(temp.resolve("snapshots/no-id.json"), Files.readString(fixtures.resolve("snapshots/row-c.json")).replace("\"id\": 4,", ""));
        for (String file : List.of("reference.json", "row-a.json", "row-b.json", "row-c.json")) Files.copy(fixtures.resolve("snapshots").resolve(file), temp.resolve("snapshots").resolve(file));
        Files.createDirectories(temp.resolve("experiments"));
        Files.writeString(temp.resolve("experiments/other-factor.json"), """
                {"experiment": {"factor": "rerankCandidates", "settings": [20, 20], "snapshots": ["../snapshots/row-b.json", "../snapshots/row-c.json"]}, "note": "x"}""");
        Files.writeString(temp.resolve("experiments/same-snapshot.json"), """
                {"experiment": {"factor": "rerankCandidates", "settings": [10, 20], "snapshots": ["../snapshots/row-b.json", "../snapshots/row-b.json"]}}""");
        Files.writeString(temp.resolve("experiments/wrong-settings.json"), """
                {"experiment": {"factor": "rerankCandidates", "settings": [20, 10], "snapshots": ["../snapshots/row-a.json", "../snapshots/row-b.json"]}}""");
        Files.writeString(temp.resolve("experiments/string-setting.json"), """
                {"experiment": {"factor": "rerankCandidates", "settings": [10, "20"], "snapshots": ["../snapshots/row-a.json", "../snapshots/row-b.json"]}}""");
        Files.writeString(temp.resolve("experiments/unrecorded-factor.json"), """
                {"experiment": {"factor": "rerankTimeoutMs", "settings": [2000, 4000], "snapshots": ["../snapshots/row-a.json", "../snapshots/row-b.json"]}}""");
        Files.createDirectories(temp.resolve("evidence"));
        Files.copy(fixtures.resolveSibling("evidence/scripted-report.json"), temp.resolve("evidence/scripted-report.json"));
        Files.writeString(claims, """
                {"claims": [
                  {"id": "C-001", "basis": "observed",
                   "check": {"type": "metric", "snapshot": "snapshots/reference.json", "metric": "properties.rerank", "expected": 1}},
                  {"id": "C-002", "basis": "observed",
                   "check": {"type": "metric", "snapshot": "snapshots/reference.json", "metric": "hitAt5", "expected": 0.7500001}},
                  {"id": "C-003", "basis": "derived",
                   "check": {"type": "ruleRow", "reference": "snapshots/reference.json", "candidate": "snapshots/other-set.json", "criteria": {"aggregateHitAt5": "pass"}}},
                  {"id": "C-004", "text": "A factor the experiment file does not declare, with the same setting twice.", "basis": "experiment",
                   "experiment": {"file": "experiments/other-factor.json", "factor": "window-overlap-tokens"},
                   "check": {"type": "rank", "snapshot": "snapshots/row-b.json", "question": "aapl-01", "expected": 1}},
                  {"id": "C-005", "basis": "observed",
                   "check": {"type": "rank", "snapshot": "snapshots/row-a.json", "question": "tsla-01", "expected": 1}},
                  {"id": "C-006", "text": "The value's recorded text differs.", "basis": "unknown",
                   "check": {"type": "notRecorded", "report": "evidence/scripted-report.json", "path": "questions[id=q3].rerankOutcome", "reason": "no trace"}},
                  {"id": "C-007", "text": "A path naming a value the report lacks.", "basis": "unknown",
                   "check": {"type": "notRecorded", "report": "evidence/scripted-report.json", "path": "questions[id=q9].rerankOutcome"}},
                  {"id": "C-008", "basis": "derived",
                   "check": {"type": "phraseSpan", "report": "evidence/scripted-report.json", "question": "q1", "phrase": "w012 w013 w014 w015", "chunk": 201, "expected": {"tokenSpan": [0, 1]}}},
                  {"id": "C-009", "basis": "observed",
                   "check": {"type": "candidate", "report": "snapshots/reference.json", "question": "q1", "chunk": 101, "expected": {"rerankInput": true}}},
                  {"id": "C-010", "text": "The rerank outcome of q1 is not recorded.", "basis": "unknown",
                   "check": {"type": "notRecorded", "report": "evidence/scripted-report.json", "path": "questions[id=q1].rerankOutcome"}},
                  {"id": "C-011", "basis": "derived",
                   "check": {"type": "topK", "question": "msft-04", "k": 5, "rows": [{"snapshot": "snapshots/row-a.json", "expected": "outside"}, {"snapshot": "./snapshots/row-a.json", "expected": "outside"}]}},
                  {"id": "C-012", "basis": "observed",
                   "check": {"type": "rank", "snapshot": "snapshots/no-id.json", "question": "aapl-01", "expected": 1}},
                  {"id": "C-013", "text": "Twenty candidates lifted msft-04.", "basis": "experiment",
                   "experiment": {"file": "experiments/same-snapshot.json", "factor": "rerankCandidates"},
                   "check": {"type": "rank", "snapshot": "snapshots/row-b.json", "question": "msft-04", "expected": 8}},
                  {"id": "C-014", "text": "Twenty candidates lifted msft-04.", "basis": "experiment",
                   "experiment": {"file": "experiments/wrong-settings.json", "factor": "rerankCandidates"},
                   "check": {"type": "rank", "snapshot": "snapshots/row-b.json", "question": "msft-04", "expected": 8}},
                  {"id": "C-015", "text": "A longer timeout lifted msft-04.", "basis": "experiment",
                   "experiment": {"file": "experiments/unrecorded-factor.json", "factor": "rerankTimeoutMs"},
                   "check": {"type": "rank", "snapshot": "snapshots/row-b.json", "question": "msft-04", "expected": 8}},
                  {"id": "C-016", "basis": "observed",
                   "check": {"type": "rank", "snapshot": "/etc/hosts", "question": "aapl-01", "expected": 1}},
                  {"id": "C-017", "text": "Twenty candidates written as a string.", "basis": "experiment",
                   "experiment": {"file": "experiments/string-setting.json", "factor": "rerankCandidates"},
                   "check": {"type": "rank", "snapshot": "snapshots/row-b.json", "question": "msft-04", "expected": 8}},
                  {"id": "C-018", "basis": "observed",
                   "check": {"type": "candidate", "report": "evidence/scripted-report.json", "question": "q1", "chunk": 101, "expected": {"fusedPosition": "1", "rerankedPosition": 3}}}
                ]}
                """);
        String name = ClaimsCheck.display(claims);
        ClaimsCheck.check(claims, temp).stream().filter(problem -> problem.contains(" C-017 ") || problem.contains(" C-018 "))
                .forEach(problem -> System.out.println("AMENDMENT_6_TYPES " + problem.substring(name.length() + 1)));
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(
                name + " C-001 [metric]: check.metric must be hitAt1, hitAt3, hitAt5, mrr, slices.<figure|nonFigure>.<metric>, or tickerHitAt5.<TICKER>, found properties.rerank",
                name + " C-002 [metric]: check.expected must be a number with at most six decimal places, found 0.7500001",
                name + " C-003 [ruleRow] snapshots/other-set.json against snapshots/reference.json: snapshots expected the same set version and question count, found reference v-claims/4 and candidate v-other/4",
                name + " C-004 [experiment]: experiments/other-factor.json unknown key \"note\"",
                name + " C-004 [experiment]: experiments/other-factor.json factor expected \"window-overlap-tokens\", found \"rerankCandidates\"",
                name + " C-004 [experiment]: experiments/other-factor.json settings expected exactly two different settings of the factor, found [20,20]",
                name + " C-005 [rank] question tsla-01 in snapshots/row-a.json: snapshots/row-a.json has no question tsla-01",
                name + " C-006 [notRecorded] questions[id=q3].rerankOutcome in evidence/scripted-report.json: reason expected \"no trace\", found \"no trace for this question (its retrieval failed)\"",
                name + " C-007 [notRecorded] questions[id=q9].rerankOutcome in evidence/scripted-report.json: path names no value in the report",
                name + " C-008 [phraseSpan] question q1, chunk 201, phrase \"w012 w013 w014 w015\", occurrence 1 in evidence/scripted-report.json: the report lists no chunk 201 holding that phrase",
                name + " C-009 [candidate] question q1, chunk 101 in snapshots/reference.json: snapshots/reference.json is not an evidence report (no snapshotId and questions)",
                name + " C-010 [notRecorded] questions[id=q1].rerankOutcome in evidence/scripted-report.json: expected not recorded (unknown), found RERANKED (observed): the value is now recorded, so the claim must be rewritten",
                name + " C-011 [topK] question msft-04, k 5: rows list ./snapshots/row-a.json more than once",
                name + " C-012 [rank] question aapl-01 in snapshots/no-id.json: snapshots/no-id.json records no snapshot id",
                name + " C-013 [experiment]: experiments/same-snapshot.json snapshots expected two different files, found [\"../snapshots/row-b.json\",\"../snapshots/row-b.json\"]",
                name + " C-014 [experiment]: experiments/wrong-settings.json settings[0] expected the rerankCandidates recorded by ../snapshots/row-a.json (10), found 20",
                name + " C-014 [experiment]: experiments/wrong-settings.json settings[1] expected the rerankCandidates recorded by ../snapshots/row-b.json (20), found 10",
                name + " C-015 [experiment]: experiments/unrecorded-factor.json factor rerankTimeoutMs expected a property both snapshots record, found neither recording it",
                name + " C-015 [experiment]: experiments/unrecorded-factor.json snapshots ../snapshots/row-a.json and ../snapshots/row-b.json expected to differ only in rerankTimeoutMs, found 1 other recorded property differing: rerankCandidates 10 against 20",
                name + " C-016 [rank] question aapl-01 in /etc/hosts: file /etc/hosts must be relative to the claims file",
                name + " C-017 [experiment]: experiments/string-setting.json settings[1] expected the rerankCandidates recorded by ../snapshots/row-b.json (the number 20), found the string \"20\"",
                name + " C-018 [candidate] question q1, chunk 101 in evidence/scripted-report.json: fusedPosition expected the string \"1\", found the number 1 (observed)");
    }

    static Stream<Arguments> causalPhrasings() {
        // Plan amendment 4, validator case 7 (each passed the first attempt's word list), then the phrasings that list already caught.
        return Stream.of(
                Arguments.of("Overlap 224 was set, resulting in msft-04 ranking first.", "\"resulting\""),
                Arguments.of("Overlap 224 results in msft-04 ranking first.", "\"results\""),
                Arguments.of("The head cut is leading to the miss.", "\"leading\""),
                Arguments.of("Windowing led msft-04 to rank first.", "\"led\""),
                Arguments.of("Windowing leads NVDA to rank first.", "\"leads\""),
                Arguments.of("The overlap drives the rank of msft-04.", "\"drives\""),
                Arguments.of("Windowing is lifting msft-04.", "\"lifting\""),
                Arguments.of("Chunk 515 is outside the head, which is why msft-05 ranks 10th.", "\"why\""),
                Arguments.of("Chunk 515 is outside the head; thus msft-05 ranks 10th.", "\"thus\""),
                Arguments.of("Chunk 515 is outside the head; consequently msft-05 ranks 10th.", "\"consequently\""),
                Arguments.of("Chunk 515 is responsible for the miss.", "\"responsible\""),
                Arguments.of("Chunk 515 accounts for the miss.", "\"accounts for\""),
                Arguments.of("Chunk 515 contributed to the miss.", "\"contributed\""),
                Arguments.of("The miss stems from the head cut.", "\"stems from\""),
                Arguments.of("Windowing pushed msft-05 down.", "\"pushed\""),
                Arguments.of("Windowing made it rank first.", "\"made\""),
                Arguments.of("Windowing improved msft-04.", "\"improved\""),
                Arguments.of("Windowing boosted msft-04.", "\"boosted\""),
                Arguments.of("The timeout triggered two fallbacks.", "\"triggered\""),
                Arguments.of("The head cut is the reason for the miss.", "\"reason\""),
                Arguments.of("Hit@5 fell because of reranking.", "\"because\""),
                Arguments.of("The timeout caused two fallbacks.", "\"caused\""),
                Arguments.of("This is due to the head cut.", "\"due to\""),
                Arguments.of("Owing to timeouts it fell back.", "\"Owing to\""),
                Arguments.of("The window explains the miss.", "\"explains\""),
                Arguments.of("As a result msft-04 left.", "\"result\""),
                Arguments.of("Reranking drove the change.", "\"drove\""),
                Arguments.of("The gain is attributable to windows.", "\"attributable\""),
                Arguments.of("Hence the rule fails.", "\"Hence\""),
                Arguments.of("Therefore it failed.", "\"Therefore\""),
                Arguments.of("Thanks to overlap it passed.", "\"Thanks to\""),
                Arguments.of("Set so that it passes.", "\"so that\""),
                Arguments.of("Windowing had an effect on msft-04 and helped nvda-01.", "\"effect\", \"helped\""),
                // Plan amendment 6: forms that passed at e44f4c1, then the further causal phrasings added with them.
                Arguments.of("A wider overlap raises msft-04 into the top 5.", "\"raises\""),
                Arguments.of("The head cut put msft-05 at rank 10.", "\"put\""),
                Arguments.of("Windowing gives msft-04 rank 1.", "\"gives\""),
                Arguments.of("The head cut is behind the miss.", "\"is behind\""),
                Arguments.of("The head cut is the source of the miss.", "\"source of\""),
                Arguments.of("The miss arises from the head cut.", "\"arises\""),
                Arguments.of("The miss comes from the head cut.", "\"comes from\""),
                Arguments.of("The head cut brought about the miss.", "\"brought about\""),
                Arguments.of("It fell back on account of the timeout.", "\"on account of\""),
                Arguments.of("It fell back in response to the timeout.", "\"in response to\""),
                Arguments.of("The miss originates in the head cut.", "\"originates\""));
    }

    @ParameterizedTest
    @MethodSource("causalPhrasings")
    void causalWordingInFreeTextIsRejectedWithoutAnExperiment(String sentence, String matched, @TempDir Path temp) throws IOException {
        Path claims = inferredClaim(temp, sentence);
        ClaimsCheck.check(claims, temp).forEach(problem -> System.out.println("CAUSAL_CASE " + problem.substring(problem.indexOf(" C-001 ") + 1)));
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(ClaimsCheck.display(claims) + " C-001 [causal wording]: " + matched
                + (matched.contains(",") ? " state" : " states") + " a cause: basis expected experiment (with an experiment file whose two snapshots differ only in that factor), found inferred");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Chunk 515 was a rerank input at fused position 14 in snapshot 459.", "msft-04 ranks outside the top 5 in snapshots 296 and 297.",
            "The ranks of nvda-01 differ between snapshots 297 and 299.", "Snapshot 297 records rank 10 for msft-05, as the traced run does.",
            "The reranked order of snapshot 459 places chunk 466 11th.", "Snapshot 297's evidence report does not record whether chunk 515 was a rerank input.",
            "Whether row 2 of chunk 515 was scored in snapshot 297 is not recorded, and its report records not a single window score.",
            "In snapshot 459 the cross-encoder received msft-05's ITEM_7 phrase in full in chunk 515 only in row 2."})
    void sentencesWithoutCausalOrAbsoluteWordingPass(String sentence, @TempDir Path temp) throws IOException {
        Path claims = inferredClaim(temp, sentence);
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        assertThat(result.problems()).isEmpty();
        assertThat(result.sentences().get(1)).isEqualTo(sentence + " (inferred from C-002)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"always", "never", "will", "won't", "every", "everything", "all", "none", "nothing", "guarantees", "guaranteed",
            // Plan amendment 6: words that passed at e44f4c1, then their inflections and near forms.
            "each", "any", "anything", "whole", "entire", "entirely", "must", "consistently", "consistent", "it'll", "going to", "gonna", "expect",
            "expected", "shall", "shan't", "nobody", "nowhere", "proves", "proven"})
    void absoluteOrPredictiveWordingInFreeTextIsRejected(String word, @TempDir Path temp) throws IOException {
        String sentence = "Chunk 515 " + word + " holds the phrase in snapshot 459.";
        Path claims = inferredClaim(temp, sentence);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(ClaimsCheck.display(claims) + " C-001 [absolute wording]: \"" + word
                + "\" expected no absolute or predictive wording in free text, found in \"" + sentence + "\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"No row of chunk 515 holds the phrase in snapshot 459.", "Chunk 515 has no trace in snapshot 297.", "In snapshot 297 no-one ranks msft-04."})
    void noFollowedByAWordIsAbsoluteWordingWhileNotIsNot(String sentence, @TempDir Path temp) throws IOException {
        // Plan amendment 6: "no <noun>" generalises over every member of the noun; an unknown claim states a negative with "not" ("is not
        // recorded", "does not record"), which passes (sentencesWithoutCausalOrAbsoluteWordingPass).
        Path claims = inferredClaim(temp, sentence);
        String matched = sentence.startsWith("No row") ? "No row" : sentence.contains("no trace") ? "no trace" : "no-one";
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(ClaimsCheck.display(claims) + " C-001 [absolute wording]: \"" + matched
                + "\" expected no absolute or predictive wording in free text, found in \"" + sentence + "\"");
    }

    @Test
    void theScreenReadsTextWithSoftHyphensInvisibleCharactersAndMarkdownOrHtmlMarksRemoved(@TempDir Path temp) throws IOException {
        // Plan amendment 6, finding 3: "cau\u00ADsed" (a soft hyphen) and "c**ause**d" (markdown emphasis) passed at e44f4c1.
        String note = " (read with soft hyphens, invisible characters, and markdown and HTML marks removed)";
        String causal = " states a cause: basis expected experiment (with an experiment file whose two snapshots differ only in that factor), found inferred";
        for (String sentence : List.of("The timeout cau\u00ADsed two fallbacks.", "The timeout c**ause**d two fallbacks.", "The timeout cau\u200Bsed two fallbacks.",
                "The timeout cau&shy;sed two fallbacks.", "The timeout `caused` two fallbacks.", "The timeout cau<b></b>sed two fallbacks.")) {
            Path claims = inferredClaim(temp, sentence);
            String expectedNote = sentence.contains("`caused`") ? "" : note;
            ClaimsCheck.check(claims, temp).forEach(problem -> System.out.println("AMENDMENT_6_NORMALISED " + problem.substring(problem.indexOf(" C-001 ") + 1)));
            assertThat(ClaimsCheck.check(claims, temp)).as(sentence).containsExactly(ClaimsCheck.display(claims) + " C-001 [causal wording]: \"caused\"" + expectedNote + causal);
        }
        String hidden = "Chunk 515 n\u2060ever a_l_w_a_y_s holds the phrase in snapshot 459.";
        Path claims = inferredClaim(temp, hidden);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(ClaimsCheck.display(claims) + " C-001 [absolute wording]: \"never\", \"always\"" + note
                + " expected no absolute or predictive wording in free text, found in \"" + hidden + "\"");
    }

    @Test
    void freeTextMayNotCiteAClaim(@TempDir Path temp) throws IOException {
        String sentence = "Chunk 515 holds the phrase in snapshot 459 (C-002, unknown).";
        Path claims = inferredClaim(temp, sentence);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(ClaimsCheck.display(claims) + " C-001 [format]: text expected no claim citation (an inferred claim"
                + " lists its premises in \"from\"; the generator prints the claim's own), found \"(C-002\" in \"" + sentence + "\"");
    }

    /** A claims file whose C-001 is an inferred claim with the sentence, following from C-002, an unknown claim that holds. */
    private static Path inferredClaim(Path temp, String sentence) throws IOException {
        Files.createDirectories(temp.resolve("evidence"));
        Files.copy(ROOT.resolve("evidence/scripted-report.json"), temp.resolve("evidence/scripted-report.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path claims = temp.resolve("claims.json");
        Files.writeString(claims, ClaimsCheck.JSON.writeValueAsString(java.util.Map.of("claims", List.of(
                java.util.Map.of("id", "C-001", "text", sentence, "basis", "inferred", "from", List.of("C-002")),
                java.util.Map.of("id", "C-002", "text", "The traced report does not record q3's rerank outcome.", "basis", "unknown",
                        "check", java.util.Map.of("type", "notRecorded", "report", "evidence/scripted-report.json", "path", "questions[id=q3].rerankOutcome"))))));
        return claims;
    }

    @Test
    void labelsAreScreenedLikeFreeTextAndMustNameAFileACheckReads(@TempDir Path temp) throws IOException {
        Files.createDirectories(temp.resolve("snapshots"));
        for (String file : List.of("reference.json", "row-a.json", "row-b.json")) Files.copy(FIXTURES.resolve("snapshots").resolve(file), temp.resolve("snapshots").resolve(file));
        Path claims = temp.resolve("claims.json");
        Files.writeString(claims, """
                {"labels": {"snapshots/reference.json": "the run with 20 candidates", "snapshots/row-a.json": "the row that caused every miss",
                            "snapshots/row-b.json": "row B", "snapshots/missing.json": "a missing file", "snapshots/row-b.json#": 3,
                            "./snapshots/row-b.json": "the forty-candidate row", "snapshots/../snapshots/reference.json": "the c**ause**d row (C-001, observed)"},
                 "claims": [
                  {"id": "C-001", "basis": "observed", "check": {"type": "rank", "snapshot": "snapshots/reference.json", "question": "aapl-01", "expected": 1}},
                  {"id": "C-002", "basis": "observed", "check": {"type": "rank", "snapshot": "snapshots/row-a.json", "question": "aapl-01", "expected": 1}}
                ]}
                """);
        String name = ClaimsCheck.display(claims);
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        result.problems().forEach(problem -> System.out.println("AMENDMENT_6_LABELS " + problem.substring(name.length() + 1)));
        assertThat(result.problems()).containsExactly(
                name + " labels \"snapshots/reference.json\": label expected no digits or spelled-out numbers (numbers are rendered from the evidence), found \"20\" in \"the run with 20 candidates\"",
                name + " labels \"snapshots/row-a.json\": label expected no causal wording, found \"caused\"",
                name + " labels \"snapshots/row-a.json\": label expected no absolute or predictive wording, found \"every\"",
                name + " labels \"snapshots/missing.json\": file snapshots/missing.json not found (resolved to " + ClaimsCheck.display(temp.resolve("snapshots/missing.json")) + ")",
                name + " labels \"snapshots/row-b.json#\": label expected a string, found 3",
                name + " labels \"./snapshots/row-b.json\": label expected no digits or spelled-out numbers (numbers are rendered from the evidence), found \"forty\" in \"the forty-candidate row\"",
                name + " labels \"./snapshots/row-b.json\": expected one label per file, found a second label for " + ClaimsCheck.display(temp.resolve("snapshots/row-b.json")) + ", already labelled by \"snapshots/row-b.json\"",
                name + " labels \"snapshots/../snapshots/reference.json\": label expected no digits or spelled-out numbers (numbers are rendered from the evidence), found \"001\" in \"the c**ause**d row (C-001, observed)\"",
                name + " labels \"snapshots/../snapshots/reference.json\": label expected no causal wording, found \"caused\" (read with soft hyphens, invisible characters, and markdown and HTML marks removed)",
                name + " labels \"snapshots/../snapshots/reference.json\": label expected no claim citation, found \"(C-001\"",
                name + " labels \"snapshots/../snapshots/reference.json\": expected one label per file, found a second label for " + ClaimsCheck.display(temp.resolve("snapshots/reference.json")) + ", already labelled by \"snapshots/reference.json\"",
                name + " labels \"snapshots/row-b.json\": expected a file a check reads, found no check reading it");
        assertThat(result.sentences().get(1)).isEqualTo("Snapshot 1 ranks aapl-01 1st.");
    }

    @Test
    void aPathThroughASymbolicLinkOutOfTheEvidenceRootIsRejected(@TempDir Path temp) throws IOException {
        Path root = Files.createDirectories(temp.resolve("live-runs"));
        Path outside = Files.createDirectories(temp.resolve("elsewhere"));
        Files.copy(FIXTURES.resolve("snapshots/reference.json"), outside.resolve("reference.json"));
        Files.createSymbolicLink(root.resolve("linked"), outside);
        Path claims = root.resolve("claims.json");
        Files.writeString(claims, """
                {"claims": [
                  {"id": "C-001", "basis": "observed", "check": {"type": "rank", "snapshot": "linked/reference.json", "question": "aapl-01", "expected": 1}},
                  {"id": "C-002", "basis": "observed", "check": {"type": "rank", "snapshot": "../elsewhere/reference.json", "question": "aapl-01", "expected": 1}}
                ]}
                """);
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims, root)).containsExactly(
                name + " C-001 [rank] question aapl-01 in linked/reference.json: file linked/reference.json resolves through a symbolic link to "
                        + outside.resolve("reference.json").toRealPath() + ", outside the evidence root " + ClaimsCheck.display(root),
                name + " C-002 [rank] question aapl-01 in ../elsewhere/reference.json: file ../elsewhere/reference.json resolves to "
                        + ClaimsCheck.display(outside.resolve("reference.json")) + ", outside the evidence root " + ClaimsCheck.display(root));
        assertThat(ClaimsCheck.check(claims, temp)).isEmpty();
        Path outsideClaims = outside.resolve("claims.json");
        Files.writeString(outsideClaims, "{\"claims\": []}");
        assertThat(ClaimsCheck.check(outsideClaims, root)).containsExactly(ClaimsCheck.display(outsideClaims) + ": the claims file resolves to "
                + ClaimsCheck.display(outsideClaims) + ", outside the evidence root " + ClaimsCheck.display(root));
    }

    @Test
    void aRepeatedKeyIsAReadErrorAndRowsAreRenderedAsScoredOnlyWhenTheTraceRecordsThatManyRows(@TempDir Path temp) throws IOException {
        Path duplicate = temp.resolve("duplicate.json");
        Files.writeString(duplicate, """
                {"claims": [{"id": "C-001", "basis": "observed", "check": {"type": "rank", "snapshot": "s.json", "question": "q", "expected": 1, "expected": 10}}]}
                """);
        assertThat(ClaimsCheck.check(duplicate, temp)).singleElement().asString()
                .startsWith(ClaimsCheck.display(duplicate) + ": cannot be read as JSON: ").contains("\"expected\"");

        Files.createDirectories(temp.resolve("evidence"));
        String report = Files.readString(ROOT.resolve("evidence/scripted-report.json"));
        String twoRows = report.replaceFirst("\"windowCount\" : \\{\\s*\"value\" : 3", "\"windowCount\" : { \"value\" : 2");
        assertThat(twoRows).isNotEqualTo(report);
        Files.writeString(temp.resolve("evidence/scripted-report.json"), twoRows);
        Path claims = temp.resolve("claims.json");
        Files.writeString(claims, """
                {"claims": [{"id": "C-001", "basis": "derived", "check": {"type": "membership", "report": "evidence/scripted-report.json", "question": "q1",
                  "phrase": "w012 w013 w014 w015", "chunk": 101, "expected": {"windowsHoldingWholly": []}}}]}
                """);
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        assertThat(result.problems()).isEmpty();
        assertThat(result.sentences().get(1)).isEqualTo("In the evidence report of snapshot 459, occurrence 1 of 1 of q1's accepted phrase \"w012 w013 w014 w015\" in chunk 101:"
                + " of the 3 rows the recorded scoring would score for this chunk (not rows that were scored: the trace records 2 scored rows, not the 3 of this arithmetic),"
                + " no row holds it wholly.");

        // Plan amendment 6, finding 4: one scored row is singular ("the trace records 1 scored rows" at e44f4c1).
        Files.writeString(temp.resolve("evidence/scripted-report.json"), report.replaceFirst("\"windowCount\" : \\{\\s*\"value\" : 3", "\"windowCount\" : { \"value\" : 1"));
        assertThat(ClaimsCheck.evaluate(claims, temp).sentences().get(1)).contains("(not rows that were scored: the trace records 1 scored row, not the 3 of this arithmetic)");
    }

    @Test
    void sentenceHelpersFormOrdinalsAndLists() {
        assertThat(List.of(1, 2, 3, 4, 10, 11, 12, 13, 21, 22, 23, 101, 111).stream().map(ClaimsCheck::ordinal))
                .containsExactly("1st", "2nd", "3rd", "4th", "10th", "11th", "12th", "13th", "21st", "22nd", "23rd", "101st", "111th");
        assertThat(ClaimsCheck.joinAnd(List.of("a"))).isEqualTo("a");
        assertThat(ClaimsCheck.joinAnd(List.of("a", "b"))).isEqualTo("a and b");
        assertThat(ClaimsCheck.joinAnd(List.of("a", "b", "c"))).isEqualTo("a, b, and c");
        assertThat(List.of("hitAt1", "hitAt5", "mrr", "slices.figure.hitAt3", "slices.nonFigure.mrr", "tickerHitAt5.NVDA").stream().map(ClaimsCheck::metricName))
                .containsExactly("hit@1", "hit@5", "MRR", "figure-slice hit@3", "non-figure-slice MRR", "NVDA hit@5");
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
        return ClaimsCheck.check(FIXTURES.resolve("false-claims.json"), ROOT).stream().filter(problem -> problem.startsWith(FALSE + " " + id + " ")).toList();
    }
}
