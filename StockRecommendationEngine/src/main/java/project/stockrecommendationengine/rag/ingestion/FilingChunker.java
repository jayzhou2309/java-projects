package project.stockrecommendationengine.rag.ingestion;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import project.stockrecommendationengine.rag.dto.FilingChunkData;
import project.stockrecommendationengine.rag.dto.FilingSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts parsed filing sections into overlapping chunks that never cross a section boundary. The size and overlap come from
 * {@link FilingIngestionProperties} ({@code rag.ingestion.chunk-max-chars}, {@code chunk-overlap-chars}; 4,000 and 500 by default) and
 * every piece carries its section key and title, its index within the section, its character offsets within the section, and an
 * estimated token count. {@link #chunk(List, int, int)} applies the same rule at an explicit size and overlap, which the
 * answer-visibility diagnostic uses to re-split stored sections in memory.
 */
@Component
public class FilingChunker {

    private final int maxChars;
    private final int overlapChars;

    @Autowired
    public FilingChunker(FilingIngestionProperties properties) {
        this(properties.getChunkMaxChars(), properties.getChunkOverlapChars());
    }

    /** The configured defaults (4,000 / 500). */
    public FilingChunker() {
        this(new FilingIngestionProperties());
    }

    /** An explicit size and overlap under the bounds of {@link FilingIngestionProperties}: 100 to 20,000, overlap 0 to half the size. */
    public FilingChunker(int maxChars, int overlapChars) {
        requireValid(maxChars, overlapChars);
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
    }

    public int maxChars() {
        return maxChars;
    }

    public int overlapChars() {
        return overlapChars;
    }

    public List<FilingChunkData> chunk(
            List<FilingSection> sections
    ) {
        return chunk(sections, maxChars, overlapChars);
    }

    /** The same rule at an explicit size and overlap (validated as the constructor validates them). */
    public List<FilingChunkData> chunk(
            List<FilingSection> sections,
            int maxChars,
            int overlapChars
    ) {
        requireValid(maxChars, overlapChars);
        List<FilingChunkData> chunks = new ArrayList<>();
        int globalChunkIndex = 0;
        for (FilingSection section : sections) {
            String content = section.content();
            if (content == null || content.isBlank()) {
                continue;
            }

            int sectionChunkIndex = 0;
            int start = 0;

            while (start < content.length()) {
                int end = Math.min(
                        start + maxChars,
                        content.length()
                );
                end = adjustEndBoundary(
                        content,
                        start,
                        end,
                        maxChars
                );
                String chunkText = content
                        .substring(start, end)
                        .trim();
                if (!chunkText.isBlank()) {
                    chunks.add(
                            new FilingChunkData(
                                    globalChunkIndex,
                                    section.sectionKey(),
                                    section.sectionTitle(),
                                    sectionChunkIndex,
                                    chunkText,
                                    start,
                                    end,
                                    estimateTokenCount(chunkText)
                            )
                    );
                    globalChunkIndex++;
                    sectionChunkIndex++;
                }
                if (end >= content.length()) {
                    break;
                }
                start = Math.max(
                        end - overlapChars,
                        start + 1
                );
            }
        }
        return chunks;
    }

    private static void requireValid(int maxChars, int overlapChars) {
        if (maxChars < 100 || maxChars > 20_000) {
            throw new IllegalArgumentException("chunk size must be between 100 and 20000 characters: " + maxChars);
        }
        if (overlapChars < 0 || overlapChars > maxChars / 2) {
            throw new IllegalArgumentException("chunk overlap must be between 0 and half the chunk size (" + maxChars / 2 + "): " + overlapChars);
        }
    }

    private static int adjustEndBoundary(
            String content,
            int start,
            int end,
            int maxChars
    ) {
        if (end >= content.length()) {
            return content.length();
        }

        // Prefer boundaries in the latter half of the window, beyond the overlap.
        int minimumEnd = start + maxChars / 2;
        int paragraphBoundary =
                content.lastIndexOf("\n\n", end);
        if (paragraphBoundary >= minimumEnd) {
            return paragraphBoundary;
        }
        int sentenceBoundary =
                content.lastIndexOf(". ", end - 1);
        if (sentenceBoundary >= minimumEnd) {
            return sentenceBoundary + 1;
        }
        int whitespaceBoundary =
                content.lastIndexOf(" ", end);
        if (whitespaceBoundary >= minimumEnd) {
            return whitespaceBoundary;
        }
        return end;
    }

    private static int estimateTokenCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) Math.ceil(
                text.length() / 4.0
        );
    }
}
