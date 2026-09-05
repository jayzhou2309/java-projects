package project.ragdemo.sec;


import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import project.ragdemo.stock.Stock;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "filings",
    uniqueConstraints = {
        @UniqueConstraint(
                name = "uq_filings_stock_accession",
                columnNames = {"stock_id", "accession_no"}
        )
    }
)
@Builder
@Getter
@Setter
@RequiredArgsConstructor
@AllArgsConstructor
public class Filing {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", nullable = false)
    private Stock stock;


    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "filing_type", nullable = false)
    private FilingType filingType;

    @Column(name = "accession_no", unique = true, length = 25)
    private String accessionNo;

    @Column(name = "filed_date", nullable = false)
    private LocalDate filedDate;

    @Column(name = "source_url", nullable = false)
    private String sourceUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Builder.Default
    private FilingStatus status = FilingStatus.PENDING;

    @Column(name = "ingested_at")
    private LocalDateTime ingestedAt;

    @Column(name = "embedded_at")
    private LocalDateTime embeddedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

}
