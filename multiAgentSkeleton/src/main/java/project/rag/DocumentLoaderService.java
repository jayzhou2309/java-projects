package project.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.model.DocumentChunk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class DocumentLoaderService {
    public String loadDocument(String filePath) throws IOException {
        Path path = Path.of(filePath);
        if (!Files.exists(path)){
            throw new IllegalArgumentException(
                    "File does not exist" + filePath
            );
        }
        return Files.readString(path);
    }

    @Service
    @RequiredArgsConstructor
    public static class DocumentChunkingService {
        private static final int CHUNK_SIZE = 500;

        public List<DocumentChunk> chunkDocument(String documentText){
            List<DocumentChunk> chunks = new ArrayList<>();

            int chunkIndex = 0;

            for (int start = 0; start < documentText.length(); start+=CHUNK_SIZE){
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
}
