package project.stockrecommendationengine.rag.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "sec_filings", schema = "public")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor

public class SECFiling {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String ticker;

    @Column(nullable = false, length = 20)
    private String cik;

    @Column(name = "accession_no", nullable = false, unique = true, length = 32)
    private String accessionNo;

    @Column(name = "filing_type", nullable = false, length = 10)
    private String filingType;

    @Column(name = "filing_date", nullable = false)
    private LocalDate filingDate;

    @Column(name = "report_date")
    private LocalDate reportDate;

    @Column(name = "primary_document", length = 255)
    private String primaryDocument;

    @Column(name = "source_url", nullable = false, columnDefinition = "TEXT")
    private String sourceUrl;

    @Column(name = "processing_version", length = 80)
    private String processingVersion;

    @Column(name = "raw_content", columnDefinition = "TEXT")
    private String rawContent;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Builder.Default
    @Column(name = "ingestion_status", nullable = false, length = 20)
    private String ingestionStatus = "PENDING";

    @Column(name = "ingestion_error", columnDefinition = "TEXT")
    private String ingestionError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @OneToMany(
            mappedBy = "filing",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @Builder.Default
    private List<FilingChunk> chunks = new ArrayList<>();
}
