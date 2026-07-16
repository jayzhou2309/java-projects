package project.demotradingapp.entity;

import jakarta.persistence.*;
import lombok.*;
import project.demotradingapp.model.OrderStatus;
import project.demotradingapp.model.OrderType;
import project.demotradingapp.model.PositionSide;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrdersEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "stock_id")
    private Long stockId;

    @Column(name = "order_type")
    private OrderType orderType;

    @Column(name = "side")
    private PositionSide side;

    @Column(name = "status")
    private OrderStatus status;

    @Column(name = "quantity")
    private Long quantity;

    @Column(name = "remaining_quantity")
    private Long remainingQuantity;

    @Column(name = "price")
    private BigDecimal price;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "version")
    private Long version;



}
