package project.stockrecommendationengine.rag.evaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
 * The evidence report against the real store with the application's default wiring (cross-encoder off, so no tokenizer bean), inside
 * rolled-back transactions (D2 to D4 on stored data). Chunk ids of ingested filings are not asserted, since a rebuild changes them.
 * <ul>
 * <li>On rows the test inserts itself (a filing with an accession no real filing has, and the test set
 * {@value #DATABASE_SET} naming it), so it holds on a freshly migrated database: a phrase is reported against exactly the inserted chunks
 * of its accession and section that hold it (another section's chunk with the same text and a same-section chunk without it are not), a
 * phrase no chunk holds is reported as held by none, every candidate field is unknown {@code no trace}, every token field
 * {@code tokenizer unavailable}, and {@code loadedModelVersion} says the cross-encoder is not loaded.</li>
 * <li>For every question of the selected set, the reported holding chunks are exactly those SQL and the normalisation rule find in
 * whatever is stored (none on an empty store).</li>
 * <li>Opt-in (-Drag.evaluation.live=true, plan amendment 2): every accepted phrase of the selected set is held by at least one stored chunk,
 * which needs the set's filings ingested.</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class RetrievalEvidenceDatabaseTests {
    static final String DATABASE_SET = "evaluation/evidence/database-set.json";
    private static final String ACCESSION = "9999999914-26-000014";

    @Autowired RetrievalEvidenceService evidence;
    @Autowired RetrievalEvaluationRepository snapshots;
    @Autowired RetrievalEvaluationSetLoader loader;
    @Autowired RetrievalEvaluationProperties properties;
    @Autowired Optional<PassageTokenizer> tokenizer;
    @Autowired JdbcTemplate jdbc;

    @Test
    void onInsertedRowsAPhraseIsReportedAgainstExactlyTheChunksHoldingItAndAPhraseHeldByNoneSaysSo() {
        assertThat(tokenizer).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sec_filings WHERE accession_no = ?", Integer.class, ACCESSION)).isZero();
        Long filingId = jdbc.queryForObject("""
                INSERT INTO sec_filings(ticker, cik, accession_no, filing_type, filing_date, source_url, ingestion_status)
                VALUES ('TEST', '0000000001', ?, '10-K', '2026-09-13', 'https://example.invalid/evidence-database-test', 'EMBEDDED') RETURNING id
                """, Long.class, ACCESSION);
        long holding = chunk(filingId, 0, "ITEM_7", "Intro. The Evidence  DATABASE\n test phrase appears here.");
        long notHolding = chunk(filingId, 1, "ITEM_7", "Same section, other words entirely.");
        long otherSection = chunk(filingId, 2, "ITEM_1A", "evidence database test phrase in another section");
        long holdingAgain = chunk(filingId, 3, "ITEM_7", "evidence database test phrase");

        RetrievalEvaluationSet set = loader.load(DATABASE_SET);
        RetrievalEvaluation stored = store(set, DATABASE_SET);
        RetrievalEvidenceReport report = evidence.report(stored.id()).orElseThrow();

        assertThat(report.settings().loadedModelVersion()).isEqualTo(EvidenceValue.unknown(RetrievalEvidenceService.CROSS_ENCODER_NOT_LOADED));
        QuestionEvidence question = report.questions().get(0);
        assertThat(question.id()).isEqualTo("db-01");
        assertThat(question.phrases()).hasSize(2);
        PhraseEvidence held = question.phrases().get(0);
        assertThat(held.heldByStoredChunk()).isEqualTo(EvidenceValue.observed(true, RetrievalEvidenceService.SOURCE_HOLDING));
        assertThat(held.chunks()).extracting(ChunkEvidence::chunkId).containsExactly(holding, holdingAgain)
                .doesNotContain(notHolding, otherSection);
        PhraseEvidence none = question.phrases().get(1);
        assertThat(none.heldByStoredChunk()).isEqualTo(EvidenceValue.observed(false, RetrievalEvidenceService.SOURCE_HOLDING));
        assertThat(none.chunks()).isEmpty();
        assertThat(held.chunks()).allSatisfy(chunk -> {
            assertThat(chunk.chunkTokens()).isEqualTo(EvidenceValue.unknown("tokenizer unavailable"));
            assertThat(chunk.occurrences()).singleElement().satisfies(o -> {
                assertThat(o.characterSpan().basis()).isEqualTo(EvidenceValue.Basis.DERIVED);
                assertThat(o.tokenSpan()).isEqualTo(EvidenceValue.unknown("tokenizer unavailable"));
            });
            assertNoTrace(chunk);
        });
        assertThat(question.rankedAbove()).isEqualTo(EvidenceValue.unknown("no trace"));
        assertThat(evidence.markdown(report)).startsWith("# Retrieval evidence report: snapshot " + stored.id());
    }

    @Test
    void everyAcceptedPhraseOfTheSelectedSetIsReportedAgainstExactlyTheStoredChunksHoldingIt() {
        RetrievalEvaluationSet set = loader.load();
        RetrievalEvaluation stored = store(set, properties.getSet());
        RetrievalEvidenceReport report = evidence.report(stored.id()).orElseThrow();
        assertThat(report.questions()).extracting(QuestionEvidence::id).containsExactlyElementsOf(set.questions().stream().map(RetrievalEvaluationQuestion::id).toList());
        int phrases = 0;
        for (RetrievalEvaluationQuestion question : set.questions()) {
            QuestionEvidence reported = report.questions().stream().filter(q -> q.id().equals(question.id())).findFirst().orElseThrow();
            assertThat(reported.phrases()).hasSameSizeAs(question.expected());
            assertThat(reported.queryTokens()).isEqualTo(EvidenceValue.unknown("tokenizer unavailable"));
            for (int index = 0; index < question.expected().size(); index++) {
                ExpectedPassage passage = question.expected().get(index);
                PhraseEvidence phrase = reported.phrases().get(index);
                phrases++;
                List<Long> holding = holdingChunks(passage);
                assertThat(phrase.phrase()).isEqualTo(passage.phrase());
                assertThat(phrase.heldByStoredChunk().value()).as(question.id() + " " + passage).isEqualTo(!holding.isEmpty());
                assertThat(phrase.chunks()).extracting(ChunkEvidence::chunkId).as(question.id() + " " + passage).containsExactlyElementsOf(holding);
                for (ChunkEvidence chunk : phrase.chunks()) assertNoTrace(chunk);
            }
        }
        assertThat(phrases).isEqualTo(set.questions().stream().mapToInt(q -> q.expected().size()).sum());
    }

    @Test
    @EnabledIfSystemProperty(named = "rag.evaluation.live", matches = "true")
    void everyAcceptedPhraseOfTheSelectedSetIsHeldByAStoredChunk() {
        RetrievalEvaluationSet set = loader.load();
        RetrievalEvidenceReport report = evidence.report(store(set, properties.getSet()).id()).orElseThrow();
        List<String> notHeld = new ArrayList<>();
        for (QuestionEvidence question : report.questions()) {
            for (PhraseEvidence phrase : question.phrases()) {
                if (!Boolean.TRUE.equals(phrase.heldByStoredChunk().value()) || phrase.chunks().isEmpty()) {
                    notHeld.add(question.id() + " " + phrase.accessionNo() + " " + phrase.sectionKey() + " \"" + phrase.phrase() + "\"");
                }
            }
        }
        assertThat(notHeld).as("accepted phrases held by no stored chunk").isEmpty();
    }

    private RetrievalEvaluation store(RetrievalEvaluationSet set, String resource) {
        List<QuestionResult> results = set.questions().stream()
                .map(q -> new QuestionResult(q.id(), q.ticker(), q.kind(), null, null, null, "HYBRID_RRF")).toList();
        Map<String, Object> recorded = new LinkedHashMap<>();
        recorded.put("set", resource);
        recorded.put("rerank", null);
        recorded.put("trace", false);
        return snapshots.save(new RetrievalEvaluation(null, Instant.now(), set.version(), results.size(), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 10, "HYBRID_RRF", recorded, results, Map.of(), List.of(), null));
    }

    private long chunk(long filingId, int index, String sectionKey, String content) {
        return jdbc.queryForObject("INSERT INTO sec_filing_chunks(filing_id, chunk_index, section_key, content) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, filingId, index, sectionKey, content);
    }

    private List<Long> holdingChunks(ExpectedPassage passage) {
        List<Long> holding = new ArrayList<>();
        String wanted = RetrievalEvaluationService.normalise(passage.phrase());
        jdbc.query("SELECT c.id, c.content FROM sec_filing_chunks c JOIN sec_filings f ON f.id = c.filing_id WHERE f.accession_no = ? AND c.section_key = ? ORDER BY c.id",
                rs -> {
                    if (RetrievalEvaluationService.normalise(rs.getString("content")).contains(wanted)) holding.add(rs.getLong("id"));
                }, passage.accessionNo(), passage.sectionKey());
        return holding;
    }

    private static void assertNoTrace(ChunkEvidence chunk) {
        for (EvidenceValue<?> value : List.of(chunk.fusedPosition(), chunk.rerankInput(), chunk.rerankedPosition(), chunk.score(), chunk.windowCount(),
                chunk.windowScores(), chunk.returnedPosition())) {
            assertThat(value).isEqualTo(EvidenceValue.unknown("no trace"));
        }
    }
}
