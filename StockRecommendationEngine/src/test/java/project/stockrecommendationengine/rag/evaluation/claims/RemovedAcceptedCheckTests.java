package project.stockrecommendationengine.rag.evaluation.claims;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/**
 * The {@code removedAccepted} check type (plan {@code 2026-09-14-retrieval-recall.md}, Milestone 3; RAG.md, Claims, Check types) over copies of
 * the scripted evidence report in a temporary evidence root: the count of questions whose trace {@code removed} list holds a chunk listed under
 * an accepted phrase, derived, rendered from the values found; a removed accepted chunk added to a copy fails a claim of 0 naming the question;
 * a report whose removals are unknown ({@code no trace of removals}) is refused naming every such question, never counted as nothing removed.
 */
class RemovedAcceptedCheckTests {
    private static final Path EVIDENCE = Path.of("src/test/resources/evaluation/evidence");

    /**
     * The scripted report with removals recorded for every question: q1 removed chunk 301 (holds no accepted phrase) as redundant with chunk
     * 101; q2 and q3 removed nothing. q1's phrases are held by chunks 101 and 102, q2's and q3's by chunk 201.
     */
    private static ObjectNode report() throws IOException {
        ObjectNode report = (ObjectNode) ClaimsCheck.JSON.readTree(Files.readString(EVIDENCE.resolve("scripted-report.json")));
        ArrayNode q1 = observedRemoved(report, 0);
        q1.addObject().put("chunkId", 301).put("candidatePosition", 3).put("vectorRank", 2).putNull("keywordRank").putNull("figureRank").put("redundantWith", 101);
        observedRemoved(report, 1);
        observedRemoved(report, 2);
        return report;
    }

    /** Replaces question {@code index}'s removed value with an observed empty list and returns the list. */
    private static ArrayNode observedRemoved(ObjectNode report, int index) {
        ObjectNode removed = ((ObjectNode) report.get("questions").get(index)).putObject("removed");
        ArrayNode list = removed.putArray("value");
        removed.put("basis", "observed");
        removed.put("source", "trace removed (empty: diversification removed no chunk)");
        return list;
    }

    private static Path write(Path temp, JsonNode report, String claims) throws IOException {
        Files.createDirectories(temp.resolve("evidence"));
        Files.writeString(temp.resolve("evidence/report.json"), ClaimsCheck.JSON.writeValueAsString(report));
        Path file = temp.resolve("claims.json");
        Files.writeString(file, claims);
        return file;
    }

    private static String claims(String expected) {
        return """
                {"claims": [
                  {"id": "C-701", "basis": "derived", "check": {"type": "removedAccepted", "report": "evidence/report.json", "expected": %s}}
                ]}
                """.formatted(expected);
    }

    @Test
    void noRemovedAcceptedChunkPassesAndTheSentenceStatesTheQuestionCount(@TempDir Path temp) throws IOException {
        ClaimsCheck.Result result = ClaimsCheck.evaluate(write(temp, report(), claims("0")), temp);
        assertThat(result.problems()).isEmpty();
        assertThat(result.sentences().get(1)).isEqualTo(
                "In the evidence report of snapshot 459, diversification removed no chunk holding an accepted phrase of any of the 3 questions.");
    }

    @Test
    void aRemovedAcceptedChunkFailsAClaimOfZeroNamingTheQuestionAndPassesAClaimOfOne(@TempDir Path temp) throws IOException {
        ObjectNode report = report();
        ((ArrayNode) report.get("questions").get(1).get("removed").get("value")).addObject().put("chunkId", 201).put("candidatePosition", 2)
                .put("vectorRank", 2).putNull("keywordRank").putNull("figureRank").put("redundantWith", 202);
        Path claims = write(temp, report, claims("0"));
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(
                name + " C-701 [removedAccepted] removals in evidence/report.json: count expected 0, found 1 (q2 (chunk 201, redundant with chunk 202))");

        Files.writeString(claims, claims("1"));
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        assertThat(result.problems()).isEmpty();
        assertThat(result.sentences().get(1)).isEqualTo("In the evidence report of snapshot 459, diversification removed a chunk holding an accepted phrase of"
                + " 1 of the 3 questions: q2 (chunk 201, redundant with chunk 202).");
    }

    @Test
    void unknownRemovalsAreRefusedNamingEveryQuestionAndNeverCountedAsNothingRemoved(@TempDir Path temp) throws IOException {
        // The fixture as committed: q1 and q2 traced before removals were traced, q3 without a trace.
        JsonNode unrecorded = ClaimsCheck.JSON.readTree(Files.readString(EVIDENCE.resolve("scripted-report.json")));
        Path claims = write(temp, unrecorded, claims("0"));
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(name + " C-701 [removedAccepted] removals in evidence/report.json: "
                + "question q1 has unknown removals (no trace of removals), question q2 has unknown removals (no trace of removals), "
                + "question q3 has unknown removals (no trace for this question (its retrieval failed))");
    }

    @Test
    void unknownAcceptedPhrasesAndAMissingRemovedValueAreRefused(@TempDir Path temp) throws IOException {
        ObjectNode report = report();
        ObjectNode q2 = (ObjectNode) report.get("questions").get(1);
        q2.putArray("phrases");
        ObjectNode count = q2.putObject("acceptedPhraseCount");
        count.putNull("value");
        count.put("basis", "unknown");
        count.put("reason", "question not in the bundled set");
        ((ObjectNode) report.get("questions").get(2)).remove("removed");
        Path claims = write(temp, report, claims("0"));
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(name + " C-701 [removedAccepted] removals in evidence/report.json: "
                + "question q2 has unknown accepted phrases (acceptedPhraseCount question not in the bundled set), question q3 has no removed value in the report");
    }

    @Test
    void malformedChecksAndAnObservedBasisAreNamed(@TempDir Path temp) throws IOException {
        Path claims = write(temp, report(), """
                {"claims": [
                  {"id": "C-701", "basis": "observed", "check": {"type": "removedAccepted", "report": "evidence/report.json", "expected": 0}},
                  {"id": "C-702", "basis": "derived", "check": {"type": "removedAccepted", "report": "evidence/report.json", "expected": -1}},
                  {"id": "C-703", "basis": "derived", "check": {"type": "removedAccepted", "report": "evidence/report.json", "expected": 0, "question": "q1"}}
                ]}
                """);
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(
                name + " C-701 [removedAccepted] removals in evidence/report.json: basis expected observed, found derived",
                name + " C-702 [removedAccepted]: check.expected must be a question count (non-negative integer), found -1",
                name + " C-703 [removedAccepted]: unknown check key \"question\"");
    }
}
