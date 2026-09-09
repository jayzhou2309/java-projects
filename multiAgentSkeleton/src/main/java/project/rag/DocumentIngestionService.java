package project.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.model.DocumentChunk;

import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DocumentIngestionService {
    private final DocumentLoaderService documentLoaderService;
    private final DocumentChunkingService documentChunkingService;
    private final VectorStoreService vectorStoreService;

    public void ingestDocument(String filePath) throws IOException {
        String documentText = documentLoaderService.loadDocument(filePath);
        List<DocumentChunk> chunks = documentChunkingService.chunkDocument(documentText);
        vectorStoreService.storeChunks(chunks);
    }
}
