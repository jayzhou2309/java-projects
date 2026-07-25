package project.demotradingapp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import project.demotradingapp.entity.Holdings;
import project.demotradingapp.entity.Portfolio;
import project.demotradingapp.entity.Stock;
import project.demotradingapp.repository.HoldingsRepo;


import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class HoldingService {
    private final HoldingsRepo holdingsRepo;

    public Holdings createHolding(Portfolio portfolio, Stock stock, BigDecimal quantity, BigDecimal price){
        return Holdings.builder()
                .portfolio(portfolio)
                .stock(stock)
                .quantity(quantity)
                .reservedQuantity(BigDecimal.ZERO)
                .averagePrice(price)
                .build();
    }

    public Holdings getHolding(Portfolio portfolio, Stock stock){
        return holdingsRepo.findByPortfolioAndStock(portfolio, stock).orElse(null);
    }

    public void increaseHolding(Portfolio portfolio, Stock stock, BigDecimal quantity, BigDecimal executionPrice){
        Holdings holdings = getHolding(portfolio, stock);
        if (holdings == null){
            holdings = createHolding(portfolio, stock, quantity, executionPrice);
        }
        BigDecimal oldCost = holdings.getAveragePrice().multiply(holdings.getQuantity());
        BigDecimal newCost = executionPrice.multiply(quantity);
        BigDecimal totalCost = oldCost.add(newCost);
        BigDecimal oldQuantity = holdings.getQuantity();
        BigDecimal newQuantity = oldQuantity.add(quantity);

        BigDecimal newAverage = totalCost.divide(newQuantity, 8, RoundingMode.HALF_UP);

        holdings.setQuantity(newQuantity);
        holdings.setAveragePrice(newAverage);
        holdingsRepo.save(holdings);
    }

    public void decreaseHolding(Portfolio portfolio, Stock stock, BigDecimal quantity){
        Holdings holdings = getHolding(portfolio, stock);

        if (quantity.compareTo(holdings.getReservedQuantity()) > 0){
            throw new IllegalArgumentException("Insufficient Reserved Shares");
        }

        holdings.setReservedQuantity(holdings.getReservedQuantity().subtract(quantity));
        holdings.setQuantity(holdings.getQuantity().subtract(quantity));
        deleteHoldingIfEmpty(portfolio, stock);

        if (holdings.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
            holdingsRepo.save(holdings);
        }
    }

    public void reserveShares(Portfolio portfolio, Stock stock, BigDecimal quantity){
        Holdings holdings = getHolding(portfolio, stock);
        // User sell order reserves shares
        holdings.setReservedQuantity(holdings.getReservedQuantity().add(quantity));
    }

    public void releaseReservedShares(Portfolio portfolio, Stock stock, BigDecimal quantity){
        // when user cancels sell order, release reserved shares
        Holdings holdings = getHolding(portfolio, stock);
        holdings.setReservedQuantity(holdings.getReservedQuantity().subtract(quantity));
        holdings.setQuantity(holdings.getQuantity().add(quantity));
    }

    public boolean hasSufficientShares(Portfolio portfolio, Stock stock, BigDecimal quantity){
        Holdings holdings = getHolding(portfolio, stock);
        BigDecimal availabeShares = holdings.getQuantity().subtract(holdings.getReservedQuantity());

        return availabeShares.compareTo(quantity) >= 0;

    }

    private void deleteHoldingIfEmpty(Portfolio portfolio, Stock stock){
        Holdings holdings = getHolding(portfolio, stock);
        if (holdings.getQuantity().compareTo(BigDecimal.ZERO) == 0){
            holdingsRepo.delete(holdings);
        }
    }

}
