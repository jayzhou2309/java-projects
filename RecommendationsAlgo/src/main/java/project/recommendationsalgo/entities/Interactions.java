package project.recommendationsalgo.entities;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import project.recommendationsalgo.User;

import java.time.LocalDateTime;

@Entity
@Table(name = "interactions")
@Builder
@Getter
@Setter
@NoArgsConstructor
public class Interactions {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_id", nullable = false)
    private project.recommendationsalgo.entities.Content content;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private project.recommendationsalgo.entities.InteractionType interactionType;

    @Column(name = "watch_duration_sec")
    private Integer watchDurationSec;

    @CreationTimestamp
    @Column(name = "timestamp")
    private LocalDateTime timestamp;
}
