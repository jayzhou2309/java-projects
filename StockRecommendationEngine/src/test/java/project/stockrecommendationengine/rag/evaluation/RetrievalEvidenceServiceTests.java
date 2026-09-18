package project.stockrecommendationengine.rag.evaluation;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import project.stockrecommendationengine.rag.evaluation.EvidenceValue.Basis;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.QuestionTrace;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport.ChunkEvidence;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport.OccurrenceEvidence;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport.PhraseEvidence;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport.QuestionEvidence;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport.RankedChunk;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvidenceReport.Span;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.FusedCandidate;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.Outcome;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.RemovedCandidate;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace.RerankedCandidate;
import project.stockrecommendationengine.rag.retrieval.ScriptedWordTokenizer;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The evidence report over scripted snapshots, set, chunks, and a word tokenizer (evaluation evidence Milestone 2, D1 to D4, and the
 * JSON half of D5); no database or model. Hand-computed positions are explained in {@link ScriptedEvidence}.
 */
class RetrievalEvidenceServiceTests {
    private static final List<String> QUESTION_CANDIDATE_FIELDS = List.of("rerankOutcome", "fallbackReason", "scoresNotRecorded", "fusedCount",
            "rerankInputCount", "ranking", "bestAcceptedChunk", "bestAcceptedPosition", "rankedAbove", "removed", "acceptedChunkRemoved");
    private static final List<String> CHUNK_CANDIDATE_FIELDS = List.of("fusedPosition", "rerankInput", "rerankedPosition", "score", "windowCount",
            "windowScores", "returnedPosition", "removedRedundantWith");
    private static final List<String> CHUNK_TOKEN_FIELDS = List.of("chunkTokens", "windowLength", "windowStarts");
    private static final List<String> OCCURRENCE_TOKEN_FIELDS = List.of("tokenSpan", "head", "windowsHoldingWholly");

    private final ScriptedEvidence scripted = new ScriptedEvidence();

    @Test
    void theScriptedReportSerialisesToTheCommittedFixture() throws Exception {
        RetrievalEvidenceService service = scripted.service();
        String json = service.json(service.report(ScriptedEvidence.tracedSnapshot()));
        String untraced = service.json(service.report(ScriptedEvidence.untracedSnapshot()));
        if (Boolean.getBoolean("rag.evidence.fixture.write")) {
            Files.writeString(ScriptedEvidence.FIXTURE_JSON, service.parse(json).toPrettyString() + "\n");
            Files.writeString(ScriptedEvidence.FIXTURE_MARKDOWN, EvidenceMarkdownRenderer.render(service.parse(json)));
            Files.writeString(ScriptedEvidence.FIXTURE_UNTRACED_JSON, service.parse(untraced).toPrettyString() + "\n");
        }
        assertThat(service.parse(json)).as("regenerate with -Drag.evidence.fixture.write=true after an intended change")
                .isEqualTo(service.parse(Files.readString(ScriptedEvidence.FIXTURE_JSON)));
        assertThat(service.parse(untraced)).as("regenerate with -Drag.evidence.fixture.write=true after an intended change")
                .isEqualTo(service.parse(Files.readString(ScriptedEvidence.FIXTURE_UNTRACED_JSON)));
    }

    @Test
    void aTokenizerThatReportsItsModelsMaxLengthDecidesWAndNamesItsSourceInsteadOfTheCrossEncoderProperty() {
        // The second reranker model's PassageTokenizer reports its own max-length (17 here; the cross-encoder property stays 20).
        ScriptedWordTokenizer words = new ScriptedWordTokenizer(ScriptedEvidence.VERSION);
        project.stockrecommendationengine.rag.retrieval.PassageTokenizer ownWindow = new project.stockrecommendationengine.rag.retrieval.PassageTokenizer() {
            @Override
            public Tokens tokenize(String text) {
                return words.tokenize(text);
            }

            @Override
            public String modelVersion() {
                return words.modelVersion();
            }

            @Override
            public MaxLength maxLength() {
                return new MaxLength(17, "current configuration rag.retrieval.gte-reranker.max-length (snapshots do not record it)");
            }
        };
        RetrievalEvidenceReport report = scripted.service(Optional.of(ownWindow)).report(ScriptedEvidence.tracedSnapshot());
        assertThat(report.settings().maxLength()).isEqualTo(EvidenceValue.observed(17,
                "current configuration rag.retrieval.gte-reranker.max-length (snapshots do not record it)"));
        // Chunk 101 beside q1 (3 query tokens): W = 17 - 3 - 3 = 11, rows 0, 11, 22, 29 under overlap 0 and 4 windows.
        ChunkEvidence c101 = chunk(question(report, "q1"), 0, 101);
        assertThat(c101.windowLength()).isEqualTo(EvidenceValue.derived(11, RetrievalEvidenceService.RULE_WINDOW_LENGTH));
        assertThat(c101.windowStarts().value()).containsExactly(0, 11, 22, 29);
        // The default (the current model's tokenizer reports none) keeps the cross-encoder property and its source.
        assertThat(words.maxLength()).isNull();
        assertThat(scripted.service().report(ScriptedEvidence.tracedSnapshot()).settings().maxLength())
                .isEqualTo(EvidenceValue.observed(20, RetrievalEvidenceService.SOURCE_MAX_LENGTH));
    }

    @Test
    void tokenSpansHeadMembershipAndWindowsEqualHandComputedValues() {
        RetrievalEvidenceReport report = scripted.service().report(ScriptedEvidence.tracedSnapshot());
        assertThat(report.settings().maxLength()).isEqualTo(EvidenceValue.observed(20, RetrievalEvidenceService.SOURCE_MAX_LENGTH));
        assertThat(report.settings().passageScoring()).isEqualTo(EvidenceValue.derived("max-window", RetrievalEvidenceService.RULE_SCORING));
        assertThat(report.settings().windowOverlapTokens().value()).isZero();
        assertThat(report.settings().maxWindows().value()).isEqualTo(4);

        QuestionEvidence q1 = question(report, "q1");
        assertThat(q1.queryTokens()).isEqualTo(EvidenceValue.derived(3, RetrievalEvidenceService.RULE_QUERY_TOKENS));
        // Chunk 101: 40 tokens, W = 17 - 3 = 14, rows 0, 14, 26; tokens 12..15 (characters 60..79) are split by the head cut and no row holds them.
        ChunkEvidence c101 = chunk(q1, 0, 101);
        assertThat(c101.chunkTokens()).isEqualTo(EvidenceValue.derived(40, RetrievalEvidenceService.RULE_CHUNK_TOKENS));
        assertThat(c101.windowLength()).isEqualTo(EvidenceValue.derived(14, RetrievalEvidenceService.RULE_WINDOW_LENGTH));
        assertThat(c101.windowStarts()).isEqualTo(EvidenceValue.derived(List.of(0, 14, 26), RetrievalEvidenceService.RULE_WINDOW_STARTS));
        assertThat(c101.occurrences()).singleElement().isEqualTo(new OccurrenceEvidence(
                EvidenceValue.derived(new Span(60, 79), RetrievalEvidenceService.RULE_CHARACTER_SPAN),
                EvidenceValue.derived(new Span(12, 16), RetrievalEvidenceService.RULE_TOKEN_SPAN),
                EvidenceValue.derived("partly", RetrievalEvidenceService.RULE_HEAD),
                EvidenceValue.derived(List.of(), RetrievalEvidenceService.RULE_WINDOWS_HOLDING)));
        // Chunk 102: "Intro w012  W013\n\tw014 w015 tail", 6 tokens, one row; the phrase is characters 6..27, tokens 1..4, wholly in row 1.
        ChunkEvidence c102 = chunk(q1, 0, 102);
        assertThat(c102.chunkTokens().value()).isEqualTo(6);
        assertThat(c102.windowLength().value()).isEqualTo(14);
        assertThat(c102.windowStarts().value()).containsExactly(0);
        OccurrenceEvidence o102 = c102.occurrences().get(0);
        assertThat(o102.characterSpan().value()).isEqualTo(new Span(6, 27));
        assertThat(o102.tokenSpan().value()).isEqualTo(new Span(1, 5));
        assertThat(o102.head().value()).isEqualTo("wholly");
        assertThat(o102.windowsHoldingWholly().value()).containsExactly(1);
        // q2 (2 query tokens) and chunk 201: W = 17 - 2 = 15, rows 0, 15, 25; tokens 30..31 are past the head and wholly in row 3.
        ChunkEvidence c201 = chunk(question(report, "q2"), 0, 201);
        assertThat(c201.windowLength().value()).isEqualTo(15);
        assertThat(c201.windowStarts().value()).containsExactly(0, 15, 25);
        assertThat(c201.occurrences().get(0).tokenSpan().value()).isEqualTo(new Span(30, 32));
        assertThat(c201.occurrences().get(0).head().value()).isEqualTo("not");
        assertThat(c201.occurrences().get(0).windowsHoldingWholly().value()).containsExactly(3);
        // The same chunk beside q3 (3 query tokens): W 14, rows 0, 14, 26, still row 3.
        ChunkEvidence c201q3 = chunk(question(report, "q3"), 0, 201);
        assertThat(c201q3.windowLength().value()).isEqualTo(14);
        assertThat(c201q3.windowStarts().value()).containsExactly(0, 14, 26);
        assertThat(c201q3.occurrences().get(0).windowsHoldingWholly().value()).containsExactly(3);
        // Each chunk is tokenized once per report, each question once.
        assertThat(scripted.tokenizer.tokenized()).containsExactly("q1 alpha beta", ScriptedWordTokenizer.words(40), "Intro w012  W013\n\tw014 w015 tail",
                "q2 gamma", ScriptedWordTokenizer.words(40), "q3 delta epsilon");
    }

    @Test
    void everyAcceptedPhraseIsReportedAgainstEveryStoredChunkThatHoldsItAndAPhraseHeldByNoneSaysSo() {
        RetrievalEvidenceReport report = scripted.service().report(ScriptedEvidence.tracedSnapshot());
        assertThat(report.questions()).extracting(QuestionEvidence::id).containsExactly("q1", "q2", "q3");
        QuestionEvidence q1 = question(report, "q1");
        assertThat(q1.acceptedPhraseCount()).isEqualTo(EvidenceValue.observed(2, "bundled set " + ScriptedEvidence.SET_RESOURCE));
        assertThat(q1.phrases()).extracting(PhraseEvidence::phrase).containsExactly("w012 w013 w014 w015", "a phrase no stored chunk holds");
        PhraseEvidence held = q1.phrases().get(0);
        assertThat(held.heldByStoredChunk()).isEqualTo(EvidenceValue.observed(true, RetrievalEvidenceService.SOURCE_HOLDING));
        // 101 and 102 hold it (102 with other case and whitespace); 100 in the same section does not. 101 was a rerank input not returned.
        assertThat(held.chunks()).extracting(ChunkEvidence::chunkId).containsExactly(101L, 102L);
        assertThat(chunk(q1, 0, 101).returnedPosition()).isEqualTo(EvidenceValue.observed(null, RetrievalEvidenceService.SOURCE_RETURNED));
        PhraseEvidence none = q1.phrases().get(1);
        assertThat(none.heldByStoredChunk()).isEqualTo(EvidenceValue.observed(false, RetrievalEvidenceService.SOURCE_HOLDING));
        assertThat(none.chunks()).isEmpty();
        // q2's chunk 201 is not in the fused list and was not returned; q3 failed but its phrase is still reported.
        ChunkEvidence c201 = chunk(question(report, "q2"), 0, 201);
        assertThat(c201.fusedPosition()).isEqualTo(EvidenceValue.observed(null, RetrievalEvidenceService.SOURCE_FUSED));
        assertThat(question(report, "q3").phrases().get(0).chunks()).extracting(ChunkEvidence::chunkId).containsExactly(201L);
        verify(scripted.chunks).chunks(ScriptedEvidence.ACC, "ITEM_7");
        verify(scripted.chunks).chunks(ScriptedEvidence.ACC, "ITEM_1A");
        verify(scripted.chunks).chunks(ScriptedEvidence.ACC2, "ITEM_1");
        verifyNoMoreInteractions(scripted.chunks);
    }

    @Test
    void candidateFieldsComeFromTheTraceForRerankedFallbackAndFailedQuestions() {
        RetrievalEvidenceReport report = scripted.service().report(ScriptedEvidence.tracedSnapshot());
        QuestionEvidence q1 = question(report, "q1");
        assertThat(q1.rank()).isEqualTo(EvidenceValue.observed(2, RetrievalEvidenceService.SOURCE_RESULTS + " rank (null: no matching chunk in the window)"));
        assertThat(q1.matchedChunkId().value()).isEqualTo(102L);
        assertThat(q1.rerankOutcome()).isEqualTo(EvidenceValue.observed("RERANKED", "trace rerank outcome"));
        assertThat(q1.rerankInputCount().value()).isEqualTo(3);
        assertThat(q1.fusedCount().value()).isEqualTo(4);
        ChunkEvidence c101 = chunk(q1, 0, 101);
        assertThat(c101.fusedPosition()).isEqualTo(EvidenceValue.observed(1, RetrievalEvidenceService.SOURCE_FUSED));
        assertThat(c101.rerankInput()).isEqualTo(EvidenceValue.observed(true, RetrievalEvidenceService.SOURCE_RERANK_INPUT));
        assertThat(c101.rerankedPosition()).isEqualTo(EvidenceValue.observed(3, "trace rerank candidates rerankedPosition"));
        assertThat(c101.score()).isEqualTo(EvidenceValue.observed(-0.5f, "trace rerank candidates score"));
        assertThat(c101.windowCount().value()).isEqualTo(3);
        assertThat(c101.windowScores().value()).containsExactly(-0.5f, -3.0f, -2.0f);
        assertThat(chunk(q1, 0, 102).returnedPosition()).isEqualTo(EvidenceValue.observed(2, RetrievalEvidenceService.SOURCE_RETURNED));
        // Ranking: reranked order 301, 102, 101; the best accepted chunk is 102 at 2 with 301 above it.
        assertThat(q1.ranking()).isEqualTo(EvidenceValue.observed("reranked order", "trace rerank outcome RERANKED"));
        assertThat(q1.bestAcceptedChunk().value()).isEqualTo(102L);
        assertThat(q1.bestAcceptedPosition().value()).isEqualTo(2);
        assertThat(q1.rankedAbove().basis()).isEqualTo(Basis.OBSERVED);
        assertThat(q1.rankedAbove().value()).singleElement().isEqualTo(new RankedChunk(301,
                EvidenceValue.observed(1, "trace rerank candidates rerankedPosition"), EvidenceValue.observed(2, "trace rerank candidates fusedPosition"),
                EvidenceValue.observed(2.5f, "trace rerank candidates score"), EvidenceValue.observed(List.of(2.5f, -1.0f), "trace rerank candidates windowScores")));

        QuestionEvidence q2 = question(report, "q2");
        assertThat(q2.rerankOutcome().value()).isEqualTo("FALLBACK");
        assertThat(q2.fallbackReason().value()).isEqualTo("timeout");
        String noScores = "rerank fell back (timeout): the trace records no reranked positions or scores";
        ChunkEvidence c201 = chunk(q2, 0, 201);
        assertThat(c201.rerankInput()).isEqualTo(EvidenceValue.derived(false, RetrievalEvidenceService.RULE_FALLBACK_INPUT));
        for (EvidenceValue<?> value : List.of(c201.rerankedPosition(), c201.score(), c201.windowCount(), c201.windowScores())) {
            assertThat(value).isEqualTo(EvidenceValue.unknown(noScores));
        }
        assertThat(q2.ranking().value()).isEqualTo("fused order");
        assertThat(q2.bestAcceptedChunk()).isEqualTo(EvidenceValue.observed(null, "trace fused order: no chunk holding an accepted phrase is in it"));
        assertThat(q2.rankedAbove().value()).extracting(RankedChunk::chunkId).containsExactly(202L, 203L);
        assertThat(q2.rankedAbove().value()).allSatisfy(entry -> assertThat(entry.score()).isEqualTo(EvidenceValue.unknown(noScores)));

        QuestionEvidence q3 = question(report, "q3");
        assertThat(q3.error().value()).isEqualTo("IllegalStateException: Embedding request failed");
        assertThat(q3.rerankOutcome()).isEqualTo(EvidenceValue.unknown(RetrievalEvidenceService.NO_TRACE_FOR_QUESTION));
        assertThat(chunk(q3, 0, 201).fusedPosition()).isEqualTo(EvidenceValue.unknown(RetrievalEvidenceService.NO_TRACE_FOR_QUESTION));
    }

    @Test
    void onASnapshotWithoutTracesEveryCandidateAndScoreFieldIsUnknownNoTrace() {
        RetrievalEvidenceService service = scripted.service();
        JsonNode report = service.parse(service.json(service.report(ScriptedEvidence.untracedSnapshot())));
        assertThat(report.path("traced").path("value").booleanValue()).isFalse();
        List<String> checked = new ArrayList<>();
        for (JsonNode question : report.get("questions")) {
            for (String field : QUESTION_CANDIDATE_FIELDS) {
                assertUnknown(question.get(field), RetrievalEvidenceService.NO_TRACE, question.get("id").asString() + "." + field);
                checked.add(field);
            }
            for (JsonNode phrase : question.get("phrases")) {
                for (JsonNode chunk : phrase.get("chunks")) {
                    for (String field : CHUNK_CANDIDATE_FIELDS) {
                        assertUnknown(chunk.get(field), RetrievalEvidenceService.NO_TRACE, question.get("id").asString() + " chunk " + chunk.get("chunkId") + "." + field);
                        checked.add(field);
                    }
                    // Token fields do not depend on a trace.
                    assertThat(chunk.path("chunkTokens").path("basis").asString()).isEqualTo("derived");
                }
            }
        }
        assertThat(checked).hasSize(3 * QUESTION_CANDIDATE_FIELDS.size() + 4 * CHUNK_CANDIDATE_FIELDS.size());
        // Results and phrases are still observed from the snapshot and the store.
        assertThat(report.at("/questions/0/rank/value").intValue()).isEqualTo(2);
        assertThat(report.at("/questions/0/phrases/0/heldByStoredChunk/value").booleanValue()).isTrue();
    }

    @Test
    void aSnapshotWithTracesDoesNotLendThemToAQuestionWithoutOne() {
        // A traced snapshot missing one question's trace entry: that question's candidate fields are unknown, not taken from another question.
        RetrievalEvaluation traced = ScriptedEvidence.tracedSnapshot();
        List<QuestionTrace> withoutQ1 = traced.traces().stream().filter(t -> !t.id().equals("q1")).toList();
        RetrievalEvidenceReport report = scripted.service().report(ScriptedEvidence.with(traced, traced.properties(), withoutQ1));
        QuestionEvidence q1 = question(report, "q1");
        assertThat(q1.rankedAbove()).isEqualTo(EvidenceValue.unknown("no trace recorded for this question"));
        assertThat(chunk(q1, 0, 101).score()).isEqualTo(EvidenceValue.unknown("no trace recorded for this question"));
        assertThat(question(report, "q2").rerankOutcome().value()).isEqualTo("FALLBACK");
    }

    @Test
    void aNullTraceSaysRetrievalFailedOnlyWhenTheResultRecordsAnError() {
        // Amendment 2: q3's result records an error, so its reason names the failure; q2 with a null trace and no error does not.
        RetrievalEvaluation traced = ScriptedEvidence.tracedSnapshot();
        RetrievalEvidenceReport report = scripted.service().report(ScriptedEvidence.with(traced, traced.properties(),
                List.of(traced.traces().get(0), new QuestionTrace("q2", null), traced.traces().get(2))));
        QuestionEvidence q2 = question(report, "q2");
        assertThat(q2.error().value()).isNull();
        assertThat(q2.rerankOutcome()).isEqualTo(EvidenceValue.unknown(RetrievalEvidenceService.NO_TRACE_RECORDED));
        assertThat(chunk(q2, 0, 201).rerankInput()).isEqualTo(EvidenceValue.unknown(RetrievalEvidenceService.NO_TRACE_RECORDED));
        assertThat(q2.rankedAbove()).isEqualTo(EvidenceValue.unknown("no trace recorded for this question"));
        QuestionEvidence q3 = question(report, "q3");
        assertThat(q3.error().value()).isNotNull();
        assertThat(q3.rerankOutcome()).isEqualTo(EvidenceValue.unknown("no trace for this question (its retrieval failed)"));
    }

    @Test
    void aNonInputChunksRerankInputSourceDescribesItsFalseValueAndWindowRulesSayTheRowsMayNotHaveBeenScored() {
        // Amendment 2: chunk 101 beside a trace whose rerank input is only 301 and 102.
        RetrievalTrace narrow = new RetrievalTrace(4, 4, null,
                List.of(new FusedCandidate(301, 1, 1, 1, null), new FusedCandidate(102, 2, 2, 2, null), new FusedCandidate(101, 3, 3, 3, null)),
                new RetrievalTrace.Rerank(Outcome.RERANKED, null, 2, null, List.of(new RerankedCandidate(102, 2, 1, 1.0f, 1, List.of(1.0f)),
                        new RerankedCandidate(301, 1, 2, 0.5f, 1, List.of(0.5f)))), List.of(102L, 301L));
        RetrievalEvaluation traced = ScriptedEvidence.tracedSnapshot();
        RetrievalEvidenceReport report = scripted.service().report(ScriptedEvidence.with(traced, traced.properties(),
                List.of(new QuestionTrace("q1", narrow), traced.traces().get(1), traced.traces().get(2))));
        ChunkEvidence c101 = chunk(question(report, "q1"), 0, 101);
        assertThat(c101.rerankInput()).isEqualTo(EvidenceValue.observed(false, "trace rerank candidates (true: a rerank input; false: not a rerank input)"));
        assertThat(c101.rerankedPosition()).isEqualTo(EvidenceValue.observed(null, RetrievalEvidenceService.SOURCE_CANDIDATES));
        assertThat(c101.windowStarts().rule()).contains("not rows that were scored").contains("a chunk outside the rerank input");
        assertThat(c101.occurrences().get(0).windowsHoldingWholly().rule()).contains("not rows that were scored");
        assertThat(c101.windowLength().rule()).contains("max-length (from the current configuration, not recorded in the snapshot)");
    }

    @Test
    void withoutTheTokenizerTokenFieldsAreUnknownTokenizerUnavailableAndTheReportIsStillBuilt() {
        RetrievalEvidenceService service = scripted.service(Optional.empty());
        RetrievalEvidenceReport built = service.report(ScriptedEvidence.tracedSnapshot());
        JsonNode report = service.parse(service.json(built));
        assertUnknown(report.at("/settings/loadedModelVersion"), RetrievalEvidenceService.CROSS_ENCODER_NOT_LOADED, "loadedModelVersion");
        int chunks = 0;
        for (JsonNode question : report.get("questions")) {
            assertUnknown(question.get("queryTokens"), RetrievalEvidenceService.TOKENIZER_UNAVAILABLE, "queryTokens");
            for (JsonNode phrase : question.get("phrases")) {
                for (JsonNode chunk : phrase.get("chunks")) {
                    chunks++;
                    for (String field : CHUNK_TOKEN_FIELDS) assertUnknown(chunk.get(field), RetrievalEvidenceService.TOKENIZER_UNAVAILABLE, field);
                    for (JsonNode occurrence : chunk.get("occurrences")) {
                        for (String field : OCCURRENCE_TOKEN_FIELDS) assertUnknown(occurrence.get(field), RetrievalEvidenceService.TOKENIZER_UNAVAILABLE, field);
                        assertThat(occurrence.path("characterSpan").path("basis").asString()).isEqualTo("derived");
                    }
                }
            }
        }
        assertThat(chunks).isEqualTo(4);
        // Candidate fields are unaffected.
        assertThat(chunk(question(built, "q1"), 0, 101).rerankedPosition().value()).isEqualTo(3);
        assertThat(service.markdown(built)).contains("unknown: tokenizer unavailable");
    }

    /** E3 (plan 2026-09-17-chunk-size.md, Milestone 2): the chunk settings and store versions as recorded; unknown, not an error, when absent. */
    @Test
    void chunkSettingsAndStoreVersionsAreReportedAsRecordedAndUnknownWhenTheSnapshotPredatesThem() {
        RetrievalEvaluation traced = ScriptedEvidence.tracedSnapshot();
        RetrievalEvidenceReport report = scripted.service().report(traced);
        assertThat(report.settings().chunkMaxChars()).isEqualTo(EvidenceValue.observed(4000, "snapshot properties.chunkMaxChars"));
        assertThat(report.settings().chunkOverlapChars()).isEqualTo(EvidenceValue.observed(500, "snapshot properties.chunkOverlapChars"));
        assertThat(report.settings().storeVersions())
                .isEqualTo(EvidenceValue.observed(List.of("sections-v2-context-v2-chunk4000-500"), "snapshot properties.storeVersions"));

        Map<String, Object> old = new LinkedHashMap<>(traced.properties());
        old.remove("chunkMaxChars");
        old.remove("chunkOverlapChars");
        old.remove("storeVersions");
        RetrievalEvidenceReport before = scripted.service().report(ScriptedEvidence.with(traced, old, traced.traces()));
        assertThat(before.settings().chunkMaxChars()).isEqualTo(EvidenceValue.unknown("snapshot records no properties.chunkMaxChars"));
        assertThat(before.settings().chunkOverlapChars()).isEqualTo(EvidenceValue.unknown("snapshot records no properties.chunkOverlapChars"));
        assertThat(before.settings().storeVersions()).isEqualTo(EvidenceValue.unknown("snapshot records no properties.storeVersions"));
        assertThat(before.settings().chunkMaxChars().value()).isNull();
        assertThat(before.questions()).hasSameSizeAs(report.questions());
        String markdown = scripted.service().markdown(before);
        assertThat(markdown).contains("| chunkMaxChars | unknown: snapshot records no properties.chunkMaxChars |")
                .contains("| storeVersions | unknown: snapshot records no properties.storeVersions |");
        assertThat(scripted.service().markdown(report)).contains("| chunkMaxChars | 4000 (observed [")
                .contains("| storeVersions | sections-v2-context-v2-chunk4000-500 (observed [");

        Map<String, Object> mixed = new LinkedHashMap<>(traced.properties());
        mixed.put("storeVersions", java.util.Arrays.asList("sections-v2-context-v2", null));
        RetrievalEvidenceReport twoVersions = scripted.service().report(ScriptedEvidence.with(traced, mixed, traced.traces()));
        assertThat(twoVersions.settings().storeVersions().value()).containsExactly("sections-v2-context-v2", null);
        assertThat(scripted.service().markdown(twoVersions)).contains("| storeVersions | sections-v2-context-v2, none (observed [");
    }

    @Test
    void tokenFieldsAreNeverComputedWithAnotherModelsTokenizerOrWithoutARecordedVersion() {
        RetrievalEvaluation traced = ScriptedEvidence.tracedSnapshot();
        RetrievalEvidenceReport other = scripted.service(Optional.of(new ScriptedWordTokenizer("0123456789ab"))).report(traced);
        String differs = "snapshot rerankerVersion " + ScriptedEvidence.VERSION + " differs from the loaded model version 0123456789ab";
        assertThat(chunk(question(other, "q1"), 0, 101).chunkTokens()).isEqualTo(EvidenceValue.unknown(differs));
        assertThat(chunk(question(other, "q1"), 0, 101).occurrences().get(0).head()).isEqualTo(EvidenceValue.unknown(differs));
        assertThat(question(other, "q1").queryTokens()).isEqualTo(EvidenceValue.unknown(differs));
        assertThat(other.settings().loadedModelVersion()).isEqualTo(EvidenceValue.observed("0123456789ab", RetrievalEvidenceService.SOURCE_LOADED_MODEL));

        Map<String, Object> noVersion = new LinkedHashMap<>(traced.properties());
        noVersion.remove("rerankerVersion");
        RetrievalEvidenceReport absent = scripted.service().report(ScriptedEvidence.with(traced, noVersion, traced.traces()));
        assertThat(absent.settings().rerankerVersion()).isEqualTo(EvidenceValue.unknown("snapshot records no properties.rerankerVersion"));
        assertThat(chunk(question(absent, "q1"), 0, 101).windowLength()).isEqualTo(EvidenceValue.unknown(RetrievalEvidenceService.NO_RERANKER_VERSION));
        noVersion.put("rerankerVersion", null);
        RetrievalEvidenceReport recordedNull = scripted.service().report(ScriptedEvidence.with(traced, noVersion, traced.traces()));
        assertThat(chunk(question(recordedNull, "q1"), 0, 101).windowLength()).isEqualTo(EvidenceValue.unknown(RetrievalEvidenceService.NO_RERANKER_VERSION));
    }

    @Test
    void windowFieldsNeedARecordedScoringWhileHeadMembershipDoesNot() {
        RetrievalEvaluation traced = ScriptedEvidence.tracedSnapshot();
        Map<String, Object> noScoring = new LinkedHashMap<>(traced.properties());
        noScoring.remove("rerankerScoring");
        RetrievalEvidenceReport absent = scripted.service().report(ScriptedEvidence.with(traced, noScoring, traced.traces()));
        ChunkEvidence c101 = chunk(question(absent, "q1"), 0, 101);
        assertThat(absent.settings().passageScoring()).isEqualTo(EvidenceValue.unknown(RetrievalEvidenceService.NO_RERANKER_SCORING));
        assertThat(c101.windowStarts()).isEqualTo(EvidenceValue.unknown(RetrievalEvidenceService.NO_RERANKER_SCORING));
        assertThat(c101.occurrences().get(0).windowsHoldingWholly()).isEqualTo(EvidenceValue.unknown(RetrievalEvidenceService.NO_RERANKER_SCORING));
        assertThat(c101.occurrences().get(0).head().value()).isEqualTo("partly");
        assertThat(c101.windowLength().value()).isEqualTo(14);

        noScoring.put("rerankerScoring", "max-window/overlap=64");
        RetrievalEvidenceReport unrecognised = scripted.service().report(ScriptedEvidence.with(traced, noScoring, traced.traces()));
        assertThat(chunk(question(unrecognised, "q1"), 0, 101).windowStarts())
                .isEqualTo(EvidenceValue.unknown("unrecognised rerankerScoring 'max-window/overlap=64'"));

        // Head scoring: one row at 0, so chunk 102's phrase is held by row 1 and chunk 201's (past the head) by none.
        noScoring.put("rerankerScoring", "head");
        RetrievalEvidenceReport head = scripted.service().report(ScriptedEvidence.with(traced, noScoring, traced.traces()));
        assertThat(head.settings().windowOverlapTokens()).isEqualTo(EvidenceValue.derived(null, RetrievalEvidenceService.RULE_SCORING_HEAD));
        assertThat(chunk(question(head, "q1"), 0, 101).windowStarts().value()).containsExactly(0);
        assertThat(chunk(question(head, "q1"), 0, 102).occurrences().get(0).windowsHoldingWholly().value()).containsExactly(1);
        assertThat(chunk(question(head, "q2"), 0, 201).occurrences().get(0).windowsHoldingWholly().value()).isEmpty();

        // Overlap 4 instead of 0: chunk 101's rows are 0, 10, 20, 26 and row 2 [10, 24) holds tokens 12..15.
        noScoring.put("rerankerScoring", "max-window/overlap=4/maxWindows=4");
        RetrievalEvidenceReport overlap = scripted.service().report(ScriptedEvidence.with(traced, noScoring, traced.traces()));
        assertThat(chunk(question(overlap, "q1"), 0, 101).windowStarts().value()).containsExactly(0, 10, 20, 26);
        assertThat(chunk(question(overlap, "q1"), 0, 101).occurrences().get(0).windowsHoldingWholly().value()).containsExactly(2);
    }

    @Test
    void aPhraseEndingPastTheInputBoundHasItsCharacterSpanButNoTokenPosition() {
        // The scorer tokenizes only the first 20,000 characters: a phrase starting at 19,995 ends past them.
        String longChunk = "x ".repeat(9_997) + "w030 w031 tail";
        assertThat(longChunk.indexOf("w030 w031")).isEqualTo(19_994);
        when(scripted.chunks.chunks(ScriptedEvidence.ACC2, "ITEM_1")).thenReturn(List.of(new EvidenceChunkRepository.StoredChunk(205, longChunk)));
        RetrievalEvidenceReport report = scripted.service().report(ScriptedEvidence.tracedSnapshot());
        ChunkEvidence chunk = chunk(question(report, "q2"), 0, 205);
        String reason = "phrase ends past the 20000-character input bound";
        // Bounded text: 9,997 "x" tokens (characters 0 to 19,993), "w030" (19,994 to 19,997), and the "w" of "w031" at 19,999.
        assertThat(chunk.chunkTokens().value()).isEqualTo(9_997 + 2);
        assertThat(chunk.occurrences()).singleElement().satisfies(o -> {
            assertThat(o.characterSpan().value()).isEqualTo(new Span(19_994, 20_003));
            assertThat(o.tokenSpan()).isEqualTo(EvidenceValue.unknown(reason));
            assertThat(o.head()).isEqualTo(EvidenceValue.unknown(reason));
            assertThat(o.windowsHoldingWholly()).isEqualTo(EvidenceValue.unknown(reason));
        });
    }

    @Test
    void rerankingOffRanksByFusedPositionWithNoScores() {
        RetrievalTrace off = new RetrievalTrace(3, 3, null, List.of(new FusedCandidate(301, 1, 1, 1, null), new FusedCandidate(101, 2, 2, null, null),
                new FusedCandidate(102, 3, 3, 2, null)), new RetrievalTrace.Rerank(Outcome.OFF, null, null, null, null), List.of(301L, 101L, 102L));
        RetrievalEvaluation traced = ScriptedEvidence.tracedSnapshot();
        RetrievalEvidenceReport report = scripted.service().report(ScriptedEvidence.with(traced, traced.properties(),
                List.of(new QuestionTrace("q1", off), traced.traces().get(1), traced.traces().get(2))));
        QuestionEvidence q1 = question(report, "q1");
        ChunkEvidence c101 = chunk(q1, 0, 101);
        assertThat(c101.rerankInput()).isEqualTo(EvidenceValue.observed(false, RetrievalEvidenceService.SOURCE_OFF));
        assertThat(c101.rerankedPosition()).isEqualTo(EvidenceValue.observed(null, RetrievalEvidenceService.SOURCE_OFF));
        assertThat(c101.returnedPosition().value()).isEqualTo(2);
        assertThat(q1.rerankInputCount()).isEqualTo(EvidenceValue.observed(null, "trace rerank inputCount (null: reranking off)"));
        assertThat(q1.ranking().value()).isEqualTo("fused order");
        assertThat(q1.bestAcceptedChunk().value()).isEqualTo(101L);
        assertThat(q1.bestAcceptedPosition()).isEqualTo(EvidenceValue.observed(2, "trace fused fusedPosition"));
        assertThat(q1.rankedAbove().value()).singleElement().satisfies(entry -> {
            assertThat(entry.chunkId()).isEqualTo(301L);
            assertThat(entry.score()).isEqualTo(EvidenceValue.observed(null, RetrievalEvidenceService.SOURCE_OFF));
        });
    }

    @Test
    void aRerankerThatReportsNoScoresLeavesUnreturnedPositionsUnknown() {
        RetrievalTrace noScores = new RetrievalTrace(4, 4, null, List.of(new FusedCandidate(301, 1, 1, 1, null), new FusedCandidate(302, 2, 2, 2, null),
                new FusedCandidate(101, 3, 3, 3, null)), new RetrievalTrace.Rerank(Outcome.RERANKED, null, 3, "reranker reports no scores", List.of(
                new RerankedCandidate(302, 2, 1, null, null, null), new RerankedCandidate(301, 1, 2, null, null, null),
                new RerankedCandidate(101, 3, null, null, null, null))), List.of(302L, 301L));
        RetrievalEvaluation traced = ScriptedEvidence.tracedSnapshot();
        RetrievalEvidenceReport report = scripted.service().report(ScriptedEvidence.with(traced, traced.properties(),
                List.of(new QuestionTrace("q1", noScores), traced.traces().get(1), traced.traces().get(2))));
        QuestionEvidence q1 = question(report, "q1");
        ChunkEvidence c101 = chunk(q1, 0, 101);
        assertThat(c101.rerankInput().value()).isTrue();
        assertThat(c101.rerankedPosition()).isEqualTo(EvidenceValue.unknown("reranker reports no scores"));
        assertThat(c101.score()).isEqualTo(EvidenceValue.unknown("reranker reports no scores"));
        String reason = "reranked positions not recorded for every rerank input: reranker reports no scores";
        assertThat(q1.bestAcceptedChunk()).isEqualTo(EvidenceValue.unknown(reason));
        assertThat(q1.rankedAbove()).isEqualTo(EvidenceValue.unknown(reason));
    }

    @Test
    void theSetIsTheOneTheSnapshotNamesAndAVersionMismatchWithholdsPhrases() {
        RetrievalEvaluation traced = ScriptedEvidence.tracedSnapshot();
        Map<String, Object> otherSet = new LinkedHashMap<>(traced.properties());
        otherSet.put("set", "evaluation/other.json");
        when(scripted.loader.load("evaluation/other.json")).thenReturn(new RetrievalEvaluationSet("v9", ScriptedEvidence.set().createdOn(),
                ScriptedEvidence.set().questions()));
        RetrievalEvidenceReport mismatch = scripted.service().report(ScriptedEvidence.with(traced, otherSet, traced.traces()));
        String reason = "bundled set evaluation/other.json is version v9 but the snapshot was evaluated on v-test";
        QuestionEvidence q1 = question(mismatch, "q1");
        assertThat(q1.acceptedPhraseCount()).isEqualTo(EvidenceValue.unknown(reason));
        assertThat(q1.phrases()).isEmpty();
        assertThat(q1.queryTokens()).isEqualTo(EvidenceValue.unknown(reason));
        assertThat(q1.bestAcceptedChunk()).isEqualTo(EvidenceValue.unknown("accepted phrases unknown: " + reason));
        assertThat(q1.rank().value()).isEqualTo(2);

        Map<String, Object> noSet = new LinkedHashMap<>(traced.properties());
        noSet.remove("set");
        when(scripted.loader.load(RetrievalEvaluationSetLoader.DEFAULT_RESOURCE)).thenReturn(new RetrievalEvaluationSet("v2", ScriptedEvidence.set().createdOn(), List.of()));
        when(scripted.loader.load(RetrievalEvaluationSetLoader.V1_RESOURCE)).thenReturn(ScriptedEvidence.set());
        RetrievalEvidenceReport byVersion = scripted.service().report(ScriptedEvidence.with(traced, noSet, traced.traces()));
        assertThat(byVersion.set()).isEqualTo(EvidenceValue.derived(RetrievalEvaluationSetLoader.V1_RESOURCE, RetrievalEvidenceService.RULE_SET_BY_VERSION));
        assertThat(question(byVersion, "q1").phrases()).hasSize(2);
    }

    @Test
    void everyValueCarriesAValidBasisAndInferredNeverAppears() {
        RetrievalEvidenceService service = scripted.service();
        for (RetrievalEvaluation snapshot : List.of(ScriptedEvidence.tracedSnapshot(), ScriptedEvidence.untracedSnapshot())) {
            String json = service.json(service.report(snapshot));
            assertThat(json).doesNotContainIgnoringCase("inferred");
            List<JsonNode> values = new ArrayList<>();
            collectValues(service.parse(json), values);
            assertThat(values).hasSizeGreaterThan(50).allSatisfy(value -> {
                String basis = value.get("basis").asString();
                assertThat(basis).isIn("observed", "derived", "unknown");
                switch (basis) {
                    case "observed" -> assertThat(value.has("source") && !value.has("rule") && !value.has("reason")).as(value.toString()).isTrue();
                    case "derived" -> assertThat(value.has("rule") && !value.has("source") && !value.has("reason")).as(value.toString()).isTrue();
                    default -> assertThat(value.get("value").isNull() && value.has("reason") && !value.has("source") && !value.has("rule"))
                            .as(value.toString()).isTrue();
                }
            });
        }
        assertThatThrownBy(() -> new EvidenceValue<>(1, Basis.UNKNOWN, null, null, "reason")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceValue<>(1, Basis.OBSERVED, null, "rule", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceValue<>(1, null, "source", null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    // Plan 2026-09-14-retrieval-recall, Milestone 3, D3: removals come only from the trace; a trace recorded before removals were traced
    // says unknown / no trace of removals, never an empty list or false.

    @Test
    void aTraceWithoutRemovalsReportsEveryRemovalFieldUnknownWithNoTraceOfRemovals() {
        RetrievalEvidenceReport report = scripted.service().report(ScriptedEvidence.tracedSnapshot());
        for (String id : List.of("q1", "q2")) {
            QuestionEvidence question = question(report, id);
            assertThat(question.removed()).as(id).isEqualTo(EvidenceValue.unknown(RetrievalEvidenceService.NO_TRACE_OF_REMOVALS));
            assertThat(question.acceptedChunkRemoved()).as(id).isEqualTo(EvidenceValue.unknown(RetrievalEvidenceService.NO_TRACE_OF_REMOVALS));
            question.phrases().forEach(phrase -> phrase.chunks().forEach(chunk ->
                    assertThat(chunk.removedRedundantWith()).isEqualTo(EvidenceValue.unknown(RetrievalEvidenceService.NO_TRACE_OF_REMOVALS))));
        }
        QuestionEvidence failed = question(report, "q3");
        assertThat(failed.removed()).isEqualTo(EvidenceValue.unknown(RetrievalEvidenceService.NO_TRACE_FOR_QUESTION));
        assertThat(failed.acceptedChunkRemoved()).isEqualTo(EvidenceValue.unknown(RetrievalEvidenceService.NO_TRACE_FOR_QUESTION));
    }

    @Test
    void recordedRemovalsAreObservedAndAnAcceptedChunkAmongThemIsMarked() {
        // q1 removed nothing; q2 removed chunk 201, which holds its accepted phrase, as redundant with chunk 202, and chunk 204, which holds none.
        RetrievalEvaluation traced = ScriptedEvidence.tracedSnapshot();
        RetrievalTrace q1 = withRemoved(traced.traces().get(0).trace(), List.of());
        List<RemovedCandidate> q2Removed = List.of(new RemovedCandidate(201, 2, 2, null, null, 202), new RemovedCandidate(204, 4, null, 3, null, 203));
        RetrievalTrace q2 = withRemoved(traced.traces().get(1).trace(), q2Removed);
        RetrievalEvidenceReport report = scripted.service().report(ScriptedEvidence.with(traced, traced.properties(),
                List.of(new QuestionTrace("q1", q1), new QuestionTrace("q2", q2), new QuestionTrace("q3", null))));

        QuestionEvidence first = question(report, "q1");
        assertThat(first.removed()).isEqualTo(EvidenceValue.observed(List.of(), RetrievalEvidenceService.SOURCE_REMOVED));
        assertThat(first.acceptedChunkRemoved()).isEqualTo(EvidenceValue.derived(false, RetrievalEvidenceService.RULE_ACCEPTED_REMOVED));
        assertThat(chunk(first, 0, 101).removedRedundantWith()).isEqualTo(EvidenceValue.observed(null, RetrievalEvidenceService.SOURCE_REDUNDANT_WITH));

        QuestionEvidence second = question(report, "q2");
        assertThat(second.removed()).isEqualTo(EvidenceValue.observed(q2Removed, RetrievalEvidenceService.SOURCE_REMOVED));
        assertThat(second.acceptedChunkRemoved()).isEqualTo(EvidenceValue.derived(true, RetrievalEvidenceService.RULE_ACCEPTED_REMOVED));
        assertThat(chunk(second, 0, 201).removedRedundantWith()).isEqualTo(EvidenceValue.observed(202L, RetrievalEvidenceService.SOURCE_REDUNDANT_WITH));

        // Only a removed chunk that holds an accepted phrase marks the question.
        RetrievalTrace unrelated = withRemoved(traced.traces().get(1).trace(), List.of(new RemovedCandidate(204, 3, null, 3, null, 203)));
        RetrievalEvidenceReport other = scripted.service().report(ScriptedEvidence.with(traced, traced.properties(),
                List.of(new QuestionTrace("q1", q1), new QuestionTrace("q2", unrelated), new QuestionTrace("q3", null))));
        assertThat(question(other, "q2").acceptedChunkRemoved().value()).isFalse();

        // The markdown lists the removals and marks the chunk.
        String markdown = scripted.service().markdown(report);
        assertThat(markdown).contains("removed: 2 chunks (observed [").contains("| 201 | 2 | 2 | none | none | 202 |")
                .contains("removed: 0 chunks (observed [").contains("| acceptedChunkRemoved | true (derived [");
    }

    @Test
    void theCommittedSnapshots598And694ReadBackWithRemovedNullAndReportNoTraceOfRemovals() throws Exception {
        for (String file : List.of("src/main/java/documentation/live-runs/2026-09-13-evaluation-evidence/measurement/snapshot-598-traced-snapshot-295-reference-rerank-off.json",
                "src/main/java/documentation/live-runs/2026-09-13-rag15-recall/snapshot-694-candidate-count-200-rerank-off.json")) {
            RetrievalEvaluation snapshot = readExport(java.nio.file.Path.of(file));
            assertThat(snapshot.traces()).as(file).hasSize(42);
            assertThat(snapshot.traces()).allSatisfy(trace -> assertThat(trace.trace().removed()).as(trace.id()).isNull());
            RetrievalEvidenceService service = new RetrievalEvidenceService(scripted.snapshots,
                    new RetrievalEvaluationSetLoader(new RetrievalEvaluationProperties()), scripted.chunks, scripted.crossEncoder, Optional.empty());
            RetrievalEvidenceReport report = service.report(snapshot);
            assertThat(report.questions()).hasSize(42).allSatisfy(question -> {
                assertThat(question.removed()).as(question.id()).isEqualTo(EvidenceValue.unknown(RetrievalEvidenceService.NO_TRACE_OF_REMOVALS));
                assertThat(question.acceptedChunkRemoved()).as(question.id()).isEqualTo(EvidenceValue.unknown(RetrievalEvidenceService.NO_TRACE_OF_REMOVALS));
            });
        }
    }

    /** A row_to_json export read as RetrievalEvaluationRepository's row mapper reads the row (as TraceReproductionFilesTests does). */
    private static RetrievalEvaluation readExport(java.nio.file.Path file) throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        JsonNode row = mapper.readTree(Files.readString(file));
        var stored = mapper.readValue(row.get("results").toString(), RetrievalEvaluationRepository.StoredResults.class);
        Map<String, Object> properties = mapper.readValue(row.get("properties").toString(), new tools.jackson.core.type.TypeReference<Map<String, Object>>() { });
        return new RetrievalEvaluation(row.get("id").asLong(), java.time.OffsetDateTime.parse(row.get("evaluated_at").asString()).toInstant(),
                row.get("set_version").asString(), row.get("question_count").asInt(), row.get("hit_at_1").decimalValue(), row.get("hit_at_3").decimalValue(),
                row.get("hit_at_5").decimalValue(), row.get("mrr").decimalValue(), row.get("window_size").asInt(), row.get("retrieval_strategy").asString(),
                properties, stored.questions(), stored.tickerHitAt5(), stored.misses(), stored.slices(), stored.traces());
    }

    private static RetrievalTrace withRemoved(RetrievalTrace trace, List<RemovedCandidate> removed) {
        return new RetrievalTrace(trace.vectorCandidates(), trace.keywordCandidates(), trace.figureCandidates(), trace.fused(), trace.rerank(),
                trace.returnedChunkIds(), removed);
    }

    private static void collectValues(JsonNode node, List<JsonNode> out) {
        if (node.isObject()) {
            if (node.has("basis")) out.add(node);
            node.forEach(child -> collectValues(child, out));
        } else if (node.isArray()) {
            node.forEach(child -> collectValues(child, out));
        }
    }

    private static void assertUnknown(JsonNode value, String reason, String field) {
        assertThat(value).as(field).isNotNull();
        assertThat(value.get("basis").asString()).as(field).isEqualTo("unknown");
        assertThat(value.get("reason").asString()).as(field).isEqualTo(reason);
        assertThat(value.get("value").isNull()).as(field).isTrue();
    }

    static QuestionEvidence question(RetrievalEvidenceReport report, String id) {
        return report.questions().stream().filter(q -> q.id().equals(id)).findFirst().orElseThrow();
    }

    static ChunkEvidence chunk(QuestionEvidence question, int phrase, long chunkId) {
        return question.phrases().get(phrase).chunks().stream().filter(c -> c.chunkId() == chunkId).findFirst().orElseThrow();
    }
}
