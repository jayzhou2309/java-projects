package project.stockrecommendationengine.rag.evaluation.claims;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/**
 * The {@code blend} check type (plan {@code 2026-09-14-rerank-blend.md}, Milestone 1; RAG.md, Claims, Check types) over a hand-built traced
 * snapshot and evidence report in a temporary evidence root. Window 3, rerank-candidates 4, five fused chunks per question.
 * <p>
 * q1: fused chunks 11 to 15 at positions 1 to 5; rerank inputs (chunk f r) 14 4 1, 13 3 2, 12 2 3, 11 1 4; accepted chunk 14. At w 1 the order
 * is 14, 13, 12 (rank 1, the stored rank); at w 0 it is 11, 12, 13 (14 is fourth, outside the window: null); at k 1 and w 0.5 chunks 11 and 14
 * both score 0.5/5 + 0.5/2 = 0.5/2 + 0.5/5 = 0.35 and 12 and 13 both 0.5/4 + 0.5/3, so the tie by smaller fused position gives 11, 14, 12 (rank 2).
 * <p>
 * q2: fused chunks 21 to 25; rerank inputs 22 2 1, 21 1 2, 23 3 3, 24 4 4; accepted chunk 23, third at w 1, w 0, and k 1 w 0.5 (rank 3).
 * <p>
 * Hand-computed metrics over q1 and q2: w 0 (k 60) hit@5 0.500000 (1 of 2), MRR 0.333333333333 / 2 = 0.166667; k 1 w 0.5 hit@5 1.000000, MRR
 * (0.5 + 0.333333333333) / 2 = 0.416667.
 */
class BlendCheckTests {

    private static final String SNAPSHOT = """
            {"id": 9001, "set_version": "t", "question_count": 2, "window_size": 3, "hit_at_1": 0.5, "hit_at_3": 1.0, "hit_at_5": 1.0, "mrr": 0.666667,
             "properties": {"rerank": true, "rerankCandidates": 4},
             "results": {"questions": [{"id": "q1", "ticker": "T", "kind": "FIGURE", "rank": 1, "matchedChunkId": 14},
                                       {"id": "q2", "ticker": "T", "kind": "NARRATIVE", "rank": 3, "matchedChunkId": 23}],
                         "slices": {}, "tickerHitAt5": {"T": 1.0},
                         "traces": [
                           {"id": "q1", "trace": {"fused": [%s], "rerank": {"outcome": "%s", "inputCount": 4, "candidates": [
                             {"chunkId": 14, "fusedPosition": 4, "rerankedPosition": 1}, {"chunkId": 13, "fusedPosition": 3, "rerankedPosition": 2},
                             {"chunkId": 12, "fusedPosition": 2, "rerankedPosition": 3}, {"chunkId": 11, "fusedPosition": 1, "rerankedPosition": 4}]}}},
                           {"id": "q2", "trace": {"fused": [%s], "rerank": {"outcome": "RERANKED", "inputCount": 4, "candidates": [
                             {"chunkId": 22, "fusedPosition": 2, "rerankedPosition": 1}, {"chunkId": 21, "fusedPosition": 1, "rerankedPosition": 2},
                             {"chunkId": 23, "fusedPosition": 3, "rerankedPosition": 3}, {"chunkId": 24, "fusedPosition": 4, "rerankedPosition": 4}]}}}]}}
            """;

    private static String fused(int first) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 5; i++) out.append(i == 0 ? "" : ", ").append("{\"chunkId\": ").append(first + i).append(", \"fusedPosition\": ").append(i + 1).append("}");
        return out.toString();
    }

    private static String report(long snapshotId) {
        return """
                {"snapshotId": %d, "questions": [
                  {"id": "q1", "acceptedPhraseCount": {"value": 1, "basis": "observed", "source": "set"}, "phrases": [{"chunks": [{"chunkId": 14}]}]},
                  {"id": "q2", "acceptedPhraseCount": {"value": 1, "basis": "observed", "source": "set"}, "phrases": [{"chunks": [{"chunkId": 23}]}]}]}
                """.formatted(snapshotId);
    }

    private static Path write(Path temp, String outcome, long reportSnapshotId, String claims) throws IOException {
        Files.createDirectories(temp.resolve("evidence"));
        Files.writeString(temp.resolve("evidence/snapshot.json"), SNAPSHOT.formatted(fused(11), outcome, fused(21)));
        Files.writeString(temp.resolve("evidence/report.json"), report(reportSnapshotId));
        Path file = temp.resolve("claims.json");
        Files.writeString(file, claims);
        return file;
    }

    private static String check(String rest) {
        return "{\"type\": \"blend\", \"snapshot\": \"evidence/snapshot.json\", \"report\": \"evidence/report.json\", " + rest + "}";
    }

    private static final String TRUE_CLAIMS = """
            {"claims": [
              {"id": "C-801", "basis": "derived", "check": %s},
              {"id": "C-802", "basis": "derived", "check": %s},
              {"id": "C-803", "basis": "derived", "check": %s},
              {"id": "C-804", "basis": "derived", "check": %s},
              {"id": "C-805", "basis": "derived", "check": %s},
              {"id": "C-806", "basis": "derived", "check": %s},
              {"id": "C-807", "basis": "derived", "check": %s}
            ]}
            """.formatted(
            check("\"k\": 60, \"w\": 1, \"questions\": [\"q1\"], \"metric\": \"rank\", \"expected\": 1"),
            check("\"k\": 60, \"w\": 1, \"questions\": [\"q2\"], \"metric\": \"rank\", \"expected\": 3"),
            check("\"k\": 60, \"w\": 0, \"questions\": [\"q1\"], \"metric\": \"rank\", \"expected\": null"),
            check("\"k\": 1, \"w\": 0.5, \"questions\": [\"q1\"], \"metric\": \"rank\", \"expected\": 2"),
            check("\"k\": 60, \"w\": 0, \"questions\": [\"q1\", \"q2\"], \"metric\": \"hitAt5\", \"expected\": 0.5"),
            check("\"k\": 60, \"w\": 0, \"questions\": [\"q1\", \"q2\"], \"metric\": \"mrr\", \"expected\": \"0.166667\""),
            check("\"k\": 1, \"w\": 0.50, \"questions\": [\"q1\", \"q2\"], \"metric\": \"mrr\", \"expected\": 0.416667"));

    private static final String LEAD = "Blending reranked and fused positions in snapshot 9001 at k ";
    private static final String FROM = " (accepted chunks from the evidence report of snapshot 9001), ";

    @Test
    void trueClaimsPassAndEachSentenceStatesTheValuesFound(@TempDir Path temp) throws IOException {
        ClaimsCheck.Result result = ClaimsCheck.evaluate(write(temp, "RERANKED", 9001, TRUE_CLAIMS), temp);
        assertThat(result.problems()).isEmpty();
        assertThat(result.sentences()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                1, LEAD + "60 and w 1" + FROM + "q1 ranks 1st.",
                2, LEAD + "60 and w 1" + FROM + "q2 ranks 3rd.",
                3, LEAD + "60 and w 0" + FROM + "q1 ranks outside the window of 3 results (no chunk holding an accepted phrase).",
                4, LEAD + "1 and w 0.5" + FROM + "q1 ranks 2nd.",
                5, LEAD + "60 and w 0" + FROM + "hit@5 over the 2 questions q1 and q2 is 0.500000 (1 ranked 1 to 5).",
                6, LEAD + "60 and w 0" + FROM + "MRR over the 2 questions q1 and q2 is 0.166667.",
                7, LEAD + "1 and w 0.5" + FROM + "MRR over the 2 questions q1 and q2 is 0.416667."));
    }

    @Test
    void wrongExpectationsFailWithTheValuesFound(@TempDir Path temp) throws IOException {
        Path claims = write(temp, "RERANKED", 9001, """
                {"claims": [
                  {"id": "C-801", "basis": "derived", "check": %s},
                  {"id": "C-802", "basis": "derived", "check": %s},
                  {"id": "C-803", "basis": "derived", "check": %s}
                ]}
                """.formatted(
                check("\"k\": 1, \"w\": 0.5, \"questions\": [\"q1\"], \"metric\": \"rank\", \"expected\": 1"),
                check("\"k\": 60, \"w\": 0, \"questions\": [\"q1\", \"q2\"], \"metric\": \"hitAt5\", \"expected\": 1"),
                check("\"k\": 1, \"w\": 0.5, \"questions\": [\"q1\", \"q2\"], \"metric\": \"mrr\", \"expected\": 0.666667")));
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(
                name + " C-801 [blend] rank at k 1, w 0.5 over 1 question in evidence/snapshot.json: rank expected 1, found 2",
                name + " C-802 [blend] hitAt5 at k 60, w 0 over 2 questions in evidence/snapshot.json: hit@5 expected 1.000000, found 0.500000 (1 of 2; outside the top 5: q1 (rank null))",
                name + " C-803 [blend] mrr at k 1, w 0.5 over 2 questions in evidence/snapshot.json: MRR expected 0.666667, found 0.416667 (q1 rank 2, q2 rank 3)");
    }

    @Test
    void unblendableEvidenceAndMalformedChecksAreRefused(@TempDir Path temp) throws IOException {
        String claimsText = """
                {"claims": [
                  {"id": "C-801", "basis": "derived", "check": %s},
                  {"id": "C-802", "basis": "derived", "check": %s},
                  {"id": "C-803", "basis": "derived", "check": %s},
                  {"id": "C-804", "basis": "observed", "check": %s}
                ]}
                """.formatted(
                check("\"k\": 60, \"w\": 0.5, \"questions\": [\"q1\"], \"metric\": \"rank\", \"expected\": 1"),
                check("\"k\": 60, \"w\": 1.5, \"questions\": [\"q1\"], \"metric\": \"rank\", \"expected\": 1"),
                check("\"k\": 60, \"w\": 0.5, \"questions\": [\"q1\", \"q2\"], \"metric\": \"rank\", \"expected\": 1"),
                check("\"k\": 60, \"w\": 1, \"questions\": [\"q2\"], \"metric\": \"rank\", \"expected\": 3"));
        Path claims = write(temp, "OFF", 9001, claimsText);
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(
                name + " C-801 [blend] rank at k 60, w 0.5 over 1 question in evidence/snapshot.json: question q1 has rerank outcome OFF, not RERANKED, so no reranked positions to blend",
                name + " C-802 [blend]: check.w must be a number from 0 to 1 with at most six decimal places, found 1.5",
                name + " C-803 [blend]: check.metric rank takes exactly one question, found 2",
                name + " C-804 [blend] rank at k 60, w 1 over 1 question in evidence/snapshot.json: basis expected observed, found derived");

        write(temp, "RERANKED", 42, claimsText);
        assertThat(ClaimsCheck.check(claims, temp)).first().isEqualTo(name + " C-801 [blend] rank at k 60, w 0.5 over 1 question in evidence/snapshot.json: "
                + "evidence/report.json is the evidence report of snapshot 42, not of evidence/snapshot.json (snapshot 9001)");
    }

    @Test
    void inputsThatAreNotTheFirstFusedChunksAreRefused(@TempDir Path temp) throws IOException {
        Path claims = write(temp, "RERANKED", 9001, """
                {"claims": [{"id": "C-801", "basis": "derived", "check": %s}]}
                """.formatted(check("\"k\": 60, \"w\": 1, \"questions\": [\"q1\"], \"metric\": \"rank\", \"expected\": 1")));
        ObjectNode snapshot = (ObjectNode) ClaimsCheck.JSON.readTree(Files.readString(temp.resolve("evidence/snapshot.json")));
        ((ObjectNode) snapshot.get("results").get("traces").get(0).get("trace").get("rerank").get("candidates").get(0)).put("chunkId", 99);
        Files.writeString(temp.resolve("evidence/snapshot.json"), ClaimsCheck.JSON.writeValueAsString(snapshot));
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(name + " C-801 [blend] rank at k 60, w 1 over 1 question in evidence/snapshot.json: "
                + "question q1 has rerank inputs that are not the first fused chunks with fused and reranked positions 1 to 4");
    }
}
