package project.stockrecommendationengine.rag.ingestion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import project.stockrecommendationengine.rag.dto.FilingChunkData;
import project.stockrecommendationengine.rag.dto.FilingSection;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Chunk-size plan Milestone 2 (plan {@code 2026-09-17-chunk-size.md}). The sample ({@code chunking/sample-sections.json}) is four
 * sections of stored AAPL 10-K text (20,417, 2,675, 497, and 56 characters). E1: the chunker at its defaults reproduces the pieces
 * recorded from it before it took its size from configuration ({@code chunking/expected-chunks-4000-500.json}, written at commit
 * ab7a175 with the constants 4,000 and 500; regenerate only for an intended change with {@code -Drag.chunker.fixture.write=true}).
 * E2: at 1,000 / 125 (and the diagnostic's other sizes) the pieces equal those of the answer-visibility diagnostic's former test-local
 * replica of the rule, kept here verbatim as {@link #replicaSplit} from the version the diagnostic used until 2026-09-17. E5: at
 * 1,000 / 125 no piece spans two sections and every piece carries the section key, title, section index, offsets, and token count.
 * The size tests: no piece longer than the size, the configured overlap, no empty piece, boundaries at half the size or later.
 * No database, no model.
 */
class FilingChunkerTests {
    static final Path SAMPLE = Path.of("src/test/resources/chunking/sample-sections.json");
    static final Path EXPECTED_DEFAULT = Path.of("src/test/resources/chunking/expected-chunks-4000-500.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void theDefaultChunkerReproducesTheRecordedPiecesOfTheSample() throws Exception {
        List<FilingChunkData> chunks = new FilingChunker().chunk(sample());
        if (Boolean.getBoolean("rag.chunker.fixture.write")) {
            Files.writeString(EXPECTED_DEFAULT, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(chunks) + "\n");
        }
        List<FilingChunkData> expected = JSON.readValue(Files.readString(EXPECTED_DEFAULT), new TypeReference<List<FilingChunkData>>() { });
        assertThat(chunks).as("regenerate with -Drag.chunker.fixture.write=true only for an intended change").isEqualTo(expected);
        assertThat(chunks).hasSize(9).allSatisfy(chunk -> assertThat(chunk.content().length()).isLessThanOrEqualTo(4000));
        assertThat(new FilingChunker(4000, 500).chunk(sample())).isEqualTo(expected);
        assertThat(new FilingChunker().chunk(sample(), 4000, 500)).isEqualTo(expected);
    }

    @Test
    void theDefaultsComeFromThePropertiesAndAreFourThousandAndFiveHundred() {
        FilingIngestionProperties properties = new FilingIngestionProperties();
        assertThat(properties.getChunkMaxChars()).isEqualTo(4000);
        assertThat(properties.getChunkOverlapChars()).isEqualTo(500);
        FilingChunker chunker = new FilingChunker(properties);
        assertThat(chunker.maxChars()).isEqualTo(4000);
        assertThat(chunker.overlapChars()).isEqualTo(500);
        properties.setChunkMaxChars(1000);
        properties.setChunkOverlapChars(125);
        assertThat(new FilingChunker(properties).maxChars()).isEqualTo(1000);
        assertThat(new FilingChunker(properties).overlapChars()).isEqualTo(125);
    }

    /** E2: piece by piece, the text equals the former replica's, the offsets locate it in its section, and the indexes count in order. */
    @ParameterizedTest
    @CsvSource({"1000, 125", "2000, 250", "500, 62", "4000, 500"})
    void thePiecesEqualTheDiagnosticsFormerReplicaAtEverySizeItUsed(int size, int overlap) throws Exception {
        List<FilingSection> sections = sample();
        List<FilingChunkData> chunks = new FilingChunker(size, overlap).chunk(sections);
        int next = 0;
        for (FilingSection section : sections) {
            List<String> replica = replicaSplit(section.content(), size, overlap);
            assertThat(replica).as(section.sectionKey() + " at " + size).isNotEmpty();
            for (int index = 0; index < replica.size(); index++, next++) {
                FilingChunkData chunk = chunks.get(next);
                String at = section.sectionKey() + " piece " + index + " at " + size;
                assertThat(chunk.content()).as(at + " text").isEqualTo(replica.get(index));
                assertThat(section.content().substring(chunk.startChar(), chunk.endChar()).trim()).as(at + " offsets").isEqualTo(chunk.content());
                assertThat(chunk.sectionKey()).as(at + " section").isEqualTo(section.sectionKey());
                assertThat(chunk.sectionChunkIndex()).as(at + " section index").isEqualTo(index);
                assertThat(chunk.chunkIndex()).as(at + " chunk index").isEqualTo(next);
            }
        }
        assertThat(chunks).as("no piece beyond the replica's at " + size).hasSize(next);
        assertThat(chunks.size()).as("more than one piece per long section at " + size).isGreaterThan(size < 4000 ? 9 : 8);
    }

    /** E5 at 1,000 / 125: every piece lies inside one section and carries that section's metadata as the stored chunks do. */
    @Test
    void everyPieceStaysInsideOneSectionAndCarriesItsMetadataAtOneThousand() throws Exception {
        List<FilingSection> sections = new ArrayList<>(sample());
        sections.add(1, new FilingSection("ITEM_1B", "Unresolved Staff Comments", ""));
        sections.add(new FilingSection("ITEM_9", "Changes in and Disagreements", "   \n\n "));
        List<FilingChunkData> chunks = new FilingChunker(1000, 125).chunk(sections);
        assertThat(chunks).isNotEmpty();
        int next = 0;
        for (FilingSection section : sections) {
            int sectionIndex = 0;
            while (next < chunks.size() && chunks.get(next).sectionKey().equals(section.sectionKey())) {
                FilingChunkData chunk = chunks.get(next);
                String at = section.sectionKey() + " piece " + sectionIndex;
                assertThat(chunk.sectionTitle()).as(at + " title").isEqualTo(section.sectionTitle());
                assertThat(chunk.sectionChunkIndex()).as(at + " section index").isEqualTo(sectionIndex);
                assertThat(chunk.chunkIndex()).as(at + " chunk index").isEqualTo(next);
                assertThat(chunk.startChar()).as(at + " start").isBetween(0, section.content().length() - 1);
                assertThat(chunk.endChar()).as(at + " end").isBetween(chunk.startChar() + 1, section.content().length());
                assertThat(section.content().substring(chunk.startChar(), chunk.endChar()).trim()).as(at + " lies in its section").isEqualTo(chunk.content());
                assertThat(chunk.tokenCount()).as(at + " tokens").isEqualTo((int) Math.ceil(chunk.content().length() / 4.0));
                sectionIndex++;
                next++;
            }
            assertThat(sectionIndex > 0).as(section.sectionKey() + " has pieces exactly when it has text").isEqualTo(!section.content().isBlank());
            if (sectionIndex > 0) assertThat(chunks.get(next - 1).endChar()).as(section.sectionKey() + " last piece ends the section").isEqualTo(section.content().length());
        }
        assertThat(next).as("every piece belongs to a section, in section order").isEqualTo(chunks.size());
        assertThat(chunks.stream().map(FilingChunkData::sectionKey).distinct()).containsExactly("ITEM_7", "ITEM_1C", "ITEM_2", "ITEM_4");
    }

    @ParameterizedTest
    @CsvSource({"1000, 125", "500, 62"})
    void piecesRespectTheSizeTheOverlapAndTheHalfSizeMinimum(int size, int overlap) throws Exception {
        List<FilingSection> sections = sample();
        List<FilingChunkData> chunks = new FilingChunker(size, overlap).chunk(sections);
        assertThat(chunks.size()).isGreaterThan(9);
        for (int index = 0; index < chunks.size(); index++) {
            FilingChunkData chunk = chunks.get(index);
            String at = chunk.sectionKey() + " piece " + chunk.sectionChunkIndex() + " at " + size;
            assertThat(chunk.content()).as(at + " not empty").isNotBlank();
            assertThat(chunk.content().length()).as(at + " within the size").isLessThanOrEqualTo(size);
            assertThat(chunk.endChar() - chunk.startChar()).as(at + " window within the size").isLessThanOrEqualTo(size);
            boolean last = index + 1 == chunks.size() || !chunks.get(index + 1).sectionKey().equals(chunk.sectionKey());
            if (!last) {
                FilingChunkData following = chunks.get(index + 1);
                assertThat(chunk.endChar() - chunk.startChar()).as(at + " boundary at half the size or later").isGreaterThanOrEqualTo(size / 2);
                assertThat(following.startChar()).as(at + " overlap as configured").isEqualTo(Math.max(chunk.endChar() - overlap, chunk.startChar() + 1));
                assertThat(following.endChar()).as(at + " progress").isGreaterThan(chunk.endChar());
            }
        }
    }

    @Test
    void sizeAndOverlapOutsideThePropertyBoundsAreRejected() {
        assertThatThrownBy(() -> new FilingChunker(99, 0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("100 and 20000");
        assertThatThrownBy(() -> new FilingChunker(20001, 0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("100 and 20000");
        assertThatThrownBy(() -> new FilingChunker(1000, 501)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("half the chunk size (500)");
        assertThatThrownBy(() -> new FilingChunker(1000, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FilingChunker().chunk(List.of(), 1000, 501)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new FilingChunker(1000, 500).overlapChars()).isEqualTo(500);
        assertThat(new FilingChunker(100, 0).maxChars()).isEqualTo(100);
        assertThat(new FilingChunker(20000, 10000).maxChars()).isEqualTo(20000);
    }

    static List<FilingSection> sample() throws Exception {
        return JSON.readValue(Files.readString(SAMPLE), Sample.class).sections();
    }

    record Sample(String origin, List<FilingSection> sections) {
    }

    /**
     * The answer-visibility diagnostic's test-local splitter as it read until 2026-09-17 (CrossEncoderAnswerVisibilityLiveTests at
     * commit ab7a175, {@code split}), kept verbatim as the reference E2 compares the real chunker against. Not used by any production
     * or diagnostic code.
     */
    static List<String> replicaSplit(String text, int size, int overlap) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + size, text.length());
            if (end < text.length()) {
                int minimumEnd = start + size / 2;
                int paragraph = text.lastIndexOf("\n\n", end);
                int sentence = text.lastIndexOf(". ", end - 1);
                int word = text.lastIndexOf(' ', end);
                if (paragraph >= minimumEnd) end = paragraph;
                else if (sentence >= minimumEnd) end = sentence + 1;
                else if (word >= minimumEnd) end = word;
            }
            String piece = text.substring(start, end).trim();
            if (!piece.isBlank()) pieces.add(piece);
            if (end >= text.length()) break;
            start = Math.max(end - overlap, start + 1);
        }
        return pieces;
    }
}
