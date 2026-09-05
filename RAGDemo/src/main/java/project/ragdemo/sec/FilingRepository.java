package project.ragdemo.sec;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FilingRepository extends JpaRepository<Filing, UUID> {
    boolean existsByStockIdAndAccessionNo(Long stockId, String accessionNo);
    Optional<Filing> findByStockIdAndAccessionNo(Long stockId, String accessionNo);
}
