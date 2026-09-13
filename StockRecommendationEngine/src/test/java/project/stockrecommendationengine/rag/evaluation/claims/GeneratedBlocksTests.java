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
 * Evaluation evidence Milestone 3 with plan amendments 4 and 6, E3: a generated block edited by hand, a block citing a missing claim, a
 * citation outside a block, a malformed citation or one whose claim or basis does not match, a line introducing a block with causal or
 * absolute wording or numbers, unbalanced markers, and a claim without a rendered sentence each fail the check with file and line; running
 * the generator restores the block, and a file it cannot generate is reported while the other files are still generated. Each case works on
 * a copy of the committed fixtures in a temporary directory (the evidence root); the committed fixture document is itself exactly the
 * generator's output (regenerate with {@code -Dclaims.fixture.write=true}).
 */
class GeneratedBlocksTests {
    static final Path COMMITTED_DOCUMENT = ClaimsCheckTests.FIXTURES.resolve("doc/measurement.md");
    static final String C001 = "    * The reference row (snapshot 1) ranks aapl-01 1st. (C-001, observed)";
    static final String C002 = "    * Row A (snapshot 2) ranks msft-04 outside its window of 10 results (no matching chunk). (C-002, observed)";

    @TempDir Path temp;
    Path document;
    String committed;

    @BeforeEach
    void copyFixtures() throws IOException {
        Path source = ClaimsCheckTests.ROOT;
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.filter(p -> p.startsWith(source.resolve("claims")) || p.startsWith(source.resolve("evidence"))).toList()) {
                Path target = temp.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
        document = temp.resolve("claims/doc/measurement.md");
        committed = Files.readString(document);
    }

    /** {@code text} with the first occurrence of {@code from} replaced (in the fixture document, the one in the first block it appears in). */
    static String once(String text, String from, String to) {
        int at = text.indexOf(from);
        assertThat(at).as("occurrence of " + from).isNotNegative();
        return text.substring(0, at) + to + text.substring(at + from.length());
    }

    @Test
    void theCommittedFixtureDocumentIsExactlyWhatTheGeneratorWrites() throws IOException {
        if (Boolean.getBoolean("claims.fixture.write")) {
            GeneratedBlocks.write(COMMITTED_DOCUMENT, ClaimsCheckTests.ROOT);
            committed = Files.readString(COMMITTED_DOCUMENT);
            Files.writeString(document, committed);
        }
        assertThat(GeneratedBlocks.check(COMMITTED_DOCUMENT, ClaimsCheckTests.ROOT)).isEmpty();
        assertThat(GeneratedBlocks.write(document, temp)).as("the generator changes nothing in the committed document").isEqualTo(new GeneratedBlocks.Generation(false, List.of()));
        assertThat(Files.readString(document)).isEqualTo(committed);
        assertThat(committed).contains("    <!-- generated:../true-claims.json#ranks start -->\n" + C001 + "\n" + C002 + "\n    <!-- generated:../true-claims.json#ranks end -->\n")
                .contains("    * Raising the window overlap from 64 to 224 lifted msft-04 from rank 8 into the top 5. (experiment: rerankerScoring"
                        + " max-window/overlap=64/maxWindows=4 in snapshot 3 against max-window/overlap=224/maxWindows=4 in snapshot 4; checked: msft-04 is outside"
                        + " the top 5 in row B (snapshot 3, rank 8), and inside the top 5 in row C (snapshot 4, rank 1)) (C-015, experiment)\n");
    }

    @Test
    void aHandEditedBlockFailsNamingFileAndLineAndTheGeneratorRestoresIt() throws IOException {
        Files.writeString(document, once(committed, C001, "    * The reference row ranks aapl-01 first of all. (C-001, observed)"));
        List<String> problems = GeneratedBlocks.check(document, temp);
        assertThat(problems).singleElement().asString().contains("measurement.md:7 block generated:../true-claims.json#ranks (lines 6 to 9) differs from the generator's output:")
                .contains("expected \"" + C001 + "\", found \"    * The reference row ranks aapl-01 first of all. (C-001, observed)\"");

        assertThat(GeneratedBlocks.write(document, temp).changed()).isTrue();
        assertThat(Files.readString(document)).isEqualTo(committed);
        assertThat(GeneratedBlocks.check(document, temp)).isEmpty();
    }

    @Test
    void aBlockWithALineRemovedOrAddedFails() throws IOException {
        Files.writeString(document, once(committed, C002 + "\n", ""));
        assertThat(GeneratedBlocks.check(document, temp)).singleElement().asString()
                .endsWith("measurement.md:8 block generated:../true-claims.json#ranks (lines 6 to 8) differs from the generator's output: expected \"" + C002 + "\", found no line");
        GeneratedBlocks.write(document, temp);
        assertThat(Files.readString(document)).isEqualTo(committed);

        Files.writeString(document, once(committed, C002 + "\n", C002 + "\n    * An added sentence. (C-002, observed)\n"));
        assertThat(GeneratedBlocks.check(document, temp)).singleElement().asString()
                .endsWith("measurement.md:9 block generated:../true-claims.json#ranks (lines 6 to 10) differs from the generator's output: expected no line, found \"    * An added sentence. (C-002, observed)\"");
        GeneratedBlocks.write(document, temp);
        assertThat(Files.readString(document)).isEqualTo(committed);
    }

    @Test
    void everyEditedLineOfABlockIsNamedNotOnlyTheFirst() throws IOException {
        // Two edits in one block, the second further down: both lines are named, so a later hand edit is not hidden by an earlier one.
        String edited = once(committed, "(snapshot 1) is 0.750000. (C-004, observed)", "(snapshot 1) is 0.75. (C-004, observed)");
        edited = once(edited, "tokens [12, 16) and", "tokens [12, 17) and");
        Files.writeString(document, edited);
        List<String> problems = GeneratedBlocks.check(document, temp);
        assertThat(problems).hasSize(2);
        assertThat(problems.get(0)).contains("measurement.md:15 block generated:../true-claims.json (lines 11 to 30) differs from the generator's output: expected \"    * The hit@5 of the reference row (snapshot 1) is 0.750000. (C-004, observed)\", found \"    * The hit@5 of the reference row (snapshot 1) is 0.75. (C-004, observed)\"");
        assertThat(problems.get(1)).contains("measurement.md:19 block generated:../true-claims.json (lines 11 to 30) differs from the generator's output: expected \"    * In the traced scripted report (the evidence report of snapshot 459), occurrence 1 of 1 of q1's accepted phrase \"w012 w013 w014 w015\" in chunk 101 spans tokens [12, 16)");
        assertThat(GeneratedBlocks.differences(List.of("a", "b", "c"), List.of("a", "x", "c", "d"))).containsExactly(
                new GeneratedBlocks.Difference(1, "expected \"b\", found \"x\""), new GeneratedBlocks.Difference(3, "expected no line, found \"d\""));
        assertThat(GeneratedBlocks.differences(List.of("a", "b", "c"), List.of("c"))).containsExactly(
                new GeneratedBlocks.Difference(0, "expected \"a\", found no line"), new GeneratedBlocks.Difference(0, "expected \"b\", found no line"));
        GeneratedBlocks.write(document, temp);
        assertThat(Files.readString(document)).isEqualTo(committed);
    }

    @Test
    void aSentenceChangedInTheClaimsFileByHandCannotReachTheBlock() throws IOException {
        // A sentence of an observed claim is not in claims.json: changing the check's expected rank leaves the block as rendered from the
        // snapshot, so the claim fails instead of the documentation drifting.
        Path claims = temp.resolve("claims/true-claims.json");
        Files.writeString(claims, once(Files.readString(claims), "\"question\": \"aapl-01\", \"expected\": 1", "\"question\": \"aapl-01\", \"expected\": 2"));
        assertThat(GeneratedBlocks.check(document, temp)).isEmpty();
        assertThat(ClaimsCheck.check(claims, temp)).singleElement().asString().endsWith("C-001 [rank] question aapl-01 in snapshots/reference.json: rank expected 2, found 1");
        assertThat(GeneratedBlocks.write(document, temp).changed()).isFalse();
    }

    @Test
    void aBlockCitingAMissingClaimFailsAndTheGeneratorRestoresIt() throws IOException {
        Files.writeString(document, once(committed, "1st. (C-001, observed)", "1st. (C-999, observed)"));
        List<String> problems = GeneratedBlocks.check(document, temp);
        assertThat(problems).hasSize(2);
        assertThat(problems.get(0)).contains("measurement.md:7 block generated:../true-claims.json#ranks (lines 6 to 9) differs from the generator's output");
        assertThat(problems.get(1)).endsWith("measurement.md:7 citation (C-999 has no claim: not defined in ../true-claims.json");
        GeneratedBlocks.write(document, temp);
        assertThat(Files.readString(document)).isEqualTo(committed);
    }

    static final String OUTSIDE = " is outside a generated block: expected claim citations only inside generated blocks (prose outside a block is not checked, so it may not cite a claim)";
    static final String NORMALISED = " (read with soft hyphens, invisible characters, and markdown and HTML marks removed)";

    @Test
    void theHeadingOfFindingOneAboveARealBlockFailsNamingItsCitationAndItsScreenedWords() throws IOException {
        // Plan amendment 6, finding 1: this false and causal heading above a real block passed verify at e44f4c1 (snapshot 298 ranks msft-04 1st).
        String heading = "Every windowed configuration keeps msft-04 outside the top 5, which proves windowing cannot help FIGURE questions (C-001, derived):";
        Files.writeString(document, once(committed, "* Ranks, selected by block name", heading));
        String display = ClaimsCheck.display(document);
        GeneratedBlocks.check(document, temp).forEach(problem -> System.out.println("AMENDMENT_6_FINDING_1 " + problem));
        assertThat(GeneratedBlocks.check(document, temp)).containsExactly(
                display + ":5 line introducing block generated:../true-claims.json#ranks (start marker at line 6): expected no causal wording, found \"help\";"
                        + " expected no absolute or predictive wording, found \"Every\", \"proves\"; expected no digits or spelled-out numbers, found \"04\", \"5\", \"001\"",
                display + ":5 citation (C-001, derived)" + OUTSIDE);
    }

    @Test
    void everyCitationOutsideABlockFailsHoweverItIsWrittenAndWhetherOrNotItsClaimExists() throws IOException {
        String prose = "* Row C drops a FIGURE question (C-007, derived), as the claims say (c-008) and (C-404; observed); hidden: (C\u00AD-003, derived) and (**C-004**, observed); a dash: (C\u2013005, observed).\n";
        Files.writeString(document, committed + prose);
        String display = ClaimsCheck.display(document);
        GeneratedBlocks.check(document, temp).forEach(problem -> System.out.println("AMENDMENT_6_OUTSIDE " + problem));
        assertThat(GeneratedBlocks.check(document, temp)).containsExactly(
                display + ":32 citation (C-007, derived)" + OUTSIDE,
                display + ":32 citation (c-008)" + OUTSIDE,
                display + ":32 citation (C-404; observed)" + OUTSIDE,
                display + ":32 citation (C\u2013005, observed)" + OUTSIDE,
                display + ":32 citation (C-003, derived)" + NORMALISED + OUTSIDE,
                display + ":32 citation (C-004, observed)" + NORMALISED + OUTSIDE);
    }

    @Test
    void aCitationInsideABlockMustBeWellFormedNameAClaimAndStateItsBasis() throws IOException {
        String edited = once(committed, C001, C001.replace("(C-001, observed)", "(C-001, Observed)"));
        edited = once(edited, C002, C002.replace("(C-002, observed)", "(C-002; observed)"));
        edited = once(edited, "(snapshot 1) is 0.750000. (C-004, observed)", "(snapshot 1) is 0.750000. (C-004, derived)");
        Files.writeString(document, edited);
        String display = ClaimsCheck.display(document);
        String form = " expected the form (C-nnn, <basis>), with a comma, one space, and the basis in lower case";
        List<String> problems = GeneratedBlocks.check(document, temp);
        problems.stream().filter(problem -> problem.contains(" citation ")).forEach(problem -> System.out.println("AMENDMENT_6_INSIDE " + problem));
        assertThat(problems).filteredOn(problem -> problem.contains(" citation ")).containsExactly(
                display + ":7 citation (C-001, Observed)" + form,
                display + ":8 citation (C-002; observed)" + form,
                display + ":15 citation (C-004, derived) states basis derived, but the claim's basis is observed");
        assertThat(problems).filteredOn(problem -> problem.contains(" differs from the generator's output")).hasSize(3);
        GeneratedBlocks.write(document, temp);
        assertThat(Files.readString(document)).isEqualTo(committed);
    }

    @Test
    void aFileWithoutBlocksCitingAClaimFails() throws IOException {
        Path other = temp.resolve("claims/doc/other.md");
        Files.writeString(other, "* A sentence citing (C-001, observed) with no generated block in this file.\n");
        assertThat(GeneratedBlocks.check(other, temp)).containsExactly(ClaimsCheck.display(other) + ":1 citation (C-001, observed)" + OUTSIDE);
    }

    @Test
    void theLineIntroducingEachBlockIsScreenedForWordingAndNumbersAfterNormalising() throws IOException {
        // The nearest line above a start marker that is not blank once normalised is screened (an HTML comment or a non-breaking space alone
        // is skipped); a block directly after another block's end marker has no line of its own, and a start marker on the first line has none.
        Files.writeString(document, """
                <!-- generated:../true-claims.json#ranks start -->
                <!-- generated:../true-claims.json#ranks end -->
                <!-- generated:../true-claims.json#ranks start -->
                <!-- generated:../true-claims.json#ranks end -->
                * The forty-candidate row, sec&shy;ond of the runs

                <!-- generated:../true-claims.json#ranks start -->
                <!-- generated:../true-claims.json#ranks end -->
                * Rows the overlap c**ause**d, n\u00ADever changing
                <!-- generated:../true-claims.json#ranks start -->
                <!-- generated:../true-claims.json#ranks end -->
                * Rows selected by block name
                <!-- generated:../true-claims.json#ranks start -->
                <!-- generated:../true-claims.json#ranks end -->
                * Every row keeps its rank
                <!-- a comment -->
                &nbsp;
                <!-- generated:../true-claims.json#ranks start -->
                <!-- generated:../true-claims.json#ranks end -->
                """);
        String display = ClaimsCheck.display(document);
        GeneratedBlocks.check(document, temp).stream().filter(problem -> problem.contains(" line introducing ")).forEach(problem -> System.out.println("AMENDMENT_6_INTRODUCING " + problem));
        assertThat(GeneratedBlocks.check(document, temp)).filteredOn(problem -> problem.contains(" line introducing ")).containsExactly(
                display + ":5 line introducing block generated:../true-claims.json#ranks (start marker at line 7): expected no digits or spelled-out numbers, found \"forty\", \"second\"" + NORMALISED,
                display + ":9 line introducing block generated:../true-claims.json#ranks (start marker at line 10): expected no causal wording, found \"caused\"" + NORMALISED
                        + "; expected no absolute or predictive wording, found \"never\"" + NORMALISED,
                display + ":15 line introducing block generated:../true-claims.json#ranks (start marker at line 18): expected no absolute or predictive wording, found \"Every\"");
    }

    @Test
    void unbalancedNestedOrMismatchedMarkersFailAndTheGeneratorReportsTheFileWithoutWritingIt() throws IOException {
        String unbalanced = once(committed, "    <!-- generated:../true-claims.json end -->\n", "");
        Files.writeString(document, unbalanced);
        assertThat(GeneratedBlocks.check(document, temp)).singleElement().asString().endsWith("measurement.md:11 start marker generated:../true-claims.json has no end marker");
        GeneratedBlocks.Generation generation = GeneratedBlocks.write(document, temp);
        assertThat(generation.changed()).isFalse();
        assertThat(generation.problems()).singleElement().asString().endsWith("measurement.md:11 start marker generated:../true-claims.json has no end marker (file not generated)");
        assertThat(Files.readString(document)).isEqualTo(unbalanced);

        Files.writeString(document, once(committed, "    <!-- generated:../true-claims.json#ranks end -->\n", ""));
        assertThat(GeneratedBlocks.check(document, temp)).anySatisfy(problem -> assertThat(problem).endsWith(
                "measurement.md:10 start marker generated:../true-claims.json inside the block opened at line 6 (expected its end marker first)"));

        Files.writeString(document, once(committed, "<!-- generated:../true-claims.json#ranks end -->", "<!-- generated:../true-claims.json#other end -->"));
        assertThat(GeneratedBlocks.check(document, temp)).anySatisfy(problem -> assertThat(problem).endsWith(
                "measurement.md:9 end marker generated:../true-claims.json#other does not match the start marker generated:../true-claims.json#ranks at line 6"));

        Files.writeString(document, "text\n<!-- generated:../true-claims.json end -->\n");
        assertThat(GeneratedBlocks.check(document, temp)).singleElement().asString().endsWith("measurement.md:2 end marker generated:../true-claims.json has no start marker");
    }

    @Test
    void aMissingOrOutsideClaimsFileOrASelectionOfNoClaimFails() throws IOException {
        Files.writeString(document, """
                <!-- generated:../absent-claims.json start -->
                <!-- generated:../absent-claims.json end -->
                <!-- generated:../true-claims.json#nothing start -->
                <!-- generated:../true-claims.json#nothing end -->
                <!-- generated:../../../outside/claims.json start -->
                <!-- generated:../../../outside/claims.json end -->
                """);
        List<String> problems = GeneratedBlocks.check(document, temp);
        assertThat(problems).hasSize(3);
        assertThat(problems.get(0)).contains("measurement.md:1 block generated:../absent-claims.json: claims file cannot be read: not found at");
        assertThat(problems.get(1)).endsWith("measurement.md:3 block generated:../true-claims.json#nothing: expected at least one claim, found none with block \"nothing\"");
        assertThat(problems.get(2)).endsWith("measurement.md:5 block generated:../../../outside/claims.json: claims file cannot be read: resolves to "
                + ClaimsCheck.display(temp.resolve("../outside/claims.json")) + ", outside the evidence root " + ClaimsCheck.display(temp));
    }

    @Test
    void aBlockWithAClaimThatCannotBeRenderedIsReportedItsOtherLinesAreStillComparedAndOtherBlocksAreGenerated() throws IOException {
        // C-002's snapshot is removed, so C-002 and C-003 (which reads it) have no rendered sentence. C-001 moves to a third block, #first.
        Files.delete(temp.resolve("claims/snapshots/row-a.json"));
        Path claims = temp.resolve("claims/true-claims.json");
        Files.writeString(claims, once(Files.readString(claims), "{\"id\": \"C-001\", \"basis\": \"observed\", \"block\": \"ranks\",", "{\"id\": \"C-001\", \"basis\": \"observed\", \"block\": \"first\","));
        String edited = once(committed, C001 + "\n", "").replace("* Prose outside a block",
                "* A claim alone\n    <!-- generated:../true-claims.json#first start -->\n    * edited\n    <!-- generated:../true-claims.json#first end -->\n* Prose outside a block");
        String display = ClaimsCheck.display(document);
        String why = " no rendered sentence (the problems of " + ClaimsCheck.display(claims) + " say why), so ";

        // The check still names a hand edit elsewhere in a block holding an unrendered claim, a missing placeholder line, and a hand edit in
        // another block; an edit to an unrendered claim's own line (C-003 here) is not seen, since that line is compared by its citation only.
        String handEdited = once(once(edited, "(snapshot 1) is 0.750000. (C-004, observed)", "(snapshot 1) is 0.75. (C-004, observed)"),
                "    * msft-04 is inside the top 5 in the reference row", "    * msft-04 is in the top 5 in the reference row");
        handEdited = once(handEdited, C002 + "\n", "");
        Files.writeString(document, handEdited);
        assertThat(GeneratedBlocks.check(document, temp)).containsExactly(
                display + ":6 block generated:../true-claims.json#ranks: C-002 has" + why + "its line is compared by citation only and the block is not generated",
                display + ":7 block generated:../true-claims.json#ranks (lines 6 to 7) differs from the generator's output: expected a line \"    * ...\" ending \" (C-002, observed)\" (its sentence was not rendered), found no line",
                display + ":9 block generated:../true-claims.json: C-002 and C-003 have" + why + "their lines are compared by citation only and the block is not generated",
                display + ":13 block generated:../true-claims.json (lines 9 to 28) differs from the generator's output: expected \"    * The hit@5 of the reference row (snapshot 1) is 0.750000. (C-004, observed)\", found \"    * The hit@5 of the reference row (snapshot 1) is 0.75. (C-004, observed)\"",
                display + ":31 block generated:../true-claims.json#first (lines 30 to 32) differs from the generator's output: expected \"" + C001 + "\", found \"    * edited\"");

        // The generator reports both blocks, leaves them as they are, and still regenerates #first.
        Files.writeString(document, edited);
        GeneratedBlocks.Generation generation = GeneratedBlocks.write(document, temp);
        assertThat(generation.changed()).isTrue();
        assertThat(generation.problems()).containsExactly(
                display + ":6 block generated:../true-claims.json#ranks: C-002 has" + why + "its line is compared by citation only and the block is not generated (block not generated)",
                display + ":10 block generated:../true-claims.json: C-002 and C-003 have" + why + "their lines are compared by citation only and the block is not generated (block not generated)");
        assertThat(Files.readString(document)).isEqualTo(edited.replace("    * edited\n", C001 + "\n"));
    }

    @Test
    void markerTextInsideALineIsNotAMarker() throws IOException {
        Files.writeString(document, "Blocks sit between `<!-- generated:<claims path> start -->` and the end marker.\n");
        assertThat(GeneratedBlocks.check(document, temp)).isEmpty();
        assertThat(GeneratedBlocks.hasMarkers(document)).isFalse();
    }

    @Test
    void theRepositoryCheckNamesEveryFailingClaimAndBlockInOneMessage() throws IOException {
        // A repository layout in the temporary directory: one measurement's claims and a documentation file with its block.
        Path root = temp.resolve("repository");
        Path runs = repositoryRuns(root);
        Files.writeString(runs.resolve("claims.json"), """
                {"claims": [
                  {"id": "C-001", "basis": "derived",
                   "check": {"type": "topK", "question": "msft-04", "k": 5, "rows": [{"snapshot": "snapshots/row-a.json", "expected": "outside"},
                     {"snapshot": "snapshots/row-b.json", "expected": "outside"}, {"snapshot": "snapshots/row-c.json", "expected": "outside"}]}},
                  {"id": "C-002", "basis": "observed",
                   "check": {"type": "rank", "snapshot": "snapshots/reference.json", "question": "msft-04", "expected": 4}},
                  {"id": "C-003", "basis": "observed",
                   "check": {"type": "metric", "snapshot": "snapshots/reference.json", "metric": "hitAt5", "expected": "0.800000"}}
                ]}
                """);
        Path rag = root.resolve("src/main/java/documentation/RAG.md");
        Files.writeString(rag, """
                * Rows
                    <!-- generated:live-runs/2026-09-13-fixture/claims.json start -->
                    * msft-04 is outside the top 5 in every row. (C-001, derived)
                    * Snapshot 1 ranks msft-04 4th. (C-002, observed)
                    * The hit@5 of snapshot 1 is 0.800000. (C-003, observed)
                    <!-- generated:live-runs/2026-09-13-fixture/claims.json end -->
                """);
        List<String> problems = DocumentationClaims.check(root);
        String message = DocumentationClaims.message(problems);
        assertThat(problems).hasSize(4);
        assertThat(message).startsWith("Claims check failed: 4 problems")
                .contains("claims.json C-001 [topK] question msft-04, k 5: snapshots/row-c.json expected outside, found inside (rank 1)")
                .contains("claims.json C-003 [metric] hitAt5 in snapshots/reference.json: value expected 0.800000, found 0.750000")
                .contains("RAG.md:3 block generated:live-runs/2026-09-13-fixture/claims.json (lines 2 to 6) differs from the generator's output:"
                        + " expected \"    * msft-04 is outside the top 5 in snapshot 2 (no matching chunk in the window) and snapshot 3 (rank 8), and inside the top 5 in snapshot 4 (rank 1). (C-001, derived)\","
                        + " found \"    * msft-04 is outside the top 5 in every row. (C-001, derived)\"")
                .contains("RAG.md:5 block generated:live-runs/2026-09-13-fixture/claims.json (lines 2 to 6) differs from the generator's output:"
                        + " expected \"    * The hit@5 of snapshot 1 is 0.750000. (C-003, observed)\", found \"    * The hit@5 of snapshot 1 is 0.800000. (C-003, observed)\"");

        assertThat(DocumentationClaims.generate(root)).isEqualTo(new DocumentationClaims.Generated(List.of(rag), List.of()));
        assertThat(DocumentationClaims.check(root)).hasSize(2).noneMatch(problem -> problem.contains("RAG.md"));
    }

    @Test
    void theGeneratorReportsADocumentWithAnUnbalancedMarkerAndStillGeneratesTheNextDocument() throws IOException {
        // Plan amendment 4, validator case 12: a.md (first in path order) has a start marker without its end marker; b.md has a hand-edited
        // block. The generator reports a.md, leaves it unwritten, and still regenerates b.md.
        Path root = temp.resolve("repository");
        Path runs = repositoryRuns(root);
        Files.writeString(runs.resolve("claims.json"), """
                {"claims": [{"id": "C-001", "basis": "observed", "check": {"type": "rank", "snapshot": "snapshots/reference.json", "question": "msft-04", "expected": 4}}]}
                """);
        Path first = root.resolve("src/main/java/documentation/a.md");
        String unbalanced = "* Rows\n    <!-- generated:live-runs/2026-09-13-fixture/claims.json start -->\n    * edited\n";
        Files.writeString(first, unbalanced);
        Path second = root.resolve("src/main/java/documentation/b.md");
        Files.writeString(second, "* Rows\n    <!-- generated:live-runs/2026-09-13-fixture/claims.json start -->\n    * edited\n    <!-- generated:live-runs/2026-09-13-fixture/claims.json end -->\n");

        DocumentationClaims.Generated generated = DocumentationClaims.generate(root);
        System.out.println("CASE_12_GENERATE changed=" + generated.changed().stream().map(path -> path.getFileName().toString()).toList());
        generated.problems().forEach(problem -> System.out.println("CASE_12_GENERATE_PROBLEM " + problem));
        assertThat(generated.problems()).containsExactly(ClaimsCheck.display(first) + ":2 start marker generated:live-runs/2026-09-13-fixture/claims.json has no end marker (file not generated)");
        assertThat(generated.changed()).containsExactly(second);
        assertThat(Files.readString(first)).isEqualTo(unbalanced);
        assertThat(Files.readString(second)).isEqualTo("* Rows\n    <!-- generated:live-runs/2026-09-13-fixture/claims.json start -->\n"
                + "    * Snapshot 1 ranks msft-04 4th. (C-001, observed)\n    <!-- generated:live-runs/2026-09-13-fixture/claims.json end -->\n");
        assertThat(DocumentationClaims.check(root)).containsExactly(ClaimsCheck.display(first) + ":2 start marker generated:live-runs/2026-09-13-fixture/claims.json has no end marker");
    }

    private Path repositoryRuns(Path root) throws IOException {
        Path runs = root.resolve("src/main/java/documentation/live-runs/2026-09-13-fixture");
        Files.createDirectories(runs);
        for (String file : List.of("snapshots/reference.json", "snapshots/row-a.json", "snapshots/row-b.json", "snapshots/row-c.json")) {
            Files.createDirectories(runs.resolve(file).getParent());
            Files.copy(temp.resolve("claims").resolve(file), runs.resolve(file));
        }
        return runs;
    }
}
