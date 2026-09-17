package project.stockrecommendationengine.rag.evaluation.claims;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * The check type {@code questionEquality} (plan {@code 2026-09-17-chunk-size-pool.md}, Milestone 1, remediation round 1; RAG.md, Claims, Check
 * types): whether two snapshots store the same rank per question, the same rank and matched chunk id, or the same rank and a matched chunk with
 * the same filing, chunk index, length, and content md5 in two chunk-hash exports (stores whose chunk ids differ). Small files in a temporary
 * evidence root: every sentence as exact text, a difference the histogram and the metrics cannot see (two questions swapping ranks), a failure
 * naming what was found, and the unreadable cases refused as exact text.
 */
class QuestionEqualityCheckTests {
    private static String snapshot(int id, String q1, String q2, String q3) {
        return """
                {"id": %d, "set_version": "v2", "question_count": 3, "hit_at_1": 0.333333, "hit_at_3": 0.666667, "hit_at_5": 0.666667, "mrr": 0.5, "window_size": 10,
                 "retrieval_strategy": "HYBRID_RRF", "properties": {"set": "s", "rerank": false, "trace": true},
                 "results": {"questions": [
                   {"id": "q1", "kind": "FIGURE", "error": null, "ticker": "T", %s},
                   {"id": "q2", "kind": "NARRATIVE", "error": null, "ticker": "T", %s},
                   {"id": "q3", "kind": "NARRATIVE", "error": null, "ticker": "T", %s}]}}
                """.formatted(id, q1, q2, q3);
    }

    private static String result(Integer rank, Integer chunk) {
        return "\"rank\": " + rank + ", \"matchedChunkId\": " + chunk;
    }

    private static final String HASHES_A = """
            [{"id": 10, "filingId": 3, "chunkIndex": 0, "sectionKey": "ITEM_1", "chars": 3981, "contentMd5": "aaa"},
             {"id": 20, "filingId": 3, "chunkIndex": 1, "sectionKey": "ITEM_1", "chars": 3918, "contentMd5": "bbb"}]
            """;
    /** The store after a rebuild: new ids, the same filing, chunk index, length, and md5 per chunk. */
    private static final String HASHES_A2 = """
            [{"id": 110, "filingId": 3, "chunkIndex": 0, "sectionKey": "ITEM_1", "chars": 3981, "contentMd5": "aaa"},
             {"id": 120, "filingId": 3, "chunkIndex": 1, "sectionKey": "ITEM_1", "chars": 3918, "contentMd5": "bbb"}]
            """;
    /** A rebuild whose second chunk holds other text of the same length. */
    private static final String HASHES_OTHER = """
            [{"id": 110, "filingId": 3, "chunkIndex": 0, "sectionKey": "ITEM_1", "chars": 3981, "contentMd5": "aaa"},
             {"id": 120, "filingId": 3, "chunkIndex": 1, "sectionKey": "ITEM_1", "chars": 3918, "contentMd5": "ccc"}]
            """;

    private static Path write(Path temp, String claims) throws IOException {
        Path m = Files.createDirectories(temp.resolve("m"));
        Files.writeString(m.resolve("a.json"), snapshot(1777, result(1, 10), result(2, 20), result(null, null)));
        Files.writeString(m.resolve("same.json"), snapshot(1615, result(1, 10), result(2, 20), result(null, null)));
        Files.writeString(m.resolve("rebuilt.json"), snapshot(1794, result(1, 110), result(2, 120), result(null, null)));
        // the same rank histogram, hit@k, and MRR as a.json, with q1 and q2 swapped
        Files.writeString(m.resolve("swapped.json"), snapshot(1800, result(2, 20), result(1, 10), result(null, null)));
        Files.writeString(m.resolve("errored.json"), snapshot(1801, result(1, 10), result(2, 20), result(null, null)).replace("\"id\": \"q3\", \"kind\": \"NARRATIVE\", \"error\": null", "\"id\": \"q3\", \"kind\": \"NARRATIVE\", \"error\": \"IllegalStateException: boom\""));
        Files.writeString(m.resolve("half.json"), snapshot(1802, result(1, null), result(2, 20), result(null, null)));
        Files.writeString(m.resolve("fewer.json"), snapshot(1803, result(1, 10), result(2, 20), result(null, null)).replace("\"id\": \"q3\"", "\"id\": \"q9\""));
        Files.writeString(m.resolve("hashes-a.json"), HASHES_A);
        Files.writeString(m.resolve("hashes-a2.json"), HASHES_A2);
        Files.writeString(m.resolve("hashes-other.json"), HASHES_OTHER);
        Files.writeString(m.resolve("not-hashes.json"), "[{\"id\": 10, \"sectionKey\": \"ITEM_1\", \"head\": \"text\"}]");
        Path file = m.resolve("claims.json");
        Files.writeString(file, claims);
        return file;
    }

    private static String check(String id, String candidate, String compare, String extra, String expected) {
        return "{\"id\": \"" + id + "\", \"basis\": \"derived\", \"check\": {\"type\": \"questionEquality\", \"reference\": \"a.json\", \"candidate\": \"" + candidate
                + "\", \"compare\": \"" + compare + "\"" + extra + ", \"expected\": " + expected + "}}";
    }

    private static final String EXPORTS = ", \"referenceChunks\": \"hashes-a.json\", \"candidateChunks\": \"hashes-a2.json\"";

    @Test
    void equalSnapshotsRenderOneSentencePerComparison(@TempDir Path temp) throws IOException {
        Path claims = write(temp, "{\"labels\": {\"a.json\": \"the default reference\", \"hashes-a2.json\": \"the hashes after the rollback\"}, \"claims\": ["
                + check("C-001", "same.json", "rank", "", "[]") + ","
                + check("C-002", "same.json", "rankAndMatchedChunk", "", "[]") + ","
                + check("C-003", "rebuilt.json", "rankAndMatchedContent", EXPORTS, "[]") + ","
                + check("C-004", "rebuilt.json", "rank", "", "[]") + "]}");
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        assertThat(result.problems()).isEmpty();
        assertThat(result.sentences().get(1)).isEqualTo("Between the default reference (snapshot 1777) and snapshot 1615, the stored rank is identical for each of the 3 questions.");
        assertThat(result.sentences().get(2)).isEqualTo("Between the default reference (snapshot 1777) and snapshot 1615, the stored rank and matched chunk id are identical for each of the 3 questions.");
        assertThat(result.sentences().get(3)).isEqualTo("Between the default reference (snapshot 1777) and snapshot 1794, the stored rank and the matched chunk's filing, chunk index, length, and "
                + "content md5 (from the chunk-hash export hashes-a.json and the hashes after the rollback (the chunk-hash export hashes-a2.json)) are identical for each of the 3 questions.");
        assertThat(result.sentences().get(4)).isEqualTo("Between the default reference (snapshot 1777) and snapshot 1794, the stored rank is identical for each of the 3 questions.");
    }

    @Test
    void differencesAreNamedPerQuestionAndAClaimOfEqualityFails(@TempDir Path temp) throws IOException {
        Path claims = write(temp, "{\"claims\": ["
                + check("C-001", "swapped.json", "rank", "", "[]") + ","
                + check("C-002", "swapped.json", "rank", "", "[\"q1\", \"q2\"]") + ","
                + check("C-003", "rebuilt.json", "rankAndMatchedChunk", "", "[\"q1\", \"q2\"]") + ","
                + check("C-004", "rebuilt.json", "rankAndMatchedContent", EXPORTS.replace("hashes-a2.json", "hashes-other.json"), "[]") + ","
                + check("C-005", "same.json", "rank", "", "[]").replace("\"derived\"", "\"observed\"") + "]}");
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        String name = ClaimsCheck.display(claims);
        assertThat(result.problems()).hasSize(3);
        assertThat(result.problems().get(0)).isEqualTo(name + " C-001 [questionEquality] rank of swapped.json against a.json: questions that differ expected none, found "
                + "q1 (rank 1 in snapshot 1777, rank 2 in snapshot 1800), q2 (rank 2 in snapshot 1777, rank 1 in snapshot 1800)");
        assertThat(result.problems().get(1)).isEqualTo(name + " C-004 [questionEquality] rankAndMatchedContent of rebuilt.json against a.json: questions that differ expected none, found "
                + "q2 (matched chunk 20 is filing 3 chunk 1, 3918 characters, md5 bbb in snapshot 1777, matched chunk 120 is filing 3 chunk 1, 3918 characters, md5 ccc in snapshot 1794)");
        assertThat(result.problems().get(2)).contains("C-005").contains("derived");
        assertThat(result.sentences().get(2)).isEqualTo("Between snapshot 1777 and snapshot 1800, the stored rank differs for 2 of the 3 questions: "
                + "q1 (rank 1 in snapshot 1777, rank 2 in snapshot 1800), q2 (rank 2 in snapshot 1777, rank 1 in snapshot 1800).");
        assertThat(result.sentences().get(3)).isEqualTo("Between snapshot 1777 and snapshot 1794, the stored rank and matched chunk id differ for 2 of the 3 questions: "
                + "q1 (matched chunk 10 in snapshot 1777, 110 in snapshot 1794), q2 (matched chunk 20 in snapshot 1777, 120 in snapshot 1794).");
    }

    @Test
    void malformedChecksAndUnreadableFilesAreRefused(@TempDir Path temp) throws IOException {
        Path claims = write(temp, "{\"claims\": ["
                + check("C-001", "same.json", "matchedChunk", "", "[]") + ","
                + check("C-002", "same.json", "rank", EXPORTS, "[]") + ","
                + check("C-003", "rebuilt.json", "rankAndMatchedContent", ", \"referenceChunks\": \"hashes-a.json\"", "[]") + ","
                + check("C-004", "same.json", "rank", "", "[\"q1\", \"q1\"]") + ","
                + check("C-005", "same.json", "rank", "", "[\"q7\"]") + ","
                + check("C-006", "a.json", "rank", "", "[]") + ","
                + check("C-007", "fewer.json", "rank", "", "[]") + ","
                + check("C-008", "errored.json", "rank", "", "[]") + ","
                + check("C-009", "half.json", "rank", "", "[]") + ","
                + check("C-010", "rebuilt.json", "rankAndMatchedContent", EXPORTS.replace("hashes-a2.json", "not-hashes.json"), "[]") + ","
                + check("C-011", "rebuilt.json", "rankAndMatchedContent", EXPORTS.replace("hashes-a2.json", "hashes-a.json"), "[]") + ","
                + check("C-012", "same.json", "rank", ", \"questions\": [\"q1\"]", "[]") + "]}");
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        assertThat(result.problems()).hasSize(12);
        assertThat(result.problems().get(0)).contains("C-001").contains("check.compare must be rank, rankAndMatchedChunk, or rankAndMatchedContent, found \"matchedChunk\"");
        assertThat(result.problems().get(1)).contains("C-002").contains("check.referenceChunks and check.candidateChunks are required with compare rankAndMatchedContent and refused otherwise");
        assertThat(result.problems().get(2)).contains("C-003").contains("check.referenceChunks and check.candidateChunks are required with compare rankAndMatchedContent and refused otherwise");
        assertThat(result.problems().get(3)).contains("C-004").contains("check.expected must list the distinct question ids that differ (an empty list for none)");
        assertThat(result.problems().get(4)).contains("C-005").contains("check.expected lists q7, which neither snapshot has");
        assertThat(result.problems().get(5)).contains("C-006").contains("check.reference and check.candidate name the same file a.json");
        assertThat(result.problems().get(6)).contains("C-007").contains("a.json and fewer.json list different questions");
        assertThat(result.problems().get(7)).contains("C-008").contains("question q3 records a retrieval error in errored.json: IllegalStateException: boom");
        assertThat(result.problems().get(8)).contains("C-009").contains("question q1 records rank 1 and matched chunk null in half.json");
        assertThat(result.problems().get(9)).contains("C-010").contains("not-hashes.json holds a chunk without an integer id, filingId, chunkIndex, and chars and a contentMd5");
        assertThat(result.problems().get(10)).contains("C-011").contains("matched chunk 110 of q1 is not in the chunk-hash export hashes-a.json");
        assertThat(result.problems().get(11)).contains("C-012").contains("questions");
    }
}
