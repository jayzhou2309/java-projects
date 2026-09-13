package project.stockrecommendationengine.rag.evaluation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 * report on stored snapshots (evaluation evidence Milestone 2). The traced snapshot ({@code -Drag.evidence.traced-snapshot}, default 459,
 * a traced run at snapshot 297's settings) and the untraced one ({@code -Drag.evidence.untraced-snapshot}, default 297) must exist. With the
 * cross-encoder enabled in this test's context only:
 * <ul>
 * <li>for every rerank input of every question of the traced snapshot, the window arithmetic on the stored chunk text beside the question
 * gives exactly the row count the trace recorded ({@code windowCount}), which checks the tokenizer, the current max-length, and the
 * parsed scoring against what the scorer actually ran;</li>
 * <li>every reported chunk whose trace records a window count has as many derived window starts;</li>
 * <li>msft-05's accepted phrases are reported against chunks 460, 515, and 571 with derived token spans, and chunk 515's rerank input
 * membership is observed; chunk 466 for msft-04 has an observed reranked position;</li>
 * <li>on the untraced snapshot every candidate field is unknown with reason "no trace" while token fields are derived.</li>
 * </ul>
 * With {@code -Drag.evidence.write-dir=<directory>} both reports are also written there as compact JSON, {@code evidence-<id>.json}, the form
 * committed as evidence (plan amendment 2).
 */
@SpringBootTest(properties = "rag.retrieval.cross-encoder.enabled=true")
@EnabledIfSystemProperty(named = "rag.evaluation.live", matches = "true")
class RetrievalEvidenceLiveTests {
    @Autowired RetrievalEvidenceService evidence;
    @Autowired RetrievalEvaluationRepository snapshots;
    @Autowired RetrievalEvaluationSetLoader loader;
    @Autowired PassageTokenizer tokenizer;
    @Autowired CrossEncoderProperties crossEncoder;
    @Autowired JdbcTemplate jdbc;

    @Test
    void theTracedSnapshotsRecordedRowCountsEqualTheWindowArithmeticAndTheNamedChunksAreReported() {
        long id = Long.getLong("rag.evidence.traced-snapshot", 459L);
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
        Set<Long> held = new TreeSet<>();
        for (PhraseEvidence phrase : msft05.phrases()) {
            for (ChunkEvidence chunk : phrase.chunks()) {
                held.add(chunk.chunkId());
                assertThat(chunk.occurrences()).allSatisfy(o -> assertThat(o.tokenSpan().basis()).isEqualTo(EvidenceValue.Basis.DERIVED));
                System.out.println("EVIDENCE_LIVE msft-05 chunk=" + chunk.chunkId() + " tokens=" + chunk.chunkTokens().value() + " W=" + chunk.windowLength().value()
                        + " starts=" + chunk.windowStarts().value() + " spans=" + chunk.occurrences().stream().map(o -> o.tokenSpan().value() + " " + o.head().value()
                        + " rows " + o.windowsHoldingWholly().value()).toList() + " fused=" + chunk.fusedPosition().value() + " rerankInput="
                        + chunk.rerankInput().value() + " (" + chunk.rerankInput().basis() + ") reranked=" + chunk.rerankedPosition().value() + " score=" + chunk.score().value());
                if (chunk.chunkId() == 515) assertThat(chunk.rerankInput().basis()).isEqualTo(EvidenceValue.Basis.OBSERVED);
            }
        }
        assertThat(held).contains(460L, 515L, 571L);
        QuestionEvidence msft04 = report.questions().stream().filter(q -> q.id().equals("msft-04")).findFirst().orElseThrow();
        ChunkEvidence c466 = msft04.phrases().stream().flatMap(p -> p.chunks().stream()).filter(c -> c.chunkId() == 466).findFirst().orElseThrow();
        assertThat(c466.rerankedPosition().basis()).isEqualTo(EvidenceValue.Basis.OBSERVED);
        assertThat(c466.rerankedPosition().value()).isNotNull();
        System.out.println("EVIDENCE_LIVE msft-04 chunk=466 reranked=" + c466.rerankedPosition().value() + " score=" + c466.score().value());
    }

    @Test
    void theUntracedSnapshotHasEveryCandidateFieldUnknownNoTrace() {
        long id = Long.getLong("rag.evidence.untraced-snapshot", 297L);
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
