package project.rag.ingestion;

import org.springframework.stereotype.Component;
import project.rag.dto.FilingChunkData;
import project.rag.dto.FilingSection;

import java.util.ArrayList;
import java.util.List;

@Component
public class FilingChunker {

    private static final int MAX_CHARS = 4000;
    private static final int OVERLAP_CHARS = 500;

    public List<FilingChunkData> chunk(
            List<FilingSection> sections
    ) {
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
                        start + MAX_CHARS,
                        content.length()
                );
                end = adjustEndBoundary(
                        content,
                        start,
                        end
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
                        end - OVERLAP_CHARS,
                        start + 1
                );
            }
        }
        return chunks;
    }

    private int adjustEndBoundary(
            String content,
            int start,
            int end
    ) {
        if (end >= content.length()) {
            return content.length();
        }

        int paragraphBoundary =
                content.lastIndexOf("\n\n", end);
        if (paragraphBoundary > start) {
            return paragraphBoundary;
        }
        int sentenceBoundary =
                content.lastIndexOf(". ", end);
        if (sentenceBoundary > start) {
            return sentenceBoundary + 1;
        }
        int whitespaceBoundary =
                content.lastIndexOf(" ", end);
        if (whitespaceBoundary > start) {
            return whitespaceBoundary;
        }
        return end;
    }

    private int estimateTokenCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) Math.ceil(
                text.length() / 4.0
        );
    }
}