package project.stockrecommendationengine.rag.dto;

public record EmbeddedFilingChunk(
        FilingChunkData chunkData,
        float[] embedding
) {
}
