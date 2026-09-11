package project.stockrecommendationengine.rag.ingestion;

import org.junit.jupiter.api.Test;
import project.stockrecommendationengine.rag.dto.FilingSection;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FilingTextProcessingTests {
    private final FilingHtmlParser parser = new FilingHtmlParser();
    private final FilingChunker chunker = new FilingChunker();

    @Test
    void removesLinkedContentsTableButPreservesBodyTablesAndShortDisclosures() {
        var sections = parser.parse("""
                <table><tr><td><a href="#business">Item 1.</a></td><td>Business</td></tr>
                <tr><td><a href="#risk">Item 1A.</a></td><td>Risk Factors</td></tr></table>
                <div id="business">Item 1. Business</div><p>Actual business.</p>
                <table><tr><td>Revenue</td><td>100</td></tr></table>
                <div id="risk">Item 1A. Risk Factors</div><p>None.</p>
                """);
        assertThat(sections).containsExactly(
                new FilingSection("ITEM_1", "Business", "Actual business.\nRevenue\n100"),
                new FilingSection("ITEM_1A", "Risk Factors", "None."));
    }

    @Test
    void embeddingsIncludeHeadingWithoutChangingCitationText() {
        var model = org.mockito.Mockito.mock(org.springframework.ai.embedding.EmbeddingModel.class);
        org.mockito.Mockito.when(model.embed(org.mockito.ArgumentMatchers.anyString())).thenReturn(new float[]{1});
        var chunks = chunker.chunk(List.of(new FilingSection("ITEM_3", "Legal Proceedings", "None.")));
        var result = new FilingEmbeddingService(model).embedChunks(chunks);
        org.mockito.Mockito.verify(model).embed("ITEM_3 — Legal Proceedings\n\nNone.");
        assertThat(result.get(0).chunkData().content()).isEqualTo("None.");
    }

    @Test
    void readsDivsAndInlineSpansWithoutDuplicatingNestedTableParagraphs() {
        var sections = parser.parse("""
                <div><span>Item 1.</span> <span>Business</span></div>
                <div>Direct <span>inline</span> content.</div>
                <table><tr><td><p>First paragraph.</p><p>Second paragraph.</p></td></tr></table>
                <div>Item 1A. Risk Factors</div><div>Risk content.</div>
                """);
        assertThat(sections).containsExactly(
                new FilingSection("ITEM_1", "Business", "Direct inline content.\nFirst paragraph.\nSecond paragraph."),
                new FilingSection("ITEM_1A", "Risk Factors", "Risk content."));
    }

    @Test
    void preservesRepeatedTableValuesAndHandlesLineBreaks() {
        var sections = parser.parse("<span>Item 8. Financial Statements</span><br>"
                + "<table><tr><td>100</td><td>100</td></tr></table>");
        assertThat(sections).containsExactly(new FilingSection("ITEM_8", "Financial Statements", "100\n100"));
    }

    @Test
    void returnsNoSectionsWhenHeadingsCannotBeRecognized() {
        assertThat(parser.parse("<div>Only body text</div>")).isEmpty();
    }

    @Test
    void shortEarlySentenceDoesNotCauseRepeatedOverlapChunks() {
        String content = "Short sentence. " + "x".repeat(12000);
        var chunks = chunker.chunk(List.of(new FilingSection("ITEM_1", "Business", content)));
        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0).startChar()).isZero();
        for (int i = 0; i < chunks.size(); i++) {
            var chunk = chunks.get(i);
            assertThat(chunk.content()).isEqualTo(content.substring(chunk.startChar(), chunk.endChar()).trim());
            assertThat(chunk.content().length()).isLessThanOrEqualTo(4000);
            if (i > 0) {
                var previous = chunks.get(i - 1);
                assertThat(chunk.startChar()).isEqualTo(previous.endChar() - 500);
                assertThat(chunk.endChar()).isGreaterThan(previous.endChar());
            }
        }
        assertThat(chunks.get(chunks.size() - 1).endChar()).isEqualTo(content.length());
    }

    @Test
    void paragraphBoundaryInsideOverlapDoesNotStallProgress() {
        String content = "Opening\n\n" + "word ".repeat(2400);
        var chunks = chunker.chunk(List.of(new FilingSection("ITEM_1", "Business", content)));
        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(chunks.size() - 1).endChar()).isEqualTo(content.length());
    }
}
