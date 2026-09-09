package project.rag.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.rag.entity.FilingChunk;

public interface FilingChunkRepository extends JpaRepository<FilingChunk, Long> {
}
