package project.demotradingapp.dto.order;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import project.demotradingapp.model.OrderType;
import project.demotradingapp.model.PositionSide;

import java.math.BigDecimal;

@Data
public class CreateOrderRequest {
    @NotNull
    private OrderType orderType;
    @NotNull
    private Long stockId;
    @NotNull
    private PositionSide side;
    @NotNull
    private BigDecimal quantity;
    private BigDecimal price;
}
