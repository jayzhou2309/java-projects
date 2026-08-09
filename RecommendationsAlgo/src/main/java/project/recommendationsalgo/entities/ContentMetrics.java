package project.recommendationsalgo.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "content_metrics")
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ContentMetrics {
    @Id
    @Column(name = "content_id")
    private Long contentId; // not a generated PK

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "content_id")
    private Content content;

    @Column(name = "view_count", nullable = false)
    @Builder.Default
    private Long viewCount = 0L;

    @Column(name = "like_count", nullable = false)
    @Builder.Default
    private Long likeCount = 0L;

    @Column(name = "watch_count", nullable = false)
    @Builder.Default
    private Long watchCount = 0L;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
