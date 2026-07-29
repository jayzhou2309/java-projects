package project.demotradingapp.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import project.demotradingapp.model.OrderType;
import project.demotradingapp.model.PositionSide;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent {
    private Long orderId;
    private Long userId;
    private Long stockId;
    private BigDecimal quantity;
    private BigDecimal price;
    private OrderType orderType;
    private PositionSide side;
}
