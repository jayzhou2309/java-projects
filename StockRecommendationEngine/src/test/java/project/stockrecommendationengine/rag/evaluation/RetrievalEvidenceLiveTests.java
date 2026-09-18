package project.stockrecommendationengine.rag.evaluation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.QuestionTrace;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport.ChunkEvidence;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport.PhraseEvidence;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport.QuestionEvidence;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderProperties;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderTokenPositions;
import project.stockrecommendationengine.rag.retrieval.CrossEncoderTokenPositions.Scoring;
import project.stockrecommendationengine.rag.retrieval.PassageTokenizer;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.RerankedCandidate;
import static org.assertj.core.api.Assertions.*;

/**
 * Opt-in (-Drag.evaluation.live=true; model files under models/; reads the shared database, writes nothing, calls no model) evidence
 * report on stored snapshots (evaluation evidence Milestone 2; re-pointed 2026-09-18, plan 2026-09-17-chunk-size.md Milestone 4, H4, since
 * the rebuilds of 2026-09-17 renumbered every chunk and the traced snapshot 459 names rerank inputs by id). The traced snapshot
 * ({@code -Drag.evidence.traced-snapshot}, default {@value #TRACED_DEFAULT}: snapshot 1892, a traced run at snapshot 297's settings on the
 * store of 2026-09-18, committed under live-runs/2026-09-18-rag29-section-key/) and the untraced one ({@code -Drag.evidence.untraced-snapshot},
 * default 297, whose report reads the current store's chunks by accession, section, and phrase, so it depends on no chunk id) must exist.
 * Chunks are named by what they hold, never by id: msft-05's and msft-04's chunks are the chunks the report lists under the set's accepted
 * phrases (accession number, section key, phrase). With the cross-encoder enabled in this test's context only:
 * <ul>
 * <li>for every rerank input of every question of the traced snapshot, the window arithmetic on the stored chunk text beside the question
 * gives exactly the row count the trace recorded ({@code windowCount}), which checks the tokenizer, the current max-length, and the
 * parsed scoring against what the scorer actually ran;</li>
 * <li>every reported chunk whose trace records a window count has as many derived window starts;</li>
 * <li>each of msft-05's accepted phrases (Item 1, Item 7, and Item 8 of the 10-K 0001193125-26-323660) is held by a stored chunk with a
 * derived token span, every holding chunk's rerank input membership is observed, and the Item 7 phrase's holding chunk (the one whose
 * phrase sits outside the head window) is a rerank input with an observed reranked position; a
 * holding chunk of msft-04's phrase that is a rerank input has an observed reranked position;</li>
 * <li>on the untraced snapshot every candidate field is unknown with reason "no trace" while token fields are derived.</li>
 * </ul>
 * With {@code -Drag.evidence.write-dir=<directory>} both reports are also written there as compact JSON, {@code evidence-<id>.json}, the form
 * committed as evidence (plan amendment 2).
 */
@SpringBootTest(properties = "rag.retrieval.cross-encoder.enabled=true")
@EnabledIfSystemProperty(named = "rag.evaluation.live", matches = "true")
class RetrievalEvidenceLiveTests {
    static final long TRACED_DEFAULT = 1892L;
    static final long UNTRACED_DEFAULT = 297L;
    /** msft-05's and msft-04's expected passages are in the 10-K of this accession number. */
    static final String MSFT_10K = "0001193125-26-323660";

    @Autowired RetrievalEvidenceService evidence;
    @Autowired RetrievalEvaluationRepository snapshots;
    @Autowired RetrievalEvaluationSetLoader loader;
    @Autowired PassageTokenizer tokenizer;
    @Autowired CrossEncoderProperties crossEncoder;
    @Autowired JdbcTemplate jdbc;

    @Test
    void theTracedSnapshotsRecordedRowCountsEqualTheWindowArithmeticAndTheNamedChunksAreReported() {
        long id = Long.getLong("rag.evidence.traced-snapshot", TRACED_DEFAULT);
        RetrievalEvaluation snapshot = snapshots.findById(id).orElseThrow(() -> new AssertionError("snapshot " + id + " is not stored"));
        assertThat(snapshot.traces()).as("snapshot %d traces", id).isNotNull();
        Scoring scoring = Scoring.parse(String.valueOf(snapshot.properties().get("rerankerScoring")));
        assertThat(snapshot.properties().get("rerankerVersion")).isEqualTo(tokenizer.modelVersion());
        Map<String, String> questions = new HashMap<>();
        loader.load(String.valueOf(snapshot.properties().get("set"))).questions().forEach(q -> questions.put(q.id(), q.question()));

        int inputs = 0;
        for (QuestionTrace trace : snapshot.traces()) {
            assertThat(trace.trace()).as(trace.id()).isNotNull();
            int queryTokens = tokenizer.tokenize(CrossEncoderTokenPositions.bound(questions.get(trace.id()))).count();
            for (RerankedCandidate candidate : trace.trace().rerank().candidates()) {
                String content = jdbc.queryForObject("SELECT content FROM sec_filing_chunks WHERE id = ?", String.class, candidate.chunkId());
                int chunkTokens = tokenizer.tokenize(CrossEncoderTokenPositions.bound(content)).count();
                int w = CrossEncoderTokenPositions.windowLength(queryTokens, chunkTokens, crossEncoder.getMaxLength());
                assertThat(CrossEncoderTokenPositions.windowStarts(chunkTokens, w, scoring).length)
                        .as("%s chunk %d (%d tokens, W %d)", trace.id(), candidate.chunkId(), chunkTokens, w).isEqualTo(candidate.windowCount());
                inputs++;
            }
        }
        System.out.println("EVIDENCE_LIVE snapshot=" + id + " rerankInputsChecked=" + inputs + " maxLength=" + crossEncoder.getMaxLength() + " scoring=" + scoring.label());
        assertThat(inputs).isEqualTo(snapshot.traces().stream().mapToInt(t -> t.trace().rerank().inputCount()).sum());

        RetrievalEvidenceReport report = evidence.report(id).orElseThrow();
        write(id, report);
        int reported = 0;
        for (QuestionEvidence question : report.questions()) {
            for (PhraseEvidence phrase : question.phrases()) {
                for (ChunkEvidence chunk : phrase.chunks()) {
                    if (chunk.windowCount().basis() == EvidenceValue.Basis.OBSERVED && chunk.windowCount().value() != null) {
                        assertThat(chunk.windowStarts().value()).as("%s chunk %d", question.id(), chunk.chunkId()).hasSize(chunk.windowCount().value());
                        reported++;
                    }
                }
            }
        }
        System.out.println("EVIDENCE_LIVE reportedChunksWithRecordedWindowCount=" + reported);

        QuestionEvidence msft05 = report.questions().stream().filter(q -> q.id().equals("msft-05")).findFirst().orElseThrow();
        assertThat(msft05.phrases()).extracting(PhraseEvidence::accessionNo).containsOnly(MSFT_10K);
        assertThat(msft05.phrases()).extracting(PhraseEvidence::sectionKey).containsExactlyInAnyOrder("ITEM_1", "ITEM_7", "ITEM_8");
        int held = 0;
        for (PhraseEvidence phrase : msft05.phrases()) {
            assertThat(phrase.chunks()).as("msft-05 %s \"%s\" held by a stored chunk", phrase.sectionKey(), phrase.phrase()).isNotEmpty();
            for (ChunkEvidence chunk : phrase.chunks()) {
                held++;
                assertThat(chunk.occurrences()).allSatisfy(o -> assertThat(o.tokenSpan().basis()).isEqualTo(EvidenceValue.Basis.DERIVED));
                assertThat(chunk.rerankInput().basis()).as("msft-05 %s chunk %d rerank input", phrase.sectionKey(), chunk.chunkId()).isEqualTo(EvidenceValue.Basis.OBSERVED);
                System.out.println("EVIDENCE_LIVE msft-05 section=" + phrase.sectionKey() + " chunk=" + chunk.chunkId() + " tokens=" + chunk.chunkTokens().value() + " W=" + chunk.windowLength().value()
                        + " starts=" + chunk.windowStarts().value() + " spans=" + chunk.occurrences().stream().map(o -> o.tokenSpan().value() + " " + o.head().value()
                        + " rows " + o.windowsHoldingWholly().value()).toList() + " fused=" + chunk.fusedPosition().value() + " rerankInput="
                        + chunk.rerankInput().value() + " (" + chunk.rerankInput().basis() + ") reranked=" + chunk.rerankedPosition().value() + " score=" + chunk.score().value());
                if (phrase.sectionKey().equals("ITEM_7")) {
                    assertThat(chunk.rerankInput().value()).as("msft-05 Item 7 chunk %d a rerank input", chunk.chunkId()).isEqualTo(true);
                    assertThat(chunk.rerankedPosition().basis()).isEqualTo(EvidenceValue.Basis.OBSERVED);
                    assertThat(chunk.rerankedPosition().value()).isNotNull();
                }
            }
        }
        assertThat(held).isGreaterThanOrEqualTo(3);
        QuestionEvidence msft04 = report.questions().stream().filter(q -> q.id().equals("msft-04")).findFirst().orElseThrow();
        assertThat(msft04.phrases()).extracting(PhraseEvidence::accessionNo).containsOnly(MSFT_10K);
        List<ChunkEvidence> msft04Inputs = msft04.phrases().stream().flatMap(p -> p.chunks().stream())
                .filter(c -> c.rerankInput().basis() == EvidenceValue.Basis.OBSERVED && Boolean.TRUE.equals(c.rerankInput().value())).toList();
        assertThat(msft04Inputs).as("msft-04 holding chunks that are rerank inputs").isNotEmpty();
        for (ChunkEvidence chunk : msft04Inputs) {
            assertThat(chunk.rerankedPosition().basis()).isEqualTo(EvidenceValue.Basis.OBSERVED);
            assertThat(chunk.rerankedPosition().value()).isNotNull();
            System.out.println("EVIDENCE_LIVE msft-04 chunk=" + chunk.chunkId() + " fused=" + chunk.fusedPosition().value() + " reranked=" + chunk.rerankedPosition().value() + " score=" + chunk.score().value());
        }
    }

    @Test
    void theUntracedSnapshotHasEveryCandidateFieldUnknownNoTrace() {
        long id = Long.getLong("rag.evidence.untraced-snapshot", UNTRACED_DEFAULT);
        RetrievalEvidenceReport report = evidence.report(id).orElseThrow(() -> new AssertionError("snapshot " + id + " is not stored"));
        assertThat(report.traced().value()).isFalse();
        write(id, report);
        int chunks = 0;
        for (QuestionEvidence question : report.questions()) {
            assertThat(List.of(question.rerankOutcome(), question.fusedCount(), question.ranking(), question.bestAcceptedChunk(), question.rankedAbove()))
                    .allSatisfy(value -> assertThat(value).isEqualTo(EvidenceValue.unknown("no trace")));
            for (PhraseEvidence phrase : question.phrases()) {
                for (ChunkEvidence chunk : phrase.chunks()) {
                    chunks++;
                    assertThat(List.of(chunk.fusedPosition(), chunk.rerankInput(), chunk.rerankedPosition(), chunk.score(), chunk.windowCount(),
                            chunk.windowScores(), chunk.returnedPosition())).allSatisfy(value -> assertThat(value).isEqualTo(EvidenceValue.unknown("no trace")));
                    assertThat(chunk.chunkTokens().basis()).isEqualTo(EvidenceValue.Basis.DERIVED);
                }
            }
        }
        System.out.println("EVIDENCE_LIVE untraced snapshot=" + id + " questions=" + report.questions().size() + " chunks=" + chunks);
        assertThat(chunks).isPositive();
    }

    /** With -Drag.evidence.write-dir=<directory>, the report as committed evidence: compact JSON on one line (plan amendment 2), evidence-<id>.json. */
    private void write(long id, RetrievalEvidenceReport report) {
        String directory = System.getProperty("rag.evidence.write-dir");
        if (directory == null) return;
        String json = evidence.json(report);
        assertThat(json).doesNotContain("\n");
        try {
            java.nio.file.Path file = java.nio.file.Path.of(directory, "evidence-" + id + ".json");
            java.nio.file.Files.writeString(file, json + "\n");
            System.out.println("EVIDENCE_LIVE wrote " + file + " bytes=" + java.nio.file.Files.size(file));
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }
}
