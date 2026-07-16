package project.demotradingapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "trades")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradesEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "buy_order_id")
    private Long buyOrderId;

    @Column(name = "sell_order_id")
    private Long sellOrderId;

    @Column(name = "stock_id")
    private Long stockId;

    @Column(name = "quantity")
    private Long quantity;

    @Column(name = "execution_price")
    private BigDecimal executionPrice;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;
}
