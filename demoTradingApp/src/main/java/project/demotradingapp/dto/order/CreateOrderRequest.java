package project.demotradingapp.dto.order;

import lombok.Data;
import project.demotradingapp.model.OrderType;
import project.demotradingapp.model.PositionSide;

import java.math.BigDecimal;

@Data
public class CreateOrderRequest {
    private OrderType orderType;
    private Long stockId;
    private PositionSide side;
    private Long quantity;
    private BigDecimal price;
}
