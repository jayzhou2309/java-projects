package project.stockrecommendationengine.rag.ingestion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import project.stockrecommendationengine.rag.dto.EmbeddedFilingChunk;
import project.stockrecommendationengine.rag.dto.FilingChunkData;

import java.util.List;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
@Slf4j
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
        long started = System.nanoTime();
        List<EmbeddedFilingChunk> embedded = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            FilingChunkData chunk = chunks.get(i);
            long chunkStarted = System.nanoTime();
            log.info("Embedding chunk {}/{}: chunkIndex={}, section={}, characters={}",
                    i + 1, chunks.size(), chunk.chunkIndex(), chunk.sectionKey(), chunk.content().length());
            try {
                float[] vector = embed(chunk.content());
                embedded.add(new EmbeddedFilingChunk(chunk, vector));
                log.info("Embedded chunk {}/{}: dimensions={}, elapsedMs={}",
                        i + 1, chunks.size(), vector.length, (System.nanoTime() - chunkStarted) / 1_000_000);
            } catch (RuntimeException failure) {
                log.warn("Embedding chunk {}/{} failed: chunkIndex={}, elapsedMs={}",
                        i + 1, chunks.size(), chunk.chunkIndex(), (System.nanoTime() - chunkStarted) / 1_000_000);
                throw failure;
            }
        }
        log.info("Embedding batch completed: chunks={}, elapsedMs={}",
                embedded.size(), (System.nanoTime() - started) / 1_000_000);
        return List.copyOf(embedded);
    }
}
