package project.demotradingapp.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.demotradingapp.dto.portfolio.HoldingResponse;
import project.demotradingapp.entity.Holdings;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class HoldingMapper {
    public HoldingResponse toHoldingsResponse(Holdings holdings){
        return HoldingResponse.builder()
                .symbol(holdings.getStock().getSymbol())
                .averagePrice(holdings.getAveragePrice())
                .quantity(holdings.getQuantity())
                .marketValue(holdings.getAveragePrice().multiply(holdings.getQuantity()))
                .build();
    }
}
