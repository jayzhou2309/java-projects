package project.demotradingapp.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.demotradingapp.entity.*;
import project.demotradingapp.model.OrderStatus;
import project.demotradingapp.model.PositionSide;
import project.demotradingapp.repository.HoldingsRepo;
import project.demotradingapp.repository.OrdersRepo;
import project.demotradingapp.repository.PortfolioRepo;
import project.demotradingapp.repository.TradesRepo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;


@Service
@RequiredArgsConstructor
public class MatchingEngineService {

    private final OrdersRepo ordersRepo;
    private final HoldingsRepo holdingsRepo;
    private final PortfolioRepo portfolioRepo;
    private final TradesRepo tradesRepo;

    // Entry point
    @Transactional
    public void matchOrders(Long stockId){
        List<OrderStatus> statusList = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
        List<Orders> buyOrder = ordersRepo.findByStockIdAndSideAndStatusOrderByPriceDescCreatedAtAsc(stockId, PositionSide.BUY, statusList);
        List<Orders> sellOrder = ordersRepo.findByStockIdAndSideAndStatusOrderByPriceAscCreatedAtAsc(stockId, PositionSide.SELL, statusList);

        while (!buyOrder.isEmpty() && !sellOrder.isEmpty()){
            Orders bestBuy = buyOrder.get(0);
            Orders bestSell = sellOrder.get(0);

            if (bestBuy.getPrice().compareTo(bestSell.getPrice()) < 0){
                break;
            }

            executeTrade(bestBuy, bestSell);

            if (bestBuy.getStatus() == OrderStatus.FILLED){
                buyOrder.removeFirst();
            }
            if (bestSell.getStatus() == OrderStatus.FILLED){
                sellOrder.removeFirst();
            }
        }
    }

    // Core matching
    private void executeTrade(Orders buyOrder, Orders sellOrder){
        BigDecimal matchedQuantity = calculateMatchedQuantity(buyOrder, sellOrder);
        if (matchedQuantity.compareTo(BigDecimal.ZERO) <= 0){
            return;
        }
        BigDecimal executionPrice = calculateExecutionPrice(buyOrder, sellOrder);
        Trades trade = createTrade(
                buyOrder, sellOrder, matchedQuantity, executionPrice
        );
        tradesRepo.save(trade);

        updateBuyerPortfolio(buyOrder, executionPrice, matchedQuantity);
        updateBuyerHolding(buyOrder.getUser().getPortfolio(), buyOrder.getStock(), matchedQuantity, executionPrice);
        // Seller Holding
        Holdings holdings = holdingsRepo.findByPortfolioAndStock(sellOrder.getUser().getPortfolio(), sellOrder.getStock())
                .orElseThrow(() -> new RuntimeException("Holding not Found"));
        updateSellerPortfolio(sellOrder.getUser().getPortfolio(), executionPrice, matchedQuantity);
        updateSellerHolding(holdings, matchedQuantity);

        updateOrder(buyOrder, matchedQuantity);
        updateOrder(sellOrder, matchedQuantity);
    }

    // Order updates
    private void updateOrder(Orders order, BigDecimal matchedQuantity) {
        BigDecimal remaining = order.getRemainingQuantity().subtract(matchedQuantity);
        order.setRemainingQuantity(remaining);

        if (remaining.compareTo(BigDecimal.ZERO) <= 0){
            order.setStatus(OrderStatus.FILLED);
        } else {
            order.setStatus(OrderStatus.PARTIALLY_FILLED);
        }

        ordersRepo.save(order);
    }

    // Portfolio updates
    private void updateBuyerPortfolio(
            Orders buyOrder,
            BigDecimal executionPrice,
            BigDecimal matchedQuantity
    ) {
        Portfolio portfolio = buyOrder.getUser().getPortfolio();
        BigDecimal reserved = buyOrder.getPrice().multiply(matchedQuantity);
        BigDecimal actual = executionPrice.multiply(matchedQuantity);
        BigDecimal refund = reserved.subtract(actual);
        portfolio.setReservedCash(portfolio.getReservedCash().subtract(reserved));
        portfolio.setAvailableCash(portfolio.getAvailableCash().add(refund));
        portfolioRepo.save(portfolio);

    }

    private void updateSellerPortfolio(
            Portfolio portfolio,
            BigDecimal executionPrice,
            BigDecimal matchedQuantity
    ) {
        BigDecimal totalProceeds = matchedQuantity.multiply(executionPrice);
        portfolio.setAvailableCash(portfolio.getAvailableCash().add(totalProceeds));
        portfolioRepo.save(portfolio);
    }

    // Holdings updates
    private void updateBuyerHolding(
            Portfolio portfolio,
            Stock stock,
            BigDecimal matchedQuantity,
            BigDecimal executionPrice
    ) {
        Holdings holdings = holdingsRepo.findByPortfolioAndStock(portfolio, stock)
                .orElse(null);
        if (holdings == null){
            holdings = Holdings.builder()
                    .portfolio(portfolio)
                    .stock(stock)
                    .quantity(matchedQuantity)
                    .reservedQuantity(BigDecimal.ZERO)
                    .averagePrice(executionPrice)
                    .build();
        } else {
            BigDecimal oldQuantity = holdings.getQuantity();
            BigDecimal newQuantity = oldQuantity.add(matchedQuantity);

            BigDecimal oldCost = holdings.getAveragePrice().multiply(holdings.getQuantity());
            BigDecimal newCost = executionPrice.multiply(matchedQuantity);

            BigDecimal totalCost = oldCost.add(newCost);
            BigDecimal newAveragePrice = totalCost.divide(newQuantity, 8, RoundingMode.HALF_UP);


            holdings.setQuantity(newQuantity);
            holdings.setAveragePrice(newAveragePrice);
        }

        holdingsRepo.save(holdings);

    }

    private void updateSellerHolding(
            Holdings holding,
            BigDecimal matchedQuantity
    ) {
        holding.setReservedQuantity(holding.getReservedQuantity().subtract(matchedQuantity));
        holding.setQuantity(holding.getQuantity().subtract(matchedQuantity));
        if (holding.getQuantity().compareTo(BigDecimal.ZERO) == 0){
            holdingsRepo.delete(holding);
        } else {
            holdingsRepo.save(holding);
        }
    }

    // Trade creation
    private Trades createTrade(
            Orders buyOrder,
            Orders sellOrder,
            BigDecimal matchedQuantity,
            BigDecimal executionPrice
    ) {
        return Trades.builder()
                .buyOrder(buyOrder)
                .sellOrder(sellOrder)
                .stock(buyOrder.getStock())
                .quantity(matchedQuantity)
                .executionPrice(executionPrice)
                .executedAt(LocalDateTime.now())
                .build();
    }

    // Price/quantity calculation
    private BigDecimal calculateExecutionPrice(
            Orders buyOrder,
            Orders sellOrder
    ) {
        if (buyOrder.getCreatedAt().isBefore(sellOrder.getCreatedAt())){
            return buyOrder.getPrice();
        }
        if (sellOrder.getCreatedAt().isBefore(buyOrder.getCreatedAt())){
            return sellOrder.getPrice();
        }
        if (buyOrder.getId() < sellOrder.getId()){
            return buyOrder.getPrice();
        }
        return sellOrder.getPrice();
    }

    private BigDecimal calculateMatchedQuantity(
            Orders buyOrder,
            Orders sellOrder
    ) {
        if (buyOrder.getRemainingQuantity().compareTo(sellOrder.getRemainingQuantity()) <= 0){
            return buyOrder.getRemainingQuantity();
        } else {
            return sellOrder.getRemainingQuantity();
        }
    }
}