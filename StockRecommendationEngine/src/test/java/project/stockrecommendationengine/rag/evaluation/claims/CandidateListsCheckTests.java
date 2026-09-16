package project.stockrecommendationengine.rag.evaluation.claims;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * The {@code candidateLists} check type (plan {@code 2026-09-15-reranker-ettin.md}, amendment 3, G7; RAG.md, Claims, Check types) over two
 * hand-built traced snapshots in a temporary evidence root.
 * <p>
 * Reference (snapshot 9101): q1 fused 11, 12, 13, 14 at positions 1 to 4, rerank inputs 11 and 12 (positions 1, 2); q2 fused 21, 22, 23, rerank
 * inputs 21, 22. Candidate (snapshot 9102) as built by {@link #candidate}: q1 identical; q2 by variant: "same" identical; "swap" fused 22, 21, 23
 * (same input set, positions swapped); "set" fused 21, 23, 22 with inputs 21, 23 (input set differs: 22 only in the reference, 23 only in the
 * candidate).
 */
class CandidateListsCheckTests {

    private static final String SNAPSHOT = """
            {"id": %d, "set_version": "t", "question_count": 2, "window_size": 3, "properties": {"trace": true},
             "results": {"questions": [{"id": "q1", "rank": 1}, {"id": "q2", "rank": 2}], "slices": {}, "tickerHitAt5": {},
               "traces": [
                 {"id": "q1", "trace": {"fused": [{"chunkId": 11, "fusedPosition": 1}, {"chunkId": 12, "fusedPosition": 2}, {"chunkId": 13, "fusedPosition": 3},
                   {"chunkId": 14, "fusedPosition": 4}], "rerank": {"outcome": "RERANKED", "candidates": [{"chunkId": 12, "fusedPosition": 2, "rerankedPosition": 1},
                   {"chunkId": 11, "fusedPosition": 1, "rerankedPosition": 2}]}}},
                 {"id": "q2", "trace": {"fused": [%s], "rerank": {"outcome": "RERANKED", "candidates": [%s]}}}]}}
            """;

    private static String reference() {
        return SNAPSHOT.formatted(9101, "{\"chunkId\": 21, \"fusedPosition\": 1}, {\"chunkId\": 22, \"fusedPosition\": 2}, {\"chunkId\": 23, \"fusedPosition\": 3}",
                "{\"chunkId\": 21, \"fusedPosition\": 1, \"rerankedPosition\": 1}, {\"chunkId\": 22, \"fusedPosition\": 2, \"rerankedPosition\": 2}");
    }

    private static String candidate(String variant) {
        return switch (variant) {
            case "same" -> reference().replace("9101", "9102");
            case "swap" -> SNAPSHOT.formatted(9102, "{\"chunkId\": 22, \"fusedPosition\": 1}, {\"chunkId\": 21, \"fusedPosition\": 2}, {\"chunkId\": 23, \"fusedPosition\": 3}",
                    "{\"chunkId\": 22, \"fusedPosition\": 1, \"rerankedPosition\": 2}, {\"chunkId\": 21, \"fusedPosition\": 2, \"rerankedPosition\": 1}");
            default -> SNAPSHOT.formatted(9102, "{\"chunkId\": 21, \"fusedPosition\": 1}, {\"chunkId\": 23, \"fusedPosition\": 2}, {\"chunkId\": 22, \"fusedPosition\": 3}",
                    "{\"chunkId\": 21, \"fusedPosition\": 1, \"rerankedPosition\": 1}, {\"chunkId\": 23, \"fusedPosition\": 2, \"rerankedPosition\": 2}");
        };
    }

    private static Path write(Path temp, String candidate, String claims) throws IOException {
        Files.createDirectories(temp.resolve("evidence"));
        Files.writeString(temp.resolve("evidence/reference.json"), reference());
        Files.writeString(temp.resolve("evidence/candidate.json"), candidate);
        Path file = temp.resolve("claims.json");
        Files.writeString(file, claims);
        return file;
    }

    private static String claims(String fused, String set, String positions) {
        return """
                {"claims": [
                  {"id": "C-701", "basis": "derived", "check": {"type": "candidateLists", "reference": "evidence/reference.json", "candidate": "evidence/candidate.json", "compare": "fusedOrder", "expected": %s}},
                  {"id": "C-702", "basis": "derived", "check": {"type": "candidateLists", "reference": "evidence/reference.json", "candidate": "evidence/candidate.json", "compare": "rerankInputSet", "expected": %s}},
                  {"id": "C-703", "basis": "derived", "check": {"type": "candidateLists", "reference": "evidence/reference.json", "candidate": "evidence/candidate.json", "compare": "rerankInputPositions", "expected": %s}}
                ]}
                """.formatted(fused, set, positions);
    }

    @Test
    void identicalListsPassWithEmptyListsAndTheSentencesSayIdentical(@TempDir Path temp) throws IOException {
        ClaimsCheck.Result result = ClaimsCheck.evaluate(write(temp, candidate("same"), claims("[]", "[]", "[]")), temp);
        assertThat(result.problems()).isEmpty();
        assertThat(result.sentences().get(1)).isEqualTo("Between snapshot 9101 and snapshot 9102, the fused order is identical for each of the 2 questions.");
        assertThat(result.sentences().get(2)).isEqualTo("Between snapshot 9101 and snapshot 9102, the rerank input set is identical for each of the 2 questions.");
        assertThat(result.sentences().get(3)).isEqualTo(
                "Between snapshot 9101 and snapshot 9102, the fused positions of the rerank inputs are identical for each of the 2 questions.");
    }

    @Test
    void reorderedFusedListWithTheSameInputSetDiffersInOrderAndPositionsOnly(@TempDir Path temp) throws IOException {
        Path claims = write(temp, candidate("swap"), claims("[]", "[]", "[]"));
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(
                name + " C-701 [candidateLists] fusedOrder of evidence/candidate.json against evidence/reference.json: questions whose fused order differs expected none, found q2",
                name + " C-703 [candidateLists] rerankInputPositions of evidence/candidate.json against evidence/reference.json: questions whose fused positions of the rerank inputs differ expected none, found q2");

        Files.writeString(claims, claims("[\"q2\"]", "[]", "[\"q2\"]"));
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        assertThat(result.problems()).isEmpty();
        assertThat(result.sentences().get(1)).isEqualTo("Between snapshot 9101 and snapshot 9102, the fused order differs for 1 of the 2 questions: q2.");
    }

    @Test
    void aDifferentInputSetNamesTheChunksOnlyInEachSnapshot(@TempDir Path temp) throws IOException {
        Path claims = write(temp, candidate("set"), claims("[\"q2\"]", "[]", "[\"q2\"]"));
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(name + " C-702 [candidateLists] rerankInputSet of evidence/candidate.json against "
                + "evidence/reference.json: questions whose rerank input set differs expected none, found q2 (only in snapshot 9101: chunk 22; only in snapshot 9102: chunk 23)");

        Files.writeString(claims, claims("[\"q2\"]", "[\"q2\"]", "[\"q2\"]"));
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        assertThat(result.problems()).isEmpty();
        assertThat(result.sentences().get(2)).isEqualTo("Between snapshot 9101 and snapshot 9102, the rerank input set differs for 1 of the 2 questions: "
                + "q2 (only in snapshot 9101: chunk 22; only in snapshot 9102: chunk 23).");
    }

    @Test
    void malformedChecksMissingTracesAndAnObservedBasisAreNamed(@TempDir Path temp) throws IOException {
        Path claims = write(temp, candidate("same").replace("{\"id\": \"q2\", \"trace\"", "{\"id\": \"q2\", \"notTrace\""), """
                {"claims": [
                  {"id": "C-701", "basis": "derived", "check": {"type": "candidateLists", "reference": "evidence/reference.json", "candidate": "evidence/candidate.json", "compare": "fusedOrder", "expected": []}},
                  {"id": "C-702", "basis": "derived", "check": {"type": "candidateLists", "reference": "evidence/reference.json", "candidate": "evidence/reference.json", "compare": "order", "expected": []}},
                  {"id": "C-703", "basis": "derived", "check": {"type": "candidateLists", "reference": "evidence/reference.json", "candidate": "evidence/reference.json", "compare": "fusedOrder", "expected": ["q1", "q1"]}},
                  {"id": "C-704", "basis": "derived", "check": {"type": "candidateLists", "reference": "evidence/reference.json", "candidate": "evidence/reference.json", "compare": "fusedOrder", "expected": []}},
                  {"id": "C-705", "basis": "observed", "check": {"type": "candidateLists", "reference": "evidence/reference.json", "candidate": "evidence/candidate.json", "compare": "rerankInputSet", "expected": [], "question": "q1"}}
                ]}
                """);
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(
                name + " C-701 [candidateLists] fusedOrder of evidence/candidate.json against evidence/reference.json: question q2 has no trace in evidence/candidate.json",
                name + " C-702 [candidateLists]: check.compare must be fusedOrder, rerankInputSet, or rerankInputPositions, found \"order\"",
                name + " C-703 [candidateLists]: check.expected must list the distinct question ids that differ (an empty list for none), found [\"q1\",\"q1\"]",
                name + " C-704 [candidateLists] fusedOrder of evidence/reference.json against evidence/reference.json: check.reference and check.candidate name the same file evidence/reference.json",
                name + " C-705 [candidateLists]: unknown check key \"question\"");
    }
}
