package project.stockrecommendationengine.rag.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.stockrecommendationengine.rag.entity.SECFiling;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;

public interface SECFilingRepository extends JpaRepository<SECFiling, Long> {
    Optional<SECFiling> findByAccessionNo(String accessionNo);
    boolean existsByTickerAndIngestionStatus(String ticker, String ingestionStatus);
    boolean existsByAccessionNoAndIngestionStatus(String accessionNo, String ingestionStatus);
    Optional<SECFiling> findFirstByTickerAndFilingTypeAndIngestionStatusOrderByFilingDateDesc(String ticker, String filingType, String ingestionStatus);
    @Query("select distinct f.ticker from SECFiling f order by f.ticker")
    List<String> findDistinctTickers();
}
