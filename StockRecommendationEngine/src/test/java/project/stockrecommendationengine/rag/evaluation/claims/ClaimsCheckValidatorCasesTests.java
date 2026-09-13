package project.stockrecommendationengine.rag.evaluation.claims;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * Plan amendment 4: the concrete cases with which validation failed the first Milestone 3 attempt, each written as a claim of the committed
 * fixture {@code src/test/resources/evaluation/claims/validator-cases/claims.json} and checked against copies of the committed evidence
 * (evidence-459.json, evidence-297.json, the traced reproduction row, and snapshots 249 and 295 to 299) in a temporary
 * {@code src/main/java/documentation/live-runs} tree, which is the evidence root. Case 7 (causal phrasings) is parameterised in
 * ClaimsCheckTests; case 12 (the generator and an unbalanced document) is in GeneratedBlocksTests.
 */
class ClaimsCheckValidatorCasesTests {
    static final Path LIVE_RUNS = Path.of("src/main/java/documentation/live-runs");
    static final String WINDOWS = "../../2026-09-13-reranker-windows/measurement/";
    static final String OUTSIDE = "../../../../../../../../../../../../../../../../../../../../../../../../private/tmp/claims-outside.json";

    @TempDir Path temp;
    Path liveRuns;
    Path claims;
    String name;

    @BeforeEach
    void copyEvidence() throws IOException {
        liveRuns = temp.resolve("src/main/java/documentation/live-runs");
        for (String file : List.of("2026-09-13-evaluation-evidence/evidence-459.json", "2026-09-13-evaluation-evidence/evidence-297.json",
                "2026-09-13-evaluation-evidence/traced-snapshot-297-settings.json", "2026-09-13-reranker/measurement/snapshot-249-rerank-candidates-20.json",
                "2026-09-13-reranker-windows/measurement/snapshot-295-reference-rerank-off.json", "2026-09-13-reranker-windows/measurement/snapshot-296-rerank-candidates-10.json",
                "2026-09-13-reranker-windows/measurement/snapshot-297-rerank-candidates-20.json", "2026-09-13-reranker-windows/measurement/snapshot-298-rerank-candidates-40-timeout-4000.json",
                "2026-09-13-reranker-windows/measurement/snapshot-299-rerank-candidates-20-overlap-224.json")) {
            Files.createDirectories(liveRuns.resolve(file).getParent());
            Files.copy(LIVE_RUNS.resolve(file), liveRuns.resolve(file));
        }
        Path source = ClaimsCheckTests.FIXTURES.resolve("validator-cases");
        Path target = liveRuns.resolve("2026-09-13-evaluation-evidence/validator-cases");
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path copy = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(copy);
                else Files.copy(path, copy);
            }
        }
        // Case 11: snapshot 295 with every question's kind and the tickerHitAt5 object removed.
        String reference = Files.readString(liveRuns.resolve("2026-09-13-reranker-windows/measurement/snapshot-295-reference-rerank-off.json"));
        String stripped = reference.replaceAll("\"kind\": \"[A-Z_]+\", ", "").replaceAll(", \"tickerHitAt5\": \\{[^}]*\\}", "");
        assertThat(stripped).doesNotContain("\"kind\"").doesNotContain("tickerHitAt5");
        Files.writeString(liveRuns.resolve("2026-09-13-reranker-windows/measurement/reference-without-kind-and-tickers.json"), stripped);
        claims = target.resolve("claims.json");
        name = ClaimsCheck.display(claims);
    }

    @Test
    void everyCaseFailsWithItsMessageAndTheClaimsAfterThemAreStillChecked() {
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, liveRuns);
        result.problems().forEach(problem -> System.out.println("VALIDATOR_CASE_PROBLEM " + problem));
        assertThat(result.problems()).containsExactly(
                name + " C-001 [format]: text expected none on basis observed (its sentence is rendered from its check), found \"Snapshot 297 ranks msft-05 first.\"",
                name + " C-002 [format]: text expected none on basis derived (its sentence is rendered from its check), found \"msft-04 is outside the top 5 in every row of the windowed measurement (296 to 299).\"",
                name + " C-002 [absolute wording]: \"every\" expected no absolute or predictive wording in free text, found in \"msft-04 is outside the top 5 in every row of the windowed measurement (296 to 299).\"",
                name + " C-004 [format]: text expected none on basis derived (its sentence is rendered from its check), found \"The cross-encoder never scored chunk 467 in snapshot 297\"",
                name + " C-004 [absolute wording]: \"never\" expected no absolute or predictive wording in free text, found in \"The cross-encoder never scored chunk 467 in snapshot 297\"",
                name + " C-005 [absolute wording]: \"never\" expected no absolute or predictive wording in free text, found in \"The cross-encoder never scored chunk 467 in snapshot 297.\"",
                name + " C-006 [format]: text expected none on basis derived (its sentence is rendered from its check), found \"With 40 candidates msft-04 will always rank first.\"",
                name + " C-006 [absolute wording]: \"will\", \"always\" expected no absolute or predictive wording in free text, found in \"With 40 candidates msft-04 will always rank first.\"",
                name + " C-007 [absolute wording]: \"will\", \"always\" expected no absolute or predictive wording in free text, found in \"With 40 candidates msft-04 will always rank first.\"",
                name + " C-008 [format]: text expected none on basis derived (its sentence is rendered from its check), found \"In snapshot 297 row 2 of chunk 515 was scored and holds msft-05's ITEM_7 phrase wholly.\"",
                name + " C-011 [experiment]: experiments/bare.json snapshots expected two committed snapshot files, one per setting, found none",
                name + " C-012 [experiment]: experiments/candidates-and-overlap.json snapshots ../" + WINDOWS + "snapshot-296-rerank-candidates-10.json and ../" + WINDOWS
                        + "snapshot-299-rerank-candidates-20-overlap-224.json expected to differ only in rerankCandidates, found 1 other recorded property differing: rerankerScoring max-window/overlap=64/maxWindows=4 against max-window/overlap=224/maxWindows=4",
                name + " C-013 [experiment]: the check reads ../../2026-09-13-reranker/measurement/snapshot-249-rerank-candidates-20.json, expected one of the snapshots of experiments/candidates-10-20.json (snapshots 296 and 297) or an evidence report of one of them",
                name + " C-015 [metric]: check.expected must be a number with at most six decimal places, found 0.7857142",
                name + " C-016 [rank] question msft-05 in " + OUTSIDE + ": file " + OUTSIDE + " resolves to " + ClaimsCheck.display(claims.getParent().resolve(OUTSIDE))
                        + ", outside the evidence root " + ClaimsCheck.display(liveRuns),
                name + " C-017 [topK]: unknown key \"rank\" in row 1",
                name + " C-018 [ruleRow] " + WINDOWS + "snapshot-297-rerank-candidates-20.json against " + WINDOWS + "reference-without-kind-and-tickers.json: tickerHitAt5 expected pass, found fail (the reference records no tickerHitAt5); figureKindTop5 expected pass, found fail (the reference records no kind for "
                        + "aapl-01, aapl-02, aapl-03, aapl-04, aapl-05, aapl-06, aapl-07, aapl-08, aapl-09, aapl-10, aapl-11, aapl-12, aapl-13, aapl-14, msft-01, msft-02, msft-03, msft-04, msft-05, msft-06, msft-07, msft-08, msft-09, msft-10, msft-11, msft-12, msft-13, msft-14, "
                        + "nvda-01, nvda-02, nvda-03, nvda-04, nvda-05, nvda-06, nvda-07, nvda-08, nvda-09, nvda-10, nvda-11, nvda-12, nvda-13, nvda-14)",
                name + " C-020 [rank] question msft-05 in " + WINDOWS + "snapshot-299-rerank-candidates-20-overlap-224.json: rank expected 1, found 10");
        assertThat(result.sentences().get(19)).isEqualTo("The traced reproduction run (snapshot 410) ranks msft-05 10th.");
        assertThat(result.sentences().get(20)).isEqualTo("Snapshot 299 ranks msft-05 10th.");
    }

    @Test
    void case1ATextBesideARankCheckIsRejectedAndTheSentenceStatesTheRankFound() {
        assertThat(problemsOf("C-001")).singleElement().asString().contains("text expected none on basis observed");
        assertThat(sentence(1)).isEqualTo("Snapshot 297 ranks msft-05 10th.");
    }

    @Test
    void case2TheRenderedSentenceNamesExactlyTheRowsChecked() {
        assertThat(problemsOf("C-002")).hasSize(2);
        assertThat(problemsOf("C-003")).isEmpty();
        assertThat(sentence(3)).isEqualTo("msft-04 is outside the top 5 in snapshot 296 (rank 8) and snapshot 297 (no matching chunk in the window).")
                .doesNotContain("every").doesNotContain("298").doesNotContain("299");
    }

    @Test
    void case3NeverInFreeTextIsRejectedOnDerivedAndOnInferredClaims() {
        assertThat(problemsOf("C-004")).extracting(problem -> problem.substring(name.length() + 7, name.length() + 25)).containsExactly("[format]: text exp", "[absolute wording]");
        assertThat(problemsOf("C-005")).singleElement().asString().contains("\"never\" expected no absolute or predictive wording");
    }

    @Test
    void case4APredictionIsRejectedOnDerivedAndOnInferredClaims() {
        assertThat(problemsOf("C-006")).hasSize(2).last().asString().contains("\"will\", \"always\" expected no absolute or predictive wording");
        assertThat(problemsOf("C-007")).singleElement().asString().contains("\"will\", \"always\"");
        assertThat(sentence(6)).isEqualTo("msft-04 is inside the top 5 in snapshot 298 (rank 1).");
    }

    @Test
    void case5RowsOfAnUntracedReportAreRenderedAsRowsTheScoringWouldScoreAndOnlyATracedRerankInputsRowsAsScored() {
        assertThat(problemsOf("C-008")).singleElement().asString().contains("text expected none on basis derived");
        assertThat(sentence(9)).isEqualTo("In the evidence report of snapshot 297, occurrence 1 of 1 of msft-05's accepted phrase \"We report our financial performance based on the"
                + " following three segments\" in chunk 515 lies outside the head window, and of the 2 rows the recorded scoring would score for this chunk (not rows that were"
                + " scored: rerank outcome unknown, no trace), row 2 holds it wholly.");
        assertThat(sentence(10)).isEqualTo("In the evidence report of snapshot 459, occurrence 1 of 1 of msft-05's accepted phrase \"We report our financial performance based on the"
                + " following three segments\" in chunk 515 lies outside the head window, and of the 2 rows scored for this chunk, row 2 holds it wholly.");
        assertThat(sentence(4)).contains("chunk 467 lies wholly inside the head window, and of the 1 row the recorded scoring would score for this chunk (not rows that were scored:");
    }

    @Test
    void case6AnExperimentNeedsTwoSnapshotsDifferingOnlyInItsFactorAndACheckOnThem() {
        assertThat(problemsOf("C-011")).singleElement().asString().contains("bare.json snapshots expected two committed snapshot files");
        assertThat(problemsOf("C-012")).singleElement().asString().contains("expected to differ only in rerankCandidates, found 1 other recorded property differing: rerankerScoring");
        assertThat(problemsOf("C-013")).singleElement().asString().contains("the check reads ../../2026-09-13-reranker/measurement/snapshot-249-rerank-candidates-20.json");
        assertThat(problemsOf("C-014")).isEmpty();
        assertThat(sentence(14)).isEqualTo("Raising rerank candidates from 10 to 20 pushed msft-05 from rank 6 to rank 10. (experiment: rerankCandidates 10 in snapshot 296 against 20 in"
                + " snapshot 297; checked: msft-05 is inside the top 6 in snapshot 296 (rank 6), and outside the top 6 in snapshot 297 (rank 10))");
    }

    @Test
    void cases8To11AreProblemsOfTheirOwnClaims() {
        assertThat(problemsOf("C-015")).singleElement().asString().endsWith("check.expected must be a number with at most six decimal places, found 0.7857142");
        assertThat(problemsOf("C-016")).singleElement().asString().contains("resolves to").contains("outside the evidence root");
        assertThat(problemsOf("C-017")).singleElement().asString().endsWith("unknown key \"rank\" in row 1");
        assertThat(problemsOf("C-018")).singleElement().asString().contains("tickerHitAt5 expected pass, found fail (the reference records no tickerHitAt5)")
                .contains("figureKindTop5 expected pass, found fail (the reference records no kind for aapl-01");
    }

    @Test
    void theRepositoryCheckReportsEveryCaseInOneMessage() {
        List<String> problems = DocumentationClaims.check(temp);
        assertThat(problems).hasSize(18);
        assertThat(DocumentationClaims.message(problems)).startsWith("Claims check failed: 18 problems");
    }

    private List<String> problemsOf(String id) {
        return ClaimsCheck.check(claims, liveRuns).stream().filter(problem -> problem.startsWith(name + " " + id + " ")).toList();
    }

    private String sentence(int index) {
        return ClaimsCheck.evaluate(claims, liveRuns).sentences().get(index);
    }
}
