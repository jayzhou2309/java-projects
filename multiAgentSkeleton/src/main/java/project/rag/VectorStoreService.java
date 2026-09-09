package project.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import project.model.DocumentChunk;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VectorStoreService {
    private final VectorStore vectorStore;

    public void storeChunk(DocumentChunk chunk){
        Document document = new Document(
            chunk.getContent(),
                    Map.of(
                            "chunkIndex", chunk.getChunkIndex()
                    )
        );
        vectorStore.add(List.of(document));
    }

    public void storeChunks(List<DocumentChunk> chunks){
        List<Document> documents = chunks.stream()
                .map(chunk -> new Document(
                        chunk.getContent(),
                        Map.of("chunkIndex", chunk.getChunkIndex())
                ))
                .toList();
        vectorStore.add(documents);
    }
}
