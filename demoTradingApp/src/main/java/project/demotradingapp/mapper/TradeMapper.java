package project.demotradingapp.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.demotradingapp.dto.trade.TradeResponse;
import project.demotradingapp.entity.Trades;

@Component
@RequiredArgsConstructor
public class TradeMapper {
    public TradeResponse toTradesResponse(Trades trades){
        return TradeResponse.builder()
                .id(trades.getId())
                .symbol(trades.getStock().getSymbol())
                .quantity(trades.getQuantity())
                .executionPrice(trades.getExecutionPrice())
                .executedAt(trades.getExecutedAt())
                .buyOrderId(trades.getBuyOrder().getId())
                .sellOrderId(trades.getSellOrder().getId())
                .build();

    }
}
