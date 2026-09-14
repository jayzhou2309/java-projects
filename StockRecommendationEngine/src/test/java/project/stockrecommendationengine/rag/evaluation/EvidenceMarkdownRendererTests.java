package project.stockrecommendationengine.rag.evaluation;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/**
 * Evaluation evidence Milestone 2, D5: the markdown is generated from the report's JSON only. A fixed report (the committed JSON fixture,
 * itself checked against the scripted service output by RetrievalEvidenceServiceTests) renders exactly the committed markdown, the
 * service's markdown is the rendering of its own JSON, and a value changed in the JSON alone changes the markdown.
 */
class EvidenceMarkdownRendererTests {
    private final ScriptedEvidence scripted = new ScriptedEvidence();

    @Test
    void theCommittedReportRendersExactlyTheCommittedMarkdown() throws Exception {
        JsonNode report = scripted.service().parse(Files.readString(ScriptedEvidence.FIXTURE_JSON));
        assertThat(EvidenceMarkdownRenderer.render(report)).as("regenerate with -Drag.evidence.fixture.write=true after an intended change")
                .isEqualTo(Files.readString(ScriptedEvidence.FIXTURE_MARKDOWN));
    }

    @Test
    void theServicesMarkdownIsTheRenderingOfItsJson() {
        RetrievalEvidenceService service = scripted.service();
        for (RetrievalEvaluation snapshot : new RetrievalEvaluation[] {ScriptedEvidence.tracedSnapshot(), ScriptedEvidence.untracedSnapshot()}) {
            RetrievalEvidenceReport report = service.report(snapshot);
            assertThat(service.markdown(report)).isEqualTo(EvidenceMarkdownRenderer.render(service.parse(service.json(report))));
        }
    }

    @Test
    void aValueChangedInTheJsonAloneChangesTheMarkdown() throws Exception {
        JsonNode report = scripted.service().parse(Files.readString(ScriptedEvidence.FIXTURE_JSON));
        String before = EvidenceMarkdownRenderer.render(report);
        assertThat(before).contains("| rank | 2 (observed [12]) |").contains("| 101 | 1 (observed [31]) | true (observed [32]) | 3 (observed [33])");
        ObjectNode rank = (ObjectNode) report.at("/questions/0/rank");
        rank.put("value", 7);
        ObjectNode score = (ObjectNode) report.at("/questions/0/phrases/0/chunks/0/rerankedPosition");
        score.putNull("value");
        score.put("basis", "unknown");
        score.remove("source");
        score.put("reason", "edited in the JSON");
        String after = EvidenceMarkdownRenderer.render(report);
        assertThat(after).contains("| rank | 7 (observed [12]) |").contains("| 101 | 1 (observed [31]) | true (observed [32]) | unknown: edited in the JSON |")
                .doesNotContain("| rank | 2 (observed");
    }

    @Test
    void cellsStayOnOneTableLine() {
        JsonNode report = scripted.service().parse("""
                {"snapshotId": 1, "setVersion": {"value": "a|b", "basis": "observed", "source": "line\\nbreak"}, "settings": {}, "questions": []}
                """);
        String markdown = EvidenceMarkdownRenderer.render(report);
        assertThat(markdown).contains("| setVersion | a\\|b (observed [1]) |").contains("1. observed: line break").contains("| rerank | absent |");
    }
}
