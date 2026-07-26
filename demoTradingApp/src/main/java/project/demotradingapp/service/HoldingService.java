package project.demotradingapp.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import project.demotradingapp.dto.portfolio.HoldingResponse;
import project.demotradingapp.entity.Holdings;
import project.demotradingapp.entity.Portfolio;
import project.demotradingapp.entity.Stock;
import project.demotradingapp.mapper.HoldingMapper;
import project.demotradingapp.repository.HoldingsRepo;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HoldingService {
    private final HoldingsRepo holdingsRepo;
    private final HoldingMapper holdingMapper;

    public Holdings getHoldings(Portfolio portfolio, Stock stock){
        return holdingsRepo.findByPortfolioAndStock(portfolio, stock)
                .orElse(null);
    }

    public HoldingResponse getHoldingForStockResponse(Portfolio portfolio, Stock stock){
        Holdings holding = getHoldings(portfolio, stock);
        return holdingMapper.toHoldingsResponse(holding);
    }


    public List<HoldingResponse> getHoldingsForPortfolio(Portfolio portfolio){
        return holdingsRepo.findByPortfolio(portfolio)
                .stream()
                .map(holdingMapper::toHoldingsResponse)
                .toList();
    }

    // CHECK HOLDING EXIST
    private Holdings requireHolding(Portfolio portfolio, Stock stock){
        Holdings holdings = getHoldings(portfolio, stock);
        if (holdings == null){
            throw new IllegalArgumentException("Holding not Found");
        }
        return holdings;
    }

    @Transactional
    public Holdings increaseHolding(Portfolio portfolio, Stock stock, BigDecimal quantity, BigDecimal executionPrice){
        Holdings holdings = getHoldings(portfolio, stock);
        if (holdings == null){
            holdings = Holdings.builder()
                    .portfolio(portfolio)
                    .stock(stock)
                    .quantity(quantity)
                    .reservedQuantity(BigDecimal.ZERO)
                    .averagePrice(executionPrice)
                    .build();
            return holdingsRepo.save(holdings);
        }
        BigDecimal oldCost = holdings.getAveragePrice().multiply(holdings.getQuantity());
        BigDecimal newCost = executionPrice.multiply(quantity);
        BigDecimal oldQuantity = holdings.getQuantity();
        BigDecimal newQuantity = oldQuantity.add(quantity);

        BigDecimal newAverage = oldCost.add(newCost).divide(newQuantity, 8, RoundingMode.HALF_UP);

        holdings.setQuantity(newQuantity);
        holdings.setAveragePrice(newAverage);
        return holdingsRepo.save(holdings);
    }

    @Transactional
    public void decreaseHolding(Portfolio portfolio, Stock stock, BigDecimal quantity){
        Holdings holdings = requireHolding(portfolio, stock);

        if (quantity.compareTo(holdings.getReservedQuantity()) > 0){
            throw new IllegalArgumentException("Insufficient Reserved Shares");
        }

        holdings.setReservedQuantity(holdings.getReservedQuantity().subtract(quantity));
        holdings.setQuantity(holdings.getQuantity().subtract(quantity));

        if (holdings.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
            holdingsRepo.delete(holdings);
        } else {
            holdingsRepo.save(holdings);
        }
    }

    @Transactional
    public void reserveShares(Portfolio portfolio, Stock stock, BigDecimal quantity){
        Holdings holdings = requireHolding(portfolio, stock);
        // User sell order reserves shares
        holdings.setReservedQuantity(holdings.getReservedQuantity().add(quantity));
        holdingsRepo.save(holdings);
    }

    @Transactional
    public void releaseReservedShares(Portfolio portfolio, Stock stock, BigDecimal quantity){
        // when user cancels sell order, release reserved shares
        Holdings holdings = requireHolding(portfolio, stock);
        holdings.setReservedQuantity(holdings.getReservedQuantity().subtract(quantity));
        holdingsRepo.save(holdings);
    }

    public boolean hasSufficientShares(Portfolio portfolio, Stock stock, BigDecimal quantity){
        Holdings holdings = getHoldings(portfolio, stock);
        if (holdings == null) return false;
        BigDecimal availabeShares = holdings.getQuantity().subtract(holdings.getReservedQuantity());

        return availabeShares.compareTo(quantity) >= 0;
    }
}
