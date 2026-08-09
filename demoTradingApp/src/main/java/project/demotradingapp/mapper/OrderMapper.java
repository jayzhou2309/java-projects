package project.demotradingapp.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.demotradingapp.dto.order.OrderResponse;
import project.demotradingapp.entity.Orders;
import project.demotradingapp.repository.StocksRepo;

@Component
@RequiredArgsConstructor
public class OrderMapper {
    private final StocksRepo stocksRepo;

    public OrderResponse toOrderResponse(Orders orders){
        return OrderResponse.builder()
                .symbol(orders.getStock().getSymbol())
                .orderType(orders.getOrderType())
                .status(orders.getStatus())
                .side(orders.getSide())
                .quantity(orders.getQuantity())
                .remainingQuantity(orders.getRemainingQuantity())
                .price(orders.getPrice())
                .createdAt(orders.getCreatedAt())
                .build();
    }
}
