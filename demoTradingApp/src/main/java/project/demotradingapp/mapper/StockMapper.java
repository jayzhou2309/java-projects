package project.demotradingapp.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import project.demotradingapp.dto.stock.StockResponse;
import project.demotradingapp.entity.Stock;


@Component
@RequiredArgsConstructor
public class StockMapper {
    public StockResponse toStockResponse(Stock stock){
        return StockResponse.builder()
                .symbol(stock.getSymbol())
                .currentPrice(stock.getCurrentPrice())
                .active(stock.isActive())
                .build();
    }
}
