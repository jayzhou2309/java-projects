package project.demotradingapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "trades")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class TradesEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buy_order_id")
    private OrdersEntity buyOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sell_order_id")
    private OrdersEntity sellOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id")
    private StocksEntity stock;

    @Column(name = "quantity")
    private Long quantity;

    @Column(name = "execution_price")
    private BigDecimal executionPrice;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;


}
