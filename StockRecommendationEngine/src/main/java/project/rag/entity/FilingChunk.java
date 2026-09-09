package project.rag.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(
        name = "sec_filing_chunks",
        schema = "public",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_sec_filing_chunk",
                        columnNames = {"filing_id", "chunk_index"}
                )
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilingChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "filing_id", nullable = false)
    private SECFiling filing;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "section_key", length = 64)
    private String sectionKey;

    @Column(name = "section_title", length = 255)
    private String sectionTitle;

    @Column(name = "section_chunk_index")
    private Integer sectionChunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "start_char")
    private Integer startChar;

    @Column(name = "end_char")
    private Integer endChar;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(
            name = "embedding",
            columnDefinition = "vector(3072)",
            insertable = false,
            updatable = false
    )
    private String embedding;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private OffsetDateTime createdAt;
}