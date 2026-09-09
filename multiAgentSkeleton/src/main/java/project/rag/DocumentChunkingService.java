package project.rag;

import org.springframework.stereotype.Service;
import project.model.DocumentChunk;

import java.util.ArrayList;
import java.util.List;

@Service
public class DocumentChunkingService {
    private static final int CHUNK_SIZE = 500;

    public List<DocumentChunk> chunkDocument(String documentText){
        List<DocumentChunk> chunks = new ArrayList<>();

        int chunkIndex = 0;

        for (int start = 0; start < documentText.length(); start += CHUNK_SIZE){
            int end = Math.min(
                    start + CHUNK_SIZE, documentText.length()
            );
            String chunkText = documentText.substring(start, end);
            DocumentChunk chunk = new DocumentChunk(chunkIndex, chunkText);
            chunks.add(chunk);
            chunkIndex++;
        }
        return chunks;
    }


}
