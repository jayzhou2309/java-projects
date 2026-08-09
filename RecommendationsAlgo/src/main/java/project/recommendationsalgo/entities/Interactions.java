package project.recommendationsalgo.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;


import java.time.LocalDateTime;

@Entity
@Table(name = "interactions")
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
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
    private Content content;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private InteractionType interactionType;

    @Column(name = "watch_duration_sec")
    private Integer watchDurationSec;

    @CreationTimestamp
    @Column(name = "timestamp")
    private LocalDateTime timestamp;
}
