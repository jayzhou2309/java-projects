package project.stockrecommendationengine.rag.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.stockrecommendationengine.rag.entity.SECFiling;

import java.util.Optional;

public interface SECFilingRepository extends JpaRepository<SECFiling, Long> {
    Optional<SECFiling> findByAccessionNo(String accessionNo);
}
