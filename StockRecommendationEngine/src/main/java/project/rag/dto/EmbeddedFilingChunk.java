package project.rag.dto;

public record EmbeddedFilingChunk(
        FilingChunkData chunkData,
        float[] embedding
) {
}
