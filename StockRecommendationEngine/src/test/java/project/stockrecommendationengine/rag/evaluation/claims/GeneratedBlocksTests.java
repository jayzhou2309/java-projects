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
 * Evaluation evidence Milestone 3, E3: a generated block edited by hand, a block citing a missing claim, a citation whose claim or basis
 * does not match, and unbalanced markers each fail the check with file and line; running the generator restores the block. Each case
 * works on a copy of the committed fixtures in a temporary directory; the committed fixture document is itself exactly the generator's
 * output (regenerate with {@code -Dclaims.fixture.write=true}).
 */
class GeneratedBlocksTests {
    static final Path COMMITTED_DOCUMENT = ClaimsCheckTests.FIXTURES.resolve("doc/measurement.md");

    @TempDir Path temp;
    Path document;
    String committed;

    @BeforeEach
    void copyFixtures() throws IOException {
        Path source = ClaimsCheckTests.FIXTURES.getParent();
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
            GeneratedBlocks.write(COMMITTED_DOCUMENT);
            committed = Files.readString(COMMITTED_DOCUMENT);
            Files.writeString(document, committed);
        }
        assertThat(GeneratedBlocks.check(COMMITTED_DOCUMENT)).isEmpty();
        assertThat(GeneratedBlocks.write(document)).as("the generator changes nothing in the committed document").isFalse();
        assertThat(Files.readString(document)).isEqualTo(committed);
        assertThat(committed).contains("""
                    <!-- generated:../true-claims.json#ranks start -->
                    * The reference row ranks aapl-01 first. (C-001, observed)
                    * Row A has no chunk matching msft-04 in its window. (C-002, observed)
                    <!-- generated:../true-claims.json#ranks end -->
                """).contains("    * Raising the window overlap from 64 to 224 lifted msft-04 from rank 8 into the top 5. (C-015, experiment)\n");
    }

    @Test
    void aHandEditedBlockFailsNamingFileAndLineAndTheGeneratorRestoresIt() throws IOException {
        Files.writeString(document, once(committed, "* The reference row ranks aapl-01 first. (C-001, observed)", "* The reference row ranks aapl-01 first of all. (C-001, observed)"));
        List<String> problems = GeneratedBlocks.check(document);
        assertThat(problems).singleElement().asString().contains("measurement.md:7 block generated:../true-claims.json#ranks (lines 6 to 9) differs from the generator's output:")
                .contains("expected \"    * The reference row ranks aapl-01 first. (C-001, observed)\", found \"    * The reference row ranks aapl-01 first of all. (C-001, observed)\"");

        assertThat(GeneratedBlocks.write(document)).isTrue();
        assertThat(Files.readString(document)).isEqualTo(committed);
        assertThat(GeneratedBlocks.check(document)).isEmpty();
    }

    @Test
    void aBlockWithALineRemovedOrAddedFails() throws IOException {
        Files.writeString(document, once(committed, "    * Row A has no chunk matching msft-04 in its window. (C-002, observed)\n", ""));
        assertThat(GeneratedBlocks.check(document)).singleElement().asString()
                .endsWith("measurement.md:8 block generated:../true-claims.json#ranks (lines 6 to 8) differs from the generator's output: expected \"    * Row A has no chunk matching msft-04 in its window. (C-002, observed)\", found no line");
        GeneratedBlocks.write(document);
        assertThat(Files.readString(document)).isEqualTo(committed);

        Files.writeString(document, once(committed, "    * Row A has no chunk matching msft-04 in its window. (C-002, observed)\n",
                "    * Row A has no chunk matching msft-04 in its window. (C-002, observed)\n    * An added sentence. (C-002, observed)\n"));
        assertThat(GeneratedBlocks.check(document)).singleElement().asString()
                .endsWith("measurement.md:9 block generated:../true-claims.json#ranks (lines 6 to 10) differs from the generator's output: expected no line, found \"    * An added sentence. (C-002, observed)\"");
        GeneratedBlocks.write(document);
        assertThat(Files.readString(document)).isEqualTo(committed);
    }

    @Test
    void everyEditedLineOfABlockIsNamedNotOnlyTheFirst() throws IOException {
        // Two edits in one block, the second further down: both lines are named, so a later hand edit is not hidden by an earlier one.
        String edited = once(committed, "hit@5 is 0.750000. (C-004, observed)", "hit@5 is 0.75. (C-004, observed)");
        edited = once(edited, "tokens [12, 16) and", "tokens [12, 17) and");
        Files.writeString(document, edited);
        List<String> problems = GeneratedBlocks.check(document);
        assertThat(problems).hasSize(2);
        assertThat(problems.get(0)).contains("measurement.md:15 block generated:../true-claims.json (lines 11 to 27) differs from the generator's output: expected \"    * The reference row's hit@5 is 0.750000. (C-004, observed)\", found \"    * The reference row's hit@5 is 0.75. (C-004, observed)\"");
        assertThat(problems.get(1)).contains("measurement.md:19 block generated:../true-claims.json (lines 11 to 27) differs from the generator's output: expected \"    * q1's phrase occupies tokens [12, 16)");
        assertThat(GeneratedBlocks.differences(List.of("a", "b", "c"), List.of("a", "x", "c", "d"))).containsExactly(
                new GeneratedBlocks.Difference(1, "expected \"b\", found \"x\""), new GeneratedBlocks.Difference(3, "expected no line, found \"d\""));
        assertThat(GeneratedBlocks.differences(List.of("a", "b", "c"), List.of("c"))).containsExactly(
                new GeneratedBlocks.Difference(0, "expected \"a\", found no line"), new GeneratedBlocks.Difference(0, "expected \"b\", found no line"));
        GeneratedBlocks.write(document);
        assertThat(Files.readString(document)).isEqualTo(committed);
    }

    @Test
    void aBlockCitingAMissingClaimFailsAndTheGeneratorRestoresIt() throws IOException {
        Files.writeString(document, once(committed, "first. (C-001, observed)", "first. (C-999, observed)"));
        List<String> problems = GeneratedBlocks.check(document);
        assertThat(problems).hasSize(2);
        assertThat(problems.get(0)).contains("measurement.md:7 block generated:../true-claims.json#ranks (lines 6 to 9) differs from the generator's output");
        assertThat(problems.get(1)).endsWith("measurement.md:7 citation (C-999 has no claim: not defined in ../true-claims.json");
        GeneratedBlocks.write(document);
        assertThat(Files.readString(document)).isEqualTo(committed);
    }

    @Test
    void aCitationOutsideTheBlocksMustNameAnExistingClaimWithItsBasis() throws IOException {
        Files.writeString(document, once(committed, "(C-007, derived).", "(C-007, observed), and see (C-404).") + "Unlabelled citation (C-008) is fine.\n");
        List<String> problems = GeneratedBlocks.check(document);
        assertThat(problems).hasSize(2);
        assertThat(problems.get(0)).endsWith("measurement.md:28 citation (C-007, observed) states basis observed, but the claim's basis is derived");
        assertThat(problems.get(1)).endsWith("measurement.md:28 citation (C-404 has no claim: not defined in ../true-claims.json");
    }

    @Test
    void aFileWithoutBlocksCitingAClaimFails() throws IOException {
        Path other = temp.resolve("claims/doc/other.md");
        Files.writeString(other, "* A sentence citing (C-001, observed) with no generated block in this file.\n");
        assertThat(GeneratedBlocks.check(other)).singleElement().asString()
                .endsWith("other.md:1 citation (C-001 has no claim: no generated block in this file references a claims file");
    }

    @Test
    void unbalancedNestedOrMismatchedMarkersFailAndTheGeneratorRefusesToWrite() throws IOException {
        Files.writeString(document, once(committed, "    <!-- generated:../true-claims.json end -->\n", ""));
        assertThat(GeneratedBlocks.check(document)).singleElement().asString().endsWith("measurement.md:11 start marker generated:../true-claims.json has no end marker");
        assertThatThrownBy(() -> GeneratedBlocks.write(document)).isInstanceOf(IllegalStateException.class).hasMessageContaining("has no end marker");

        Files.writeString(document, once(committed, "    <!-- generated:../true-claims.json#ranks end -->\n", ""));
        assertThat(GeneratedBlocks.check(document)).anySatisfy(problem -> assertThat(problem).endsWith(
                "measurement.md:10 start marker generated:../true-claims.json inside the block opened at line 6 (expected its end marker first)"));

        Files.writeString(document, once(committed, "<!-- generated:../true-claims.json#ranks end -->", "<!-- generated:../true-claims.json#other end -->"));
        assertThat(GeneratedBlocks.check(document)).anySatisfy(problem -> assertThat(problem).endsWith(
                "measurement.md:9 end marker generated:../true-claims.json#other does not match the start marker generated:../true-claims.json#ranks at line 6"));

        Files.writeString(document, "text\n<!-- generated:../true-claims.json end -->\n");
        assertThat(GeneratedBlocks.check(document)).singleElement().asString().endsWith("measurement.md:2 end marker generated:../true-claims.json has no start marker");
    }

    @Test
    void aMissingClaimsFileOrASelectionOfNoClaimFails() throws IOException {
        Files.writeString(document, """
                <!-- generated:../absent-claims.json start -->
                <!-- generated:../absent-claims.json end -->
                <!-- generated:../true-claims.json#nothing start -->
                <!-- generated:../true-claims.json#nothing end -->
                """);
        List<String> problems = GeneratedBlocks.check(document);
        assertThat(problems).hasSize(2);
        assertThat(problems.get(0)).contains("measurement.md:1 block generated:../absent-claims.json: claims file cannot be read: not found at");
        assertThat(problems.get(1)).endsWith("measurement.md:3 block generated:../true-claims.json#nothing: expected at least one claim, found none with block \"nothing\"");
    }

    @Test
    void markerTextInsideALineIsNotAMarker() throws IOException {
        Files.writeString(document, "Blocks sit between `<!-- generated:<claims path> start -->` and the end marker.\n");
        assertThat(GeneratedBlocks.check(document)).isEmpty();
        assertThat(GeneratedBlocks.hasMarkers(document)).isFalse();
    }

    @Test
    void theRepositoryCheckNamesEveryFailingClaimAndBlockInOneMessage() throws IOException {
        // A repository layout in the temporary directory: one measurement's claims and a documentation file with its block.
        Path root = temp.resolve("repository");
        Path runs = root.resolve("src/main/java/documentation/live-runs/2026-09-13-fixture");
        Files.createDirectories(runs);
        for (String file : List.of("snapshots/reference.json", "snapshots/row-a.json", "snapshots/row-b.json", "snapshots/row-c.json")) {
            Files.createDirectories(runs.resolve(file).getParent());
            Files.copy(temp.resolve("claims").resolve(file), runs.resolve(file));
        }
        Files.writeString(runs.resolve("claims.json"), """
                {"claims": [
                  {"id": "C-001", "text": "msft-04 is outside the top 5 in rows A, B, and C.", "basis": "derived",
                   "check": {"type": "topK", "question": "msft-04", "k": 5, "rows": [{"snapshot": "snapshots/row-a.json", "expected": "outside"},
                     {"snapshot": "snapshots/row-b.json", "expected": "outside"}, {"snapshot": "snapshots/row-c.json", "expected": "outside"}]}},
                  {"id": "C-002", "text": "The reference row ranks msft-04 fourth.", "basis": "observed",
                   "check": {"type": "rank", "snapshot": "snapshots/reference.json", "question": "msft-04", "expected": 4}},
                  {"id": "C-003", "text": "The reference row's hit@5 is 0.800000.", "basis": "observed",
                   "check": {"type": "metric", "snapshot": "snapshots/reference.json", "metric": "hitAt5", "expected": "0.800000"}}
                ]}
                """);
        Path rag = root.resolve("src/main/java/documentation/RAG.md");
        Files.writeString(rag, """
                * Rows
                    <!-- generated:live-runs/2026-09-13-fixture/claims.json start -->
                    * msft-04 is outside the top 5 in rows A, B, and C. (C-001, derived)
                    * The reference row ranks msft-04 4th. (C-002, observed)
                    * The reference row's hit@5 is 0.800000. (C-003, observed)
                    <!-- generated:live-runs/2026-09-13-fixture/claims.json end -->
                """);
        List<String> problems = DocumentationClaims.check(root);
        String message = DocumentationClaims.message(problems);
        assertThat(problems).hasSize(3);
        assertThat(message).startsWith("Claims check failed: 3 problems")
                .contains("claims.json C-001 [topK] question msft-04, k 5: snapshots/row-c.json expected outside, found inside (rank 1)")
                .contains("claims.json C-003 [metric] hitAt5 in snapshots/reference.json: value expected 0.800000, found 0.750000")
                .contains("RAG.md:4 block generated:live-runs/2026-09-13-fixture/claims.json (lines 2 to 6) differs from the generator's output:"
                        + " expected \"    * The reference row ranks msft-04 fourth. (C-002, observed)\", found \"    * The reference row ranks msft-04 4th. (C-002, observed)\"");

        assertThat(DocumentationClaims.generate(root)).containsExactly(rag);
        assertThat(DocumentationClaims.check(root)).hasSize(2).noneMatch(problem -> problem.contains("RAG.md"));
    }
}
