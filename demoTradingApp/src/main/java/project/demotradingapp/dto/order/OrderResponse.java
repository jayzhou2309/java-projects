package project.demotradingapp.dto.order;

import lombok.Builder;
import lombok.Data;
import project.demotradingapp.model.OrderStatus;
import project.demotradingapp.model.OrderType;
import project.demotradingapp.model.PositionSide;

import java.time.LocalDateTime;

@Data
@Builder
public class OrderResponse {
    private Long id;
    private String symbol;
    private OrderType type;
    private OrderStatus status;
    private PositionSide side;
    private Long quantity;
    private Long remainingQuantity;
    private Long price;
    private LocalDateTime createdAt;
}
