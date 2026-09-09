package project.rag.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.rag.entity.SECFiling;

public interface SECFilingRepository extends JpaRepository<SECFiling, Long> {
    boolean existsByAccessionNo(String accessionNo);
}
