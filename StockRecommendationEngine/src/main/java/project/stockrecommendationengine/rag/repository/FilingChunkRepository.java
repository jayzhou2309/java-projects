package project.stockrecommendationengine.rag.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.stockrecommendationengine.rag.entity.FilingChunk;

public interface FilingChunkRepository extends JpaRepository<FilingChunk, Long> {
}
