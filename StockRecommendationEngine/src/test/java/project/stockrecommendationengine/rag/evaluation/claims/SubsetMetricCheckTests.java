package project.stockrecommendationengine.rag.evaluation.claims;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * The check type {@code subsetMetric} (plan {@code 2026-09-17-chunk-size-pool.md}, Milestone 1; RAG.md, Claims, Check types): hit@1, hit@3,
 * hit@5, and MRR over a listed subset of a snapshot's questions, computed from their stored ranks as RetrievalEvaluationService computes the
 * aggregates. Small files in a temporary evidence root: every sentence as exact text, hand-computed values, another value failing naming
 * what was found, and the unreadable cases refused as exact text.
 */
class SubsetMetricCheckTests {
    /** A row-export-shaped snapshot: q1 at rank 1, q2 at rank 3, q3 outside the window, q4 errored, q5 at rank 6. */
    private static final String SNAPSHOT = """
            {"id": 1777, "set_version": "v2", "question_count": 5, "hit_at_1": 0.2, "hit_at_3": 0.4, "hit_at_5": 0.4, "mrr": 0.3, "window_size": 10,
             "retrieval_strategy": "HYBRID_RRF", "properties": {"set": "s", "rerank": false, "trace": true},
             "results": {"questions": [
               {"id": "q1", "kind": "FIGURE", "rank": 1, "error": null, "ticker": "T", "matchedChunkId": 10},
               {"id": "q2", "kind": "NARRATIVE", "rank": 3, "error": null, "ticker": "T", "matchedChunkId": 30},
               {"id": "q3", "kind": "NARRATIVE", "rank": null, "error": null, "ticker": "T", "matchedChunkId": null},
               {"id": "q4", "kind": "FIGURE", "rank": null, "error": "IllegalStateException: boom", "ticker": "T", "matchedChunkId": null},
               {"id": "q5", "kind": "FIGURE", "rank": 6, "error": null, "ticker": "T", "matchedChunkId": 60}]}}
            """;

    private static Path write(Path temp, String claims) throws IOException {
        Files.createDirectories(temp.resolve("m"));
        Files.writeString(temp.resolve("m/snapshot.json"), SNAPSHOT);
        Path file = temp.resolve("m/claims.json");
        Files.writeString(file, claims);
        return file;
    }

    @Test
    void theFourMetricsAreComputedFromStoredRanksOverTheListedQuestionsOnly(@TempDir Path temp) throws IOException {
        Path claims = write(temp, """
                {"labels": {"snapshot.json": "the default reference"},
                 "claims": [
                  {"id": "C-001", "basis": "derived", "check": {"type": "subsetMetric", "snapshot": "snapshot.json", "questions": ["q1", "q2", "q3"], "metric": "hitAt5", "expected": "0.666667"}},
                  {"id": "C-002", "basis": "derived", "check": {"type": "subsetMetric", "snapshot": "snapshot.json", "questions": ["q1", "q2", "q3"], "metric": "mrr", "expected": 0.444444}},
                  {"id": "C-003", "basis": "derived", "check": {"type": "subsetMetric", "snapshot": "snapshot.json", "questions": ["q1", "q2"], "metric": "hitAt1", "expected": "0.500000"}},
                  {"id": "C-004", "basis": "derived", "check": {"type": "subsetMetric", "snapshot": "snapshot.json", "questions": ["q2"], "metric": "hitAt3", "expected": 1}},
                  {"id": "C-005", "basis": "derived", "check": {"type": "subsetMetric", "snapshot": "snapshot.json", "questions": ["q4", "q5"], "metric": "hitAt5", "expected": 0}},
                  {"id": "C-006", "basis": "derived", "check": {"type": "subsetMetric", "snapshot": "snapshot.json", "questions": ["q5", "q4", "q1"], "metric": "mrr", "expected": "0.388889"}}
                ]}
                """);
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        assertThat(result.problems()).isEmpty();
        assertThat(result.sentences().get(1)).isEqualTo("From the stored ranks of the default reference (snapshot 1777), hit@5 over the 3 questions q1, q2, and q3 is 0.666667 (2 ranked 1 to 5).");
        assertThat(result.sentences().get(2)).isEqualTo("From the stored ranks of the default reference (snapshot 1777), MRR over the 3 questions q1, q2, and q3 is 0.444444.");
        assertThat(result.sentences().get(3)).isEqualTo("From the stored ranks of the default reference (snapshot 1777), hit@1 over the 2 questions q1 and q2 is 0.500000 (1 ranked 1st).");
        assertThat(result.sentences().get(4)).isEqualTo("From the stored ranks of the default reference (snapshot 1777), hit@3 over the 1 question q2 is 1.000000 (1 ranked 1 to 3).");
        assertThat(result.sentences().get(5)).isEqualTo("From the stored ranks of the default reference (snapshot 1777), hit@5 over the 2 questions q4 and q5 is 0.000000 (0 ranked 1 to 5).");
        assertThat(result.sentences().get(6)).isEqualTo("From the stored ranks of the default reference (snapshot 1777), MRR over the 3 questions q5, q4, and q1 is 0.388889.");
    }

    @Test
    void anotherValueFailsNamingTheValueFoundAndTheSentenceStillStatesTheEvidence(@TempDir Path temp) throws IOException {
        Path claims = write(temp, """
                {"claims": [
                  {"id": "C-001", "basis": "derived", "check": {"type": "subsetMetric", "snapshot": "snapshot.json", "questions": ["q1", "q3", "q5"], "metric": "hitAt5", "expected": 1}},
                  {"id": "C-002", "basis": "derived", "check": {"type": "subsetMetric", "snapshot": "snapshot.json", "questions": ["q1", "q3"], "metric": "mrr", "expected": "0.25"}},
                  {"id": "C-003", "basis": "observed", "check": {"type": "subsetMetric", "snapshot": "snapshot.json", "questions": ["q1"], "metric": "hitAt5", "expected": 1}}
                ]}
                """);
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        String name = ClaimsCheck.display(claims);
        assertThat(result.problems()).hasSize(3);
        assertThat(result.problems().get(0)).isEqualTo(name + " C-001 [subsetMetric] hitAt5 over 3 questions of snapshot.json: hit@5 expected 1.000000, found 0.333333 (1 of 3; outside the top 5: q3 (rank null), q5 (rank 6))");
        assertThat(result.problems().get(1)).isEqualTo(name + " C-002 [subsetMetric] mrr over 2 questions of snapshot.json: MRR expected 0.250000, found 0.500000 (q1 rank 1, q3 rank null)");
        assertThat(result.problems().get(2)).contains("C-003").contains("derived");
        assertThat(result.sentences().get(1)).isEqualTo("From the stored ranks of snapshot 1777, hit@5 over the 3 questions q1, q3, and q5 is 0.333333 (1 ranked 1 to 5).");
    }

    @Test
    void malformedChecksAreRefused(@TempDir Path temp) throws IOException {
        Path claims = write(temp, """
                {"claims": [
                  {"id": "C-001", "basis": "derived", "check": {"type": "subsetMetric", "snapshot": "snapshot.json", "questions": ["q1", "q1"], "metric": "hitAt5", "expected": 1}},
                  {"id": "C-002", "basis": "derived", "check": {"type": "subsetMetric", "snapshot": "snapshot.json", "questions": [], "metric": "hitAt5", "expected": 1}},
                  {"id": "C-003", "basis": "derived", "check": {"type": "subsetMetric", "snapshot": "snapshot.json", "questions": ["q1"], "metric": "hitAt10", "expected": 1}},
                  {"id": "C-004", "basis": "derived", "check": {"type": "subsetMetric", "snapshot": "snapshot.json", "questions": ["q1"], "metric": "mrr", "expected": 0.1234567}},
                  {"id": "C-005", "basis": "derived", "check": {"type": "subsetMetric", "snapshot": "snapshot.json", "questions": ["q9"], "metric": "mrr", "expected": 0}},
                  {"id": "C-006", "basis": "derived", "check": {"type": "subsetMetric", "snapshot": "snapshot.json", "questions": ["q1"], "metric": "mrr", "expected": 1, "slice": "tuning"}}
                ]}
                """);
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        assertThat(result.problems()).hasSize(6);
        assertThat(result.problems().get(0)).contains("C-001").contains("check.questions must list distinct non-blank question ids");
        assertThat(result.problems().get(1)).contains("C-002").contains("check.questions must list at least one question id");
        assertThat(result.problems().get(2)).contains("C-003").contains("check.metric must be hitAt1, hitAt3, hitAt5, or mrr, found \"hitAt10\"");
        assertThat(result.problems().get(3)).contains("C-004").contains("check.expected must be a number with at most six decimal places");
        assertThat(result.problems().get(4)).contains("C-005").contains("has no question q9");
        assertThat(result.problems().get(5)).contains("C-006").contains("slice");
    }
}
