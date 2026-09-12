package project.stockrecommendationengine.rag.evaluation;

/**
 * One passage that answers an evaluation question: a verbatim excerpt of a chunk stored for the given filing and
 * section. Chunk IDs change on rebuild, so the passage is identified by content, never by ID.
 */
public record ExpectedPassage(String accessionNo, String sectionKey, String phrase) {
}
