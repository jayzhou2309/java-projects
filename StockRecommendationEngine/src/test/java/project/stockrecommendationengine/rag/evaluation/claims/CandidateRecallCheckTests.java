package project.stockrecommendationengine.rag.evaluation.claims;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/**
 * The {@code bestFusedPosition} and {@code candidateRecall} check types (plan {@code 2026-09-14-retrieval-recall.md}, Milestone 1; RAG.md,
 * Claims, Check types) over copies of the scripted evidence reports in a temporary evidence root: the best fused position is the smallest
 * {@code fusedPosition} over every chunk holding any accepted phrase of the question, candidate recall@K is the share of the report's questions
 * whose best fused position is at most K (or in the fused list for {@code "all"}), both are derived, each sentence is rendered from the values
 * found, and an altered fused position fails the claim naming the question.
 */
class CandidateRecallCheckTests {
    static final Path EVIDENCE = Path.of("src/test/resources/evaluation/evidence");

    /**
     * The traced scripted report with q3's unknown fused position (its retrieval failed) replaced by an observed position 5, so every question
     * has a readable best position: q1 1 (chunk 101; chunk 102 at 3), q2 not fused (its one chunk 201), q3 5 (chunk 201).
     */
    private static ObjectNode report() throws IOException {
        ObjectNode report = (ObjectNode) ClaimsCheck.JSON.readTree(Files.readString(EVIDENCE.resolve("scripted-report.json")));
        ObjectNode q3 = fused(report, 2, 0, 0);
        q3.put("value", 5);
        q3.put("basis", "observed");
        q3.remove("reason");
        q3.put("source", "trace fused (null: not in the fused list)");
        return report;
    }

    private static ObjectNode fused(JsonNode report, int question, int phrase, int chunk) {
        return (ObjectNode) report.get("questions").get(question).get("phrases").get(phrase).get("chunks").get(chunk).get("fusedPosition");
    }

    private static Path write(Path temp, JsonNode report, String claims) throws IOException {
        Files.createDirectories(temp.resolve("evidence"));
        Files.writeString(temp.resolve("evidence/report.json"), ClaimsCheck.JSON.writeValueAsString(report));
        Path file = temp.resolve("claims.json");
        Files.writeString(file, claims);
        return file;
    }

    private static final String CLAIMS = """
            {"claims": [
              {"id": "C-501", "basis": "derived", "check": {"type": "bestFusedPosition", "report": "evidence/report.json", "question": "q1", "expected": 1}},
              {"id": "C-502", "basis": "derived", "check": {"type": "bestFusedPosition", "report": "evidence/report.json", "question": "q2", "expected": null}},
              {"id": "C-503", "basis": "derived", "check": {"type": "candidateRecall", "report": "evidence/report.json", "k": 1, "expected": 0.333333}},
              {"id": "C-504", "basis": "derived", "check": {"type": "candidateRecall", "report": "evidence/report.json", "k": 5, "expected": "0.666667"}},
              {"id": "C-505", "basis": "derived", "check": {"type": "candidateRecall", "report": "evidence/report.json", "k": "all", "expected": 0.666667}}
            ]}
            """;

    @Test
    void trueClaimsPassAndEachSentenceStatesTheValuesFound(@TempDir Path temp) throws IOException {
        ClaimsCheck.Result result = ClaimsCheck.evaluate(write(temp, report(), CLAIMS), temp);
        assertThat(result.problems()).isEmpty();
        assertThat(List.of(1, 2, 3, 4, 5)).extracting(index -> result.sentences().get(index)).containsExactly(
                "In the evidence report of snapshot 459, the best fused position of a chunk holding an accepted phrase of q1 is 1 (chunk 101).",
                "In the evidence report of snapshot 459, the chunk holding an accepted phrase of q2 was not in the fused list.",
                "In the evidence report of snapshot 459, candidate recall@1 is 0.333333: 1 of 3 questions have a chunk holding an accepted phrase at fused position 1 or earlier.",
                "In the evidence report of snapshot 459, candidate recall@5 is 0.666667: 2 of 3 questions have a chunk holding an accepted phrase at fused position 5 or earlier.",
                "In the evidence report of snapshot 459, candidate recall over the whole fused list is 0.666667: 2 of 3 questions have a chunk holding an accepted phrase in the fused list.");
    }

    @Test
    void theBestPositionIsTheMinimumOverEveryHoldingChunkNotTheFirstListed(@TempDir Path temp) throws IOException {
        ObjectNode report = report();
        fused(report, 0, 0, 0).put("value", 7);
        Path claims = write(temp, report, CLAIMS);
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        String name = ClaimsCheck.display(claims);
        assertThat(result.problems()).containsExactly(
                name + " C-501 [bestFusedPosition] question q1 in evidence/report.json: best fused position expected 1, found 3 (chunk 102)",
                name + " C-503 [candidateRecall] k 1 in evidence/report.json: share expected 0.333333, found 0.000000 (0 of 3; not counted: q1 (fused position 3), q2 (not fused), q3 (fused position 5))");
        assertThat(result.sentences().get(1)).isEqualTo("In the evidence report of snapshot 459, the best fused position of a chunk holding an accepted phrase of q1 is 3 (chunk 102).");
    }

    @Test
    void aQuestionMovedOutOfTheFusedListFailsItsClaimAndTheRecallNamingIt(@TempDir Path temp) throws IOException {
        ObjectNode report = report();
        fused(report, 2, 0, 0).putNull("value");
        Path claims = write(temp, report, CLAIMS);
        Files.writeString(claims, Files.readString(claims).replace("{\"claims\": [", """
                {"claims": [
                  {"id": "C-506", "basis": "derived", "check": {"type": "bestFusedPosition", "report": "evidence/report.json", "question": "q3", "expected": 5}},"""));
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(
                name + " C-506 [bestFusedPosition] question q3 in evidence/report.json: best fused position expected 5, found null (not fused)",
                name + " C-504 [candidateRecall] k 5 in evidence/report.json: share expected 0.666667, found 0.333333 (1 of 3; not counted: q2 (not fused), q3 (not fused))",
                name + " C-505 [candidateRecall] k all in evidence/report.json: share expected 0.666667, found 0.333333 (1 of 3; not counted: q2 (not fused), q3 (not fused))");
    }

    @Test
    void anUnknownFusedPositionIsNeverReadAsNotFused(@TempDir Path temp) throws IOException {
        // The untraced report records every fused position as unknown (no trace); the traced one records q3's as unknown (its retrieval failed).
        JsonNode untraced = ClaimsCheck.JSON.readTree(Files.readString(EVIDENCE.resolve("scripted-report-untraced.json")));
        Path claims = write(temp, untraced, """
                {"claims": [
                  {"id": "C-501", "basis": "derived", "check": {"type": "bestFusedPosition", "report": "evidence/report.json", "question": "q2", "expected": null}},
                  {"id": "C-502", "basis": "derived", "check": {"type": "candidateRecall", "report": "evidence/report.json", "k": "all", "expected": 0}}
                ]}
                """);
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(
                name + " C-501 [bestFusedPosition] question q2 in evidence/report.json: question q2 chunk 201 has an unknown fused position (no trace)",
                name + " C-502 [candidateRecall] k all in evidence/report.json: question q1 chunk 101 has an unknown fused position (no trace)");
    }

    @Test
    void malformedChecksAndAnObservedBasisAreNamed(@TempDir Path temp) throws IOException {
        Path claims = write(temp, report(), """
                {"claims": [
                  {"id": "C-501", "basis": "observed", "check": {"type": "bestFusedPosition", "report": "evidence/report.json", "question": "q1", "expected": 1}},
                  {"id": "C-502", "basis": "derived", "check": {"type": "bestFusedPosition", "report": "evidence/report.json", "question": "q1", "expected": 0}},
                  {"id": "C-503", "basis": "derived", "check": {"type": "candidateRecall", "report": "evidence/report.json", "k": "twenty", "expected": 1}},
                  {"id": "C-504", "basis": "derived", "check": {"type": "candidateRecall", "report": "evidence/report.json", "k": 10, "expected": 0.1234567}},
                  {"id": "C-505", "basis": "derived", "check": {"type": "candidateRecall", "report": "evidence/report.json", "k": 10, "expected": 1, "question": "q1"}},
                  {"id": "C-506", "basis": "derived", "check": {"type": "bestFusedPosition", "report": "evidence/report.json", "question": "q9", "expected": 1}}
                ]}
                """);
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(
                name + " C-501 [bestFusedPosition] question q1 in evidence/report.json: basis expected observed, found derived",
                name + " C-502 [bestFusedPosition]: check.expected must be a fused position (positive integer) or null, found 0",
                name + " C-503 [candidateRecall]: check.k must be a positive integer or \"all\", found \"twenty\"",
                name + " C-504 [candidateRecall]: check.expected must be a number with at most six decimal places, found 0.1234567",
                name + " C-505 [candidateRecall]: unknown check key \"question\"",
                name + " C-506 [bestFusedPosition] question q9 in evidence/report.json: the report has no question q9");
    }
}
