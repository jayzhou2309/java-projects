package project.demotradingapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "holdings")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class HoldingsEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portfolio_id")
    private PortfoliosEntity portfolio;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id")
    private StocksEntity stock;

    @Column(name = "quantity")
    private Long quantity;

    @Column(name = "average_price")
    private BigDecimal averagePrice;

    @Version
    private Long version;

}
