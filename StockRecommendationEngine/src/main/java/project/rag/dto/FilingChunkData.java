package project.rag.dto;

public record FilingChunkData(
        Integer chunkIndex,
        String sectionKey,
        String sectionTitle,
        Integer sectionChunkIndex,
        String content,
        Integer startChar,
        Integer endChar,
        Integer tokenCount
) {
}
