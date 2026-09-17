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

    /**
     * The distinct processing versions of the filings retrieval can return (ingestion status EMBEDDED), sorted, a null version (a filing
     * stored before migration V3 and never rebuilt) last; recorded as {@code properties.storeVersions} of an evaluation snapshot.
     */
    @Query("select distinct f.processingVersion from SECFiling f where f.ingestionStatus = 'EMBEDDED' order by f.processingVersion")
    List<String> findDistinctProcessingVersionsOfEmbeddedFilings();
}
