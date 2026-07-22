package project.demotradingapp.dto.order;

import lombok.Builder;
import lombok.Data;
import project.demotradingapp.model.OrderStatus;
import project.demotradingapp.model.OrderType;
import project.demotradingapp.model.PositionSide;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class OrderResponse {
    private Long id;
    private String symbol;
    private OrderType orderType;
    private OrderStatus status;
    private PositionSide side;
    private BigDecimal quantity;
    private BigDecimal remainingQuantity;
    private BigDecimal price;
    private LocalDateTime createdAt;
}
