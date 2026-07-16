package project.demotradingapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "holdings")
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class HoldingsEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "portfolio_id")
    private Long portfolioId;

    @Column(name = "stock_id")
    private Long stockId;

    @Column(name = "quantity")
    private Long quantity;

    @Column(name = "average_price")
    private BigDecimal averagePrice;

    @Column(name = "version")
    private Long version;

}
