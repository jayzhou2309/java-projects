package project.demotradingapp.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "portfolios")
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@Setter
public class PortfoliosEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UsersEntity user;

    @Column(name = "available_cash")
    private BigDecimal availableCash;

    @Column(name = "reserved_cash")
    private BigDecimal reservedCash;

    @Version
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "portfolio", fetch = FetchType.LAZY)
    private List<HoldingsEntity> holdings;

    @OneToMany(mappedBy = "portfolio", fetch = FetchType.LAZY)
    private List<TransactionsEntity> transaction;
}
