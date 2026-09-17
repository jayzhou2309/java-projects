package project.stockrecommendationengine.rag.evaluation.claims;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * The check types added for the chunk-size measurement (plan {@code 2026-09-17-chunk-size.md}, Milestone 3; RAG.md, Claims, Check types):
 * {@code matchedChunk} (a question's stored matched chunk, observed), {@code rankHistogram} (how many questions at each rank and outside the
 * window, derived), {@code rankedAbove} (the chunks a trace returned above the matched chunk, joined to a chunk export for section and text,
 * derived), and {@code fileValue} (one value of a committed JSON file by path, observed), and the {@code coupled} properties of an experiment
 * file (recorded properties that change together with the factor), over small files written to a temporary evidence root: every sentence as
 * exact text, another value failing naming what was found, and the unreadable cases refused as exact text.
 */
class MeasurementChecksTests {
    /** A row-export-shaped snapshot: q1 at rank 1, q2 at rank 3 (chunk 30 behind 10 and 20), q3 outside the window, q4 errored. */
    private static final String SNAPSHOT_A = """
            {"id": 1611, "set_version": "v2", "question_count": 4, "hit_at_1": 0.25, "hit_at_3": 0.5, "hit_at_5": 0.5, "mrr": 0.333333, "window_size": 3,
             "retrieval_strategy": "HYBRID_RRF",
             "properties": {"set": "s", "rerank": false, "chunkMaxChars": 4000, "chunkOverlapChars": 500, "storeVersions": ["v-4000-500"], "trace": true},
             "results": {"questions": [
               {"id": "q1", "kind": "FIGURE", "rank": 1, "error": null, "ticker": "T", "matchedChunkId": 10, "retrievalStrategy": "HYBRID_RRF"},
               {"id": "q2", "kind": "NARRATIVE", "rank": 3, "error": null, "ticker": "T", "matchedChunkId": 30, "retrievalStrategy": "HYBRID_RRF"},
               {"id": "q3", "kind": "NARRATIVE", "rank": null, "error": null, "ticker": "T", "matchedChunkId": null, "retrievalStrategy": "HYBRID_RRF"},
               {"id": "q4", "kind": "FIGURE", "rank": null, "error": "IllegalStateException: boom", "ticker": "T", "matchedChunkId": null, "retrievalStrategy": null}],
              "traces": [
               {"id": "q1", "trace": {"returnedChunkIds": [10, 20, 30]}},
               {"id": "q2", "trace": {"returnedChunkIds": [10, 20, 30]}},
               {"id": "q3", "trace": {"returnedChunkIds": [20, 40]}},
               {"id": "q4", "trace": null}]}}
            """;
    /** An API-shaped snapshot at the other size, differing from A in the factor, its coupled properties, and nothing else. */
    private static final String SNAPSHOT_B = """
            {"id": 1620, "setVersion": "v2", "questionCount": 4, "hitAt1": 0.5, "hitAt3": 0.5, "hitAt5": 0.75, "mrr": 0.55, "window": 3,
             "retrievalStrategy": "HYBRID_RRF",
             "properties": {"set": "s", "rerank": false, "chunkMaxChars": 1000, "chunkOverlapChars": 125, "storeVersions": ["v-1000-125"], "trace": true},
             "results": [
               {"id": "q1", "kind": "FIGURE", "rank": 1, "error": null, "ticker": "T", "matchedChunkId": 110, "retrievalStrategy": "HYBRID_RRF"},
               {"id": "q2", "kind": "NARRATIVE", "rank": 1, "error": null, "ticker": "T", "matchedChunkId": 130, "retrievalStrategy": "HYBRID_RRF"},
               {"id": "q3", "kind": "NARRATIVE", "rank": 2, "error": null, "ticker": "T", "matchedChunkId": 140, "retrievalStrategy": "HYBRID_RRF"},
               {"id": "q4", "kind": "FIGURE", "rank": null, "error": null, "ticker": "T", "matchedChunkId": null, "retrievalStrategy": "HYBRID_RRF"}],
             "traces": [
               {"id": "q1", "trace": {"returnedChunkIds": [110, 120, 130]}},
               {"id": "q2", "trace": {"returnedChunkIds": [130, 110, 120]}},
               {"id": "q3", "trace": {"returnedChunkIds": [120, 140, 110]}},
               {"id": "q4", "trace": {"returnedChunkIds": [110, 120, 130]}}]}
            """;
    private static final String CHUNKS_A = """
            [{"id": 10, "filingId": 3, "sectionKey": "ITEM_7", "head": "Net sales rose"}, {"id": 20, "filingId": 3, "sectionKey": "ITEM_8", "head": "Table \\"A\\" follows"},
             {"id": 30, "filingId": 3, "sectionKey": "ITEM_7", "head": "Gross margin"}]
            """;
    private static final String CHUNKS_B = """
            [{"id": 110, "sectionKey": "ITEM_7", "head": "Net sales rose"}, {"id": 120, "sectionKey": "ITEM_8", "head": "Table follows"},
             {"id": 130, "sectionKey": "ITEM_7", "head": "Gross margin"}, {"id": 140, "sectionKey": "ITEM_1A", "head": "Risks"}]
            """;
    private static final String LATENCY = """
            {"snapshotId": 1611, "retrievalMs": {"count": 4, "median": 312.5, "p95": 610, "max": 702}, "rerankMs": null, "wallSeconds": 15.21, "questions": [{"id": "q1", "retrievalMs": 300}]}
            """;

    private static final String REPORT = """
            {"snapshotId": 1620, "questions": [
              {"id": "q1", "phrases": [{"phrase": "net sales rose", "heldByStoredChunk": {"value": true, "basis": "observed", "source": "sec_filing_chunks"}},
                                       {"phrase": "table follows", "heldByStoredChunk": {"value": true, "basis": "observed", "source": "sec_filing_chunks"}}]},
              {"id": "q2", "phrases": [{"phrase": "gross margin", "heldByStoredChunk": {"value": false, "basis": "observed", "source": "sec_filing_chunks"}}]}]}
            """;
    private static final String REPORT_UNKNOWN = """
            {"snapshotId": 1621, "questions": [
              {"id": "q1", "phrases": [{"phrase": "net sales rose", "heldByStoredChunk": {"value": null, "basis": "unknown", "reason": "set not found"}}]}]}
            """;

    private static Path write(Path temp, String claims) throws IOException {
        Files.createDirectories(temp.resolve("m"));
        Files.writeString(temp.resolve("m/evidence-1620.json"), REPORT);
        Files.writeString(temp.resolve("m/evidence-1621.json"), REPORT_UNKNOWN);
        Files.writeString(temp.resolve("m/snapshot-a.json"), SNAPSHOT_A);
        Files.writeString(temp.resolve("m/snapshot-b.json"), SNAPSHOT_B);
        Files.writeString(temp.resolve("m/chunks-a.json"), CHUNKS_A);
        Files.writeString(temp.resolve("m/chunks-b.json"), CHUNKS_B);
        Files.writeString(temp.resolve("m/latency-1611.json"), LATENCY);
        Files.writeString(temp.resolve("m/experiment.json"), """
                {"description": "chunk size", "experiment": {"factor": "chunkMaxChars", "settings": [4000, 1000], "coupled": ["chunkOverlapChars", "storeVersions"],
                 "snapshots": ["snapshot-a.json", "snapshot-b.json"]}}
                """);
        Files.writeString(temp.resolve("m/experiment-uncoupled.json"), """
                {"experiment": {"factor": "chunkMaxChars", "settings": [4000, 1000], "snapshots": ["snapshot-a.json", "snapshot-b.json"]}}
                """);
        Files.writeString(temp.resolve("m/experiment-bad-coupled.json"), """
                {"experiment": {"factor": "chunkMaxChars", "settings": [4000, 1000], "coupled": ["chunkOverlapChars", "storeVersions", "set", "missing"],
                 "snapshots": ["snapshot-a.json", "snapshot-b.json"]}}
                """);
        Files.writeString(temp.resolve("m/experiment-factor-coupled.json"), """
                {"experiment": {"factor": "chunkMaxChars", "settings": [4000, 1000], "coupled": ["chunkMaxChars", "chunkOverlapChars", "storeVersions"],
                 "snapshots": ["snapshot-a.json", "snapshot-b.json"]}}
                """);
        Path file = temp.resolve("m/claims.json");
        Files.writeString(file, claims);
        return file;
    }

    @Test
    void matchedChunkRankHistogramRankedAboveAndFileValueRenderTheirSentences(@TempDir Path temp) throws IOException {
        Path claims = write(temp, """
                {"labels": {"snapshot-a.json": "the baseline", "chunks-a.json": "the stored-size export", "latency-1611.json": "the baseline latency"},
                 "claims": [
                  {"id": "C-001", "basis": "observed", "check": {"type": "matchedChunk", "snapshot": "snapshot-a.json", "question": "q2", "expected": 30}},
                  {"id": "C-002", "basis": "observed", "check": {"type": "matchedChunk", "snapshot": "snapshot-a.json", "question": "q3", "expected": null}},
                  {"id": "C-003", "basis": "observed", "check": {"type": "matchedChunk", "snapshot": "snapshot-a.json", "question": "q4", "expected": null}},
                  {"id": "C-004", "basis": "derived", "check": {"type": "rankHistogram", "snapshot": "snapshot-a.json", "expected": {"1": 1, "3": 1, "notInWindow": 2}}},
                  {"id": "C-005", "basis": "derived", "check": {"type": "rankHistogram", "snapshot": "snapshot-b.json", "expected": {"1": 2, "2": 1, "notInWindow": 1}}},
                  {"id": "C-006", "basis": "derived", "check": {"type": "rankedAbove", "snapshot": "snapshot-a.json", "question": "q2", "chunks": "chunks-a.json", "expected": [10, 20]}},
                  {"id": "C-007", "basis": "derived", "check": {"type": "rankedAbove", "snapshot": "snapshot-a.json", "question": "q1", "chunks": "chunks-a.json", "expected": []}},
                  {"id": "C-008", "basis": "derived", "check": {"type": "rankedAbove", "snapshot": "snapshot-a.json", "question": "q3", "chunks": "chunks-a.json", "expected": [20, 40]}},
                  {"id": "C-009", "basis": "derived", "check": {"type": "rankedAbove", "snapshot": "snapshot-b.json", "question": "q3", "chunks": "chunks-b.json", "expected": [120]}},
                  {"id": "C-010", "basis": "observed", "check": {"type": "fileValue", "file": "latency-1611.json", "path": "retrievalMs.median", "expected": 312.5}},
                  {"id": "C-011", "basis": "observed", "check": {"type": "fileValue", "file": "latency-1611.json", "path": "questions[id=q1].retrievalMs", "expected": 300}},
                  {"id": "C-012", "basis": "observed", "check": {"type": "fileValue", "file": "latency-1611.json", "path": "rerankMs", "expected": null}},
                  {"id": "C-013", "basis": "derived", "check": {"type": "rankHistogram", "snapshot": "snapshot-b.json", "expected": {"1": 2, "2": 1, "3": 0, "notInWindow": 1}}},
                  {"id": "C-014", "basis": "observed", "check": {"type": "fileValue", "file": "chunks-a.json", "path": "[id=20].sectionKey", "expected": "ITEM_8"}}
                ]}
                """);
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        String name = ClaimsCheck.display(claims);
        assertThat(result.problems()).containsExactly(
                name + " C-008 [rankedAbove] chunks returned above the matched chunk of q3 in snapshot-a.json: returned chunk 40 is not in the chunk export chunks-a.json");
        assertThat(result.sentences().get(1)).isEqualTo("The baseline (snapshot 1611) records chunk 30 as the matched chunk of q2.");
        assertThat(result.sentences().get(2)).isEqualTo("The baseline (snapshot 1611) records no matched chunk for q3 (no matching chunk in the window).");
        assertThat(result.sentences().get(3)).isEqualTo("The baseline (snapshot 1611) records a retrieval error for q4 and no matched chunk.");
        assertThat(result.sentences().get(4)).isEqualTo("In the baseline (snapshot 1611), of the 4 questions 1 rank 1st, 1 rank 3rd, and 2 rank outside the window of 3 results (no matching chunk, 1 with a retrieval error).");
        assertThat(result.sentences().get(5)).isEqualTo("In snapshot 1620, of the 4 questions 2 rank 1st, 1 rank 2nd, and 1 rank outside the window of 3 results (no matching chunk).");
        assertThat(result.sentences().get(6)).isEqualTo("In the baseline (snapshot 1611), the 2 chunks returned above q2's matched chunk 30 (rank 3) are chunk 10 (ITEM_7, \"Net sales rose\") "
                + "and chunk 20 (ITEM_8, \"Table \"A\" follows\"); sections and text from the stored-size export (the chunk export chunks-a.json).");
        assertThat(result.sentences().get(7)).isEqualTo("In the baseline (snapshot 1611), no chunk is returned above q1's matched chunk 10 (rank 1).");
        assertThat(result.sentences().get(8)).isNull();
        assertThat(result.sentences().get(9)).isEqualTo("In snapshot 1620, the 1 chunk returned above q3's matched chunk 140 (rank 2) is chunk 120 (ITEM_8, \"Table follows\"); "
                + "sections and text from the chunk export chunks-b.json.");
        assertThat(result.sentences().get(10)).isEqualTo("In the baseline latency (the file latency-1611.json), retrievalMs.median is 312.5.");
        assertThat(result.sentences().get(11)).isEqualTo("In the baseline latency (the file latency-1611.json), questions[id=q1].retrievalMs is 300.");
        assertThat(result.sentences().get(12)).isEqualTo("In the baseline latency (the file latency-1611.json), rerankMs is null.");
        assertThat(result.sentences().get(13)).isEqualTo(result.sentences().get(5));
        assertThat(result.sentences().get(14)).isEqualTo("In the stored-size export (the file chunks-a.json), [id=20].sectionKey is ITEM_8.");
    }

    @Test
    void aQuestionWithNoMatchedChunkListsItsWholeWindow(@TempDir Path temp) throws IOException {
        Path claims = write(temp, """
                {"claims": [
                  {"id": "C-001", "basis": "derived", "check": {"type": "rankedAbove", "snapshot": "snapshot-b.json", "question": "q4", "chunks": "chunks-b.json", "expected": [110, 120, 130]}}
                ]}
                """);
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        assertThat(result.problems()).isEmpty();
        assertThat(result.sentences().get(1)).isEqualTo("In snapshot 1620, the 3 chunks returned for q4, none of them a matched chunk, are chunk 110 (ITEM_7, \"Net sales rose\"), "
                + "chunk 120 (ITEM_8, \"Table follows\"), and chunk 130 (ITEM_7, \"Gross margin\"); sections and text from the chunk export chunks-b.json.");
    }

    @Test
    void anotherValueFailsNamingWhatWasFoundAndUnreadableCasesAreRefused(@TempDir Path temp) throws IOException {
        Path claims = write(temp, """
                {"claims": [
                  {"id": "C-001", "basis": "observed", "check": {"type": "matchedChunk", "snapshot": "snapshot-a.json", "question": "q2", "expected": 20}},
                  {"id": "C-002", "basis": "derived", "check": {"type": "matchedChunk", "snapshot": "snapshot-a.json", "question": "q2", "expected": 30}},
                  {"id": "C-003", "basis": "observed", "check": {"type": "matchedChunk", "snapshot": "snapshot-a.json", "question": "q2", "expected": "30"}},
                  {"id": "C-004", "basis": "derived", "check": {"type": "rankHistogram", "snapshot": "snapshot-a.json", "expected": {"1": 2, "notInWindow": 2}}},
                  {"id": "C-005", "basis": "derived", "check": {"type": "rankHistogram", "snapshot": "snapshot-a.json", "expected": {"first": 1}}},
                  {"id": "C-006", "basis": "derived", "check": {"type": "rankedAbove", "snapshot": "snapshot-a.json", "question": "q2", "chunks": "chunks-a.json", "expected": [20, 10]}},
                  {"id": "C-007", "basis": "derived", "check": {"type": "rankedAbove", "snapshot": "snapshot-a.json", "question": "q4", "chunks": "chunks-a.json", "expected": []}},
                  {"id": "C-008", "basis": "derived", "check": {"type": "rankedAbove", "snapshot": "snapshot-a.json", "question": "q2", "chunks": "latency-1611.json", "expected": [10, 20]}},
                  {"id": "C-009", "basis": "observed", "check": {"type": "fileValue", "file": "latency-1611.json", "path": "retrievalMs.median", "expected": 300}},
                  {"id": "C-010", "basis": "observed", "check": {"type": "fileValue", "file": "latency-1611.json", "path": "retrievalMs.mean", "expected": 300}},
                  {"id": "C-011", "basis": "observed", "check": {"type": "fileValue", "file": "latency-1611.json", "path": "retrievalMs.p95", "expected": "610"}},
                  {"id": "C-012", "basis": "observed", "check": {"type": "rankedAbove", "snapshot": "snapshot-a.json", "question": "q2", "chunks": "chunks-a.json", "expected": [10, 20], "report": "x"}}
                ]}
                """);
        String name = ClaimsCheck.display(claims);
        assertThat(ClaimsCheck.check(claims, temp)).containsExactly(
                name + " C-001 [matchedChunk] matched chunk of q2 in snapshot-a.json: matchedChunkId expected 20, found 30",
                name + " C-002 [matchedChunk] matched chunk of q2 in snapshot-a.json: basis expected derived, found observed",
                name + " C-003 [matchedChunk] matched chunk of q2 in snapshot-a.json: check.expected must be a chunk id (integer) or null, found \"30\"",
                name + " C-004 [rankHistogram] rank histogram of snapshot-a.json: rank 1 expected 2, found 1; rank 3 expected 0, found 1",
                name + " C-005 [rankHistogram] rank histogram of snapshot-a.json: check.expected key \"first\" is neither a rank nor notInWindow",
                name + " C-006 [rankedAbove] chunks returned above the matched chunk of q2 in snapshot-a.json: chunks above expected [20, 10], found [10, 20]",
                name + " C-007 [rankedAbove] chunks returned above the matched chunk of q4 in snapshot-a.json: question q4 records a retrieval error: IllegalStateException: boom",
                name + " C-008 [rankedAbove] chunks returned above the matched chunk of q2 in snapshot-a.json: latency-1611.json is not a chunk export (a JSON array of chunks)",
                name + " C-009 [fileValue] retrievalMs.median in latency-1611.json: value expected 300, found 312.5",
                name + " C-010 [fileValue] retrievalMs.mean in latency-1611.json: path retrievalMs.mean names nothing in latency-1611.json",
                name + " C-011 [fileValue] retrievalMs.p95 in latency-1611.json: value expected the string \"610\", found the number 610",
                name + " C-012 [rankedAbove]: unknown check key \"report\"");
    }

    @Test
    void heldPhrasesCountsObservedBooleansAndNamesThePhrasesNotHeld(@TempDir Path temp) throws IOException {
        Path claims = write(temp, """
                {"labels": {"evidence-1620.json": "the rebuilt store"},
                 "claims": [
                  {"id": "C-001", "basis": "derived", "check": {"type": "heldPhrases", "report": "evidence-1620.json", "expected": {"phrases": 3, "held": 2}}},
                  {"id": "C-002", "basis": "derived", "check": {"type": "heldPhrases", "report": "evidence-1620.json", "expected": {"phrases": 3, "held": 3}}},
                  {"id": "C-003", "basis": "derived", "check": {"type": "heldPhrases", "report": "evidence-1621.json", "expected": {"phrases": 1, "held": 1}}},
                  {"id": "C-004", "basis": "observed", "check": {"type": "heldPhrases", "report": "evidence-1620.json", "expected": {"phrases": 3}}}
                ]}
                """);
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        String name = ClaimsCheck.display(claims);
        assertThat(result.sentences().get(1)).isEqualTo("In the rebuilt store (the evidence report of snapshot 1620), 2 of the 3 accepted phrases are held by a stored chunk; not held: q2 \"gross margin\".");
        assertThat(result.problems()).containsExactly(
                name + " C-002 [heldPhrases] accepted phrases held by a stored chunk in evidence-1620.json: held expected 3, found 2 (not held: q2 \"gross margin\")",
                name + " C-003 [heldPhrases] accepted phrases held by a stored chunk in evidence-1621.json: question q1 phrase \"net sales rose\": heldByStoredChunk is not an observed boolean "
                        + "({\"value\":null,\"basis\":\"unknown\",\"reason\":\"set not found\"})",
                name + " C-004 [heldPhrases] accepted phrases held by a stored chunk in evidence-1620.json: check.expected.held must be a non-negative count, found null");
    }

    @Test
    void coupledPropertiesOfAnExperimentMustDifferAndAreNamedInTheRenderedSentence(@TempDir Path temp) throws IOException {
        Path claims = write(temp, """
                {"claims": [
                  {"id": "C-001", "basis": "experiment", "text": "The rank of q2 changed between the sizes.", "experiment": {"file": "experiment.json", "factor": "chunkMaxChars"},
                   "check": {"type": "rank", "snapshot": "snapshot-b.json", "question": "q2", "expected": 1}},
                  {"id": "C-002", "basis": "experiment", "text": "Without the coupled list the pair is refused.", "experiment": {"file": "experiment-uncoupled.json", "factor": "chunkMaxChars"},
                   "check": {"type": "rank", "snapshot": "snapshot-b.json", "question": "q2", "expected": 1}},
                  {"id": "C-003", "basis": "experiment", "text": "A coupled list naming an equal property or a missing one is refused.", "experiment": {"file": "experiment-bad-coupled.json", "factor": "chunkMaxChars"},
                   "check": {"type": "rank", "snapshot": "snapshot-b.json", "question": "q2", "expected": 1}},
                  {"id": "C-004", "basis": "experiment", "text": "A coupled list naming the factor is refused.", "experiment": {"file": "experiment-factor-coupled.json", "factor": "chunkMaxChars"},
                   "check": {"type": "rank", "snapshot": "snapshot-b.json", "question": "q2", "expected": 1}}
                ]}
                """);
        ClaimsCheck.Result result = ClaimsCheck.evaluate(claims, temp);
        String name = ClaimsCheck.display(claims);
        assertThat(result.sentences().get(1)).isEqualTo("The rank of q2 changed between the sizes. (experiment: chunkMaxChars 4000 in snapshot 1611 against 1000 in snapshot 1620, "
                + "with it chunkOverlapChars 500 against 125 and storeVersions [v-4000-500] against [v-1000-125]; checked: snapshot 1620 ranks q2 1st)");
        assertThat(result.problems()).containsExactly(
                name + " C-002 [experiment]: experiment-uncoupled.json snapshots snapshot-a.json and snapshot-b.json expected to differ only in chunkMaxChars, found 2 other recorded properties differing: "
                        + "chunkOverlapChars 500 against 125, storeVersions [v-4000-500] against [v-1000-125]",
                name + " C-003 [experiment]: experiment-bad-coupled.json snapshots expected to differ in the coupled property set, found s in both",
                name + " C-003 [experiment]: experiment-bad-coupled.json coupled property missing expected a property both snapshots record, found snapshot-a.json not recording it",
                name + " C-004 [experiment]: experiment-factor-coupled.json coupled expected properties other than the factor, found the factor chunkMaxChars");
    }
}
