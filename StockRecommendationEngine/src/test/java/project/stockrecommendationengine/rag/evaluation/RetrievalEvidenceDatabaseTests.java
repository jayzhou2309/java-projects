package project.stockrecommendationengine.rag.evaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.QuestionResult;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport.ChunkEvidence;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport.PhraseEvidence;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport.QuestionEvidence;
import project.stockrecommendationengine.rag.retrieval.PassageTokenizer;
import static org.assertj.core.api.Assertions.*;

/**
 * The evidence report against the real store with the application's default wiring (cross-encoder off, so no tokenizer bean): for a
 * snapshot of every question of the selected set stored without traces (inside a rolled-back transaction), every accepted phrase is
 * reported against exactly the stored chunks of its accession and section that hold it, recomputed here with SQL and the normalisation
 * rule; every candidate field is unknown with reason "no trace" and every token field unknown with reason "tokenizer unavailable" (D2 to
 * D4 on stored data). Chunk ids are not asserted, since a rebuild changes them.
 */
@SpringBootTest
@Transactional
class RetrievalEvidenceDatabaseTests {
    @Autowired RetrievalEvidenceService evidence;
    @Autowired RetrievalEvaluationRepository snapshots;
    @Autowired RetrievalEvaluationSetLoader loader;
    @Autowired RetrievalEvaluationProperties properties;
    @Autowired Optional<PassageTokenizer> tokenizer;
    @Autowired JdbcTemplate jdbc;

    @Test
    void everyAcceptedPhraseIsReportedAgainstEveryStoredChunkHoldingItWithTraceAndTokenFieldsUnknown() {
        assertThat(tokenizer).isEmpty();
        RetrievalEvaluationSet set = loader.load();
        List<QuestionResult> results = set.questions().stream()
                .map(q -> new QuestionResult(q.id(), q.ticker(), q.kind(), null, null, null, "HYBRID_RRF")).toList();
        Map<String, Object> recorded = new LinkedHashMap<>();
        recorded.put("set", properties.getSet());
        recorded.put("rerank", null);
        recorded.put("trace", false);
        RetrievalEvaluation stored = snapshots.save(new RetrievalEvaluation(null, Instant.now(), set.version(), results.size(), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 10, "HYBRID_RRF", recorded, results, Map.of(), List.of(), null));

        RetrievalEvidenceReport report = evidence.report(stored.id()).orElseThrow();
        assertThat(report.questions()).extracting(QuestionEvidence::id).containsExactlyElementsOf(set.questions().stream().map(RetrievalEvaluationQuestion::id).toList());
        assertThat(report.settings().loadedModelVersion()).isEqualTo(EvidenceValue.unknown("tokenizer unavailable"));
        int phrases = 0;
        int chunks = 0;
        for (RetrievalEvaluationQuestion question : set.questions()) {
            QuestionEvidence reported = report.questions().stream().filter(q -> q.id().equals(question.id())).findFirst().orElseThrow();
            assertThat(reported.phrases()).hasSameSizeAs(question.expected());
            assertThat(reported.rankedAbove()).isEqualTo(EvidenceValue.unknown("no trace"));
            assertThat(reported.queryTokens()).isEqualTo(EvidenceValue.unknown("tokenizer unavailable"));
            for (int index = 0; index < question.expected().size(); index++) {
                ExpectedPassage passage = question.expected().get(index);
                PhraseEvidence phrase = reported.phrases().get(index);
                phrases++;
                List<Long> holding = new ArrayList<>();
                String wanted = RetrievalEvaluationService.normalise(passage.phrase());
                jdbc.query("SELECT c.id, c.content FROM sec_filing_chunks c JOIN sec_filings f ON f.id = c.filing_id WHERE f.accession_no = ? AND c.section_key = ? ORDER BY c.id",
                        rs -> {
                            if (RetrievalEvaluationService.normalise(rs.getString("content")).contains(wanted)) holding.add(rs.getLong("id"));
                        }, passage.accessionNo(), passage.sectionKey());
                assertThat(phrase.phrase()).isEqualTo(passage.phrase());
                assertThat(phrase.heldByStoredChunk().value()).as(question.id() + " " + passage).isEqualTo(!holding.isEmpty()).isTrue();
                assertThat(phrase.chunks()).extracting(ChunkEvidence::chunkId).as(question.id() + " " + passage).containsExactlyElementsOf(holding);
                for (ChunkEvidence chunk : phrase.chunks()) {
                    chunks++;
                    assertThat(chunk.chunkTokens()).isEqualTo(EvidenceValue.unknown("tokenizer unavailable"));
                    assertThat(chunk.occurrences()).isNotEmpty().allSatisfy(o -> {
                        assertThat(o.characterSpan().basis()).isEqualTo(EvidenceValue.Basis.DERIVED);
                        assertThat(o.tokenSpan()).isEqualTo(EvidenceValue.unknown("tokenizer unavailable"));
                    });
                    for (EvidenceValue<?> value : List.of(chunk.fusedPosition(), chunk.rerankInput(), chunk.rerankedPosition(), chunk.score(), chunk.windowCount(),
                            chunk.windowScores(), chunk.returnedPosition())) {
                        assertThat(value).isEqualTo(EvidenceValue.unknown("no trace"));
                    }
                }
            }
        }
        assertThat(phrases).isEqualTo(set.questions().stream().mapToInt(q -> q.expected().size()).sum());
        assertThat(chunks).isGreaterThanOrEqualTo(phrases);
        assertThat(evidence.markdown(report)).startsWith("# Retrieval evidence report: snapshot " + stored.id());
    }
}
