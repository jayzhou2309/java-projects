package project.stockrecommendationengine.rag.ingestion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.stockrecommendationengine.rag.dto.FilingChunkData;
import project.stockrecommendationengine.rag.dto.FilingSection;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chunk-size plan Milestone 2 (plan {@code 2026-09-17-chunk-size.md}): the chunker at its defaults reproduces the recorded pieces of a
 * fixed sample byte for byte (E1). The sample ({@code chunking/sample-sections.json}) is four sections of stored AAPL 10-K text; the
 * expected pieces ({@code chunking/expected-chunks-4000-500.json}) were recorded from the chunker before it took its size from
 * configuration (commit ab7a175, constants 4,000 and 500). Regenerate only for an intended change with
 * {@code -Drag.chunker.fixture.write=true}. No database, no model.
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
        assertThat(chunks).hasSize(expected.size()).allSatisfy(chunk -> assertThat(chunk.content().length()).isLessThanOrEqualTo(4000));
    }

    static List<FilingSection> sample() throws Exception {
        return JSON.readValue(Files.readString(SAMPLE), Sample.class).sections();
    }

    record Sample(String origin, List<FilingSection> sections) {
    }
}
