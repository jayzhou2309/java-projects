package project.ragdemo.stock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface StockIdentifierRepository extends JpaRepository<StockIdentifier, UUID> {
    @Query("""
        select i from StockIdentifier i
        where i.identifierType = :type and i.identifierValue = :value
          and i.validFrom <= :asOf and (i.validTo is null or i.validTo > :asOf)
          and i.recordedAt <= :asOf
        order by i.recordedAt, i.id
        """)
    List<StockIdentifier> findKnownAt(@Param("type") String type, @Param("value") String value,
                                     @Param("asOf") Instant asOf);
}
