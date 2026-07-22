package project.demotradingapp.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.demotradingapp.dto.portfolio.PortfolioResponse;
import project.demotradingapp.entity.Portfolio;

@Component
@RequiredArgsConstructor
public class PortfolioMapper {

    private final HoldingMapper holdingMapper;

    public PortfolioResponse toPortfolioResponse(Portfolio portfolio){
        return PortfolioResponse.builder()
                .id(portfolio.getId())
                .availableCash(portfolio.getAvailableCash())
                .reservedCash(portfolio.getReservedCash())
                .holdings(portfolio.getHoldings().stream()
                        .map(holdingMapper::toHoldingsResponse)
                        .toList())
                .build();
    }
}
