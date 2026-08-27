package project.demotradingapp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.demotradingapp.entity.Holdings;
import project.demotradingapp.entity.Portfolio;
import project.demotradingapp.entity.Stock;
import project.demotradingapp.mapper.HoldingMapper;
import project.demotradingapp.repository.HoldingsRepo;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class HoldingServiceTest {
    @Mock
    private HoldingsRepo holdingsRepo;
    @Mock
    private HoldingMapper holdingMapper;
    @InjectMocks
    private HoldingService holdingService;
    @Test
    void hasSufficientShares_shouldReturnTrue(){
        Portfolio portfolio = new Portfolio();
        Stock stock = new Stock();
        Holdings holdings = Holdings.builder()
                .portfolio(portfolio)
                .stock(stock)
                .quantity(new BigDecimal(10))
                .reservedQuantity(new BigDecimal(2))
                .build();

        when(holdingsRepo.findByPortfolioAndStock(portfolio, stock))
                .thenReturn(Optional.of(holdings));

        boolean result =
                holdingService.hasSufficientShares(portfolio, stock, new BigDecimal(0));
        assertTrue(result);
    }

    @Test
    void getHoldings_shouldReturnHolding(){
        Portfolio portfolio = new Portfolio();
        Stock stock = new Stock();
        Holdings holdings = Holdings.builder()
                .portfolio(portfolio)
                .stock(stock)
                .quantity(new BigDecimal(10))
                .reservedQuantity(BigDecimal.ZERO)
                .averagePrice(new BigDecimal(100))
                .build();

        when(holdingsRepo.findByPortfolioAndStock(portfolio, stock))
                .thenReturn(Optional.of(holdings));

        Holdings result = holdingService.getHoldings(portfolio, stock);

        assertNotNull(result);
        assertEquals(holdings, result);
    }
}
