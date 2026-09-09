package project.ragdemo.recommendation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import project.ragdemo.monitoring.RunMonitor;
import project.ragdemo.rag.RagRetrievalService;
import project.ragdemo.research.EvidenceItem;
import project.ragdemo.research.ResearchRequest;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecommendationBaselineTest {
    private final RagRetrievalService retrieval = mock(RagRetrievalService.class);
    private final RecommendationGenerator generator = mock(RecommendationGenerator.class);
    private final RecommendationValidator validator = new RecommendationValidator();
    private final RunMonitor monitor = new RunMonitor();
    private final RecommendationServiceImpl service = new RecommendationServiceImpl(retrieval, generator, validator, monitor);
    private final Properties cases = new Properties();
    private EvidenceItem item;

    @BeforeEach
    void loadFixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/evaluation/baseline.properties")) {
            assertNotNull(input);
            cases.load(input);
        }
        item = new EvidenceItem(cases.getProperty("evidence.id"), cases.getProperty("evidence.symbol"),
                cases.getProperty("evidence.url"), cases.getProperty("evidence.type"),
                cases.getProperty("evidence.date"), cases.getProperty("evidence.text"));
    }

    @Test
    void explicitEvidenceDoesNotRetrieveAgain() {
        String query = cases.getProperty("supported.query");
        var expected = completed(List.of(item.sourceUrl()), "N/A");
        when(generator.generate(query, List.of(item))).thenReturn(expected);
        assertEquals(expected, service.generateFromEvidence(query, List.of(item)));
        verify(generator).generate(query, List.of(item));
        verifyNoInteractions(retrieval);
    }

    @Test
    void emptyEvidenceSkipsModel() {
        var response = service.generateFromEvidence(cases.getProperty("missing.query"), List.of());
        assertEquals(RecommendationStatus.INSUFFICIENT_EVIDENCE, response.status());
        assertEquals("N/A", response.takeProfit());
        assertTrue(response.sources().isEmpty());
        verifyNoInteractions(generator, retrieval);
    }

    @Test
    void unrelatedAndInsufficientAnswersKeepStructuredSchema() {
        for (var status : List.of(RecommendationStatus.UNRELATED_TOPIC, RecommendationStatus.INSUFFICIENT_EVIDENCE)) {
            String query = cases.getProperty(status == RecommendationStatus.UNRELATED_TOPIC ? "unrelated.query" : "missing.query");
            var expected = RecommendationResponse.outcome(status, "No supported recommendation.");
            when(generator.generate(query, List.of(item))).thenReturn(expected);
            assertEquals(expected, service.generateFromEvidence(query, List.of(item)));
        }
    }

    @Test
    void fabricatedSourcesAndTargetsFailClosed() {
        String query = cases.getProperty("supported.query");
        for (var invalid : List.of(completed(List.of("https://invented.test"), "N/A"),
                completed(List.of(item.sourceUrl()), "100"))) {
            when(generator.generate(query, List.of(item))).thenReturn(invalid);
            var response = service.generateFromEvidence(query, List.of(item));
            assertEquals(RecommendationStatus.FAILED, response.status());
            assertTrue(response.sources().isEmpty());
            assertEquals("N/A", response.takeProfit());
        }
    }

    @Test
    void nullAndMalformedResponsesAreRejected() {
        assertFalse(validator.validate(null, List.of(item)).isEmpty());
        var malformed = new RecommendationResponse("", Double.NaN, "N/A", "N/A", "", null, null);
        assertFalse(validator.validate(malformed, List.of(item)).isEmpty());
        assertFalse(validator.validate(completed(List.of(), "N/A"), List.of(item)).isEmpty());
    }

    @Test
    void pipelineUsesExactlyRetrievedEvidenceAndRecordsOutcome() {
        String query = cases.getProperty("supported.query");
        Document document = new Document(item.text(), Map.of("symbol", item.symbol(),
                "sourceUrl", item.sourceUrl(), "filingType", item.filingType(), "filedDate", item.filedDate()));
        var evidence = List.of(EvidenceItem.fromDocument(document));
        var expected = completed(List.of(item.sourceUrl()), "N/A");
        when(retrieval.retrival(query)).thenReturn(List.of(document));
        when(generator.generate(query, evidence)).thenReturn(expected);
        assertEquals(expected, service.generateRecommendation(query));
        verify(retrieval, times(1)).retrival(query);
        verify(generator).generate(query, evidence);
        var events = monitor.snapshot().get(0).events();
        assertEquals("COMPLETED", events.get(events.size() - 1).status());
    }

    @Test
    void providerFailureRemainsVisibleAndPropagates() {
        when(retrieval.retrival("question")).thenThrow(new IllegalStateException("provider detail"));
        assertThrows(IllegalStateException.class, () -> service.generateRecommendation("question"));
        var events = monitor.snapshot().get(0).events();
        assertEquals("FAILED", events.get(events.size() - 1).status());
        assertFalse(events.toString().contains("provider detail"));
    }

    @Test
    void requestRejectsBlankQueryAndReversedDates() {
        assertThrows(IllegalArgumentException.class, () -> new ResearchRequest(" ", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ResearchRequest("q", null,
                java.time.LocalDate.of(2026, 2, 1), java.time.LocalDate.of(2026, 1, 1)));
        assertEquals("q", new ResearchRequest(" q ", null, null, null).query());
    }

    private RecommendationResponse completed(List<String> sources, String target) {
        return new RecommendationResponse("Review supply-chain exposure", 0.5, target, "N/A",
                "Supplier interruptions may adversely affect operations.", sources, RecommendationStatus.COMPLETED);
    }
}
