package project.stockrecommendationengine.rag.evaluation;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import project.stockrecommendationengine.rag.controller.RetrievalEvaluationController;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@code GET /api/rag/evaluate/{id}/evidence} over the scripted report (evaluation evidence Milestone 2): JSON by default and with
 * {@code format=json}, markdown with {@code format=markdown} equal to the rendering of that JSON, 404 for an unknown snapshot, 400 for
 * another format, and an answer (with token fields unknown) when no tokenizer bean exists (D4). Token gating is in IntegrationAccessTests.
 */
class RetrievalEvidenceEndpointTests {
    private final ScriptedEvidence scripted = new ScriptedEvidence();

    private MockMvc mvc(RetrievalEvidenceService evidence) {
        return MockMvcBuilders.standaloneSetup(new RetrievalEvaluationController(mock(RetrievalEvaluationService.class), scripted.snapshots, evidence)).build();
    }

    @Test
    void answersJsonAndTheMarkdownRenderingOfTheSameJson() throws Exception {
        when(scripted.snapshots.findById(459L)).thenReturn(Optional.of(ScriptedEvidence.tracedSnapshot()));
        RetrievalEvidenceService evidence = scripted.service();
        MockMvc mvc = mvc(evidence);
        MvcResult json = mvc.perform(get("/api/rag/evaluate/459/evidence")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.snapshotId").value(459))
                .andExpect(jsonPath("$.questions[0].phrases[0].chunks[0].occurrences[0].tokenSpan.value.start").value(12))
                .andExpect(jsonPath("$.questions[0].phrases[0].chunks[0].occurrences[0].head.value").value("partly"))
                .andReturn();
        assertThat(evidence.parse(json.getResponse().getContentAsString())).isEqualTo(evidence.parse(evidence.json(evidence.report(459L).orElseThrow())));
        mvc.perform(get("/api/rag/evaluate/459/evidence").param("format", "json")).andExpect(status().isOk()).andExpect(jsonPath("$.snapshotId").value(459));
        MvcResult markdown = mvc.perform(get("/api/rag/evaluate/459/evidence").param("format", "markdown")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/markdown"))).andReturn();
        assertThat(markdown.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(EvidenceMarkdownRenderer.render(evidence.parse(json.getResponse().getContentAsString())));
    }

    @Test
    void unknownSnapshotIs404AndAnotherFormatIs400() throws Exception {
        when(scripted.snapshots.findById(anyLong())).thenReturn(Optional.empty());
        MockMvc mvc = mvc(scripted.service());
        mvc.perform(get("/api/rag/evaluate/999/evidence")).andExpect(status().isNotFound());
        mvc.perform(get("/api/rag/evaluate/999/evidence").param("format", "markdown")).andExpect(status().isNotFound());
        mvc.perform(get("/api/rag/evaluate/459/evidence").param("format", "html")).andExpect(status().isBadRequest());
        verify(scripted.snapshots, times(2)).findById(999L);
        verifyNoMoreInteractions(scripted.snapshots);
    }

    @Test
    void withoutTheTokenizerBeanTheEndpointStillAnswers() throws Exception {
        when(scripted.snapshots.findById(459L)).thenReturn(Optional.of(ScriptedEvidence.tracedSnapshot()));
        MockMvc mvc = mvc(scripted.service(Optional.empty()));
        mvc.perform(get("/api/rag/evaluate/459/evidence")).andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.loadedModelVersion.reason").value("tokenizer unavailable"))
                .andExpect(jsonPath("$.questions[0].phrases[0].chunks[0].chunkTokens.basis").value("unknown"))
                .andExpect(jsonPath("$.questions[0].phrases[0].chunks[0].chunkTokens.reason").value("tokenizer unavailable"))
                .andExpect(jsonPath("$.questions[0].phrases[0].chunks[0].rerankedPosition.value").value(3));
        mvc.perform(get("/api/rag/evaluate/459/evidence").param("format", "markdown")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("| 101 | unknown: tokenizer unavailable |")));
    }
}
