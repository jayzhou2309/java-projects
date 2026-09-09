package project.rag.ingestion;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import project.rag.dto.EmbeddedFilingChunk;
import project.rag.dto.FilingChunkData;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FilingEmbeddingService {
    private final EmbeddingModel embeddingModel;

    public float[] embed(String content){
        if (content == null || content.isBlank()){
            throw new IllegalArgumentException("Content cannot be empty");
        }
        return embeddingModel.embed(content);
    }

    public List<EmbeddedFilingChunk> embedChunks(
            List<FilingChunkData> chunks
    ) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        return chunks.stream()
                .map(chunk ->
                        new EmbeddedFilingChunk(
                                chunk,
                                embed(chunk.content())
                        )
                )
                .toList();
    }
}
