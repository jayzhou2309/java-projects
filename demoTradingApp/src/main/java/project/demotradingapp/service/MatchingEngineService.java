package project.demotradingapp.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.demotradingapp.entity.*;
import project.demotradingapp.model.OrderStatus;
import project.demotradingapp.model.OrderType;
import project.demotradingapp.model.PositionSide;
import project.demotradingapp.repository.HoldingsRepo;
import project.demotradingapp.repository.OrdersRepo;
import project.demotradingapp.repository.PortfolioRepo;
import project.demotradingapp.repository.TradesRepo;

import javax.sound.sampled.Port;
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
        List<Orders> buyOrders = ordersRepo.findByStockIdAndSideAndStatusOrderByPriceDescCreatedAtAsc(stockId, PositionSide.BUY, statusList);
        List<Orders> sellOrders = ordersRepo.findByStockIdAndSideAndStatusOrderByPriceAscCreatedAtAsc(stockId, PositionSide.SELL, statusList);

        while (!buyOrders.isEmpty() && !sellOrders.isEmpty()){
            Orders bestBuy = buyOrders.get(0);
            Orders bestSell = sellOrders.get(0);

            if (!canMatch(bestBuy, bestSell)){
                break;
            }

            executeTrade(bestBuy, bestSell);

            if (bestBuy.getStatus() == OrderStatus.FILLED){
                buyOrders.remove(0);
            }
            if (bestSell.getStatus() == OrderStatus.FILLED){
                sellOrders.remove(0);
            }
        }
    }

    // Core matching
    private void executeTrade(Orders buyOrder, Orders sellOrder){
        BigDecimal matchedQuantity = calculateMatchedQuantity(buyOrder, sellOrder);
        BigDecimal executionPrice = calculateExecutionPrice(buyOrder, sellOrder);
        Trades trade = createTrade(buyOrder, sellOrder, matchedQuantity, executionPrice);
        updateBuyerHolding(buyOrder.getUser().getPortfolio(), buyOrder.getStock(), matchedQuantity, executionPrice);
        updateBuyerPortfolio(buyOrder, executionPrice, matchedQuantity);
        updateSellerPortfolio(sellOrder.getUser().getPortfolio(), executionPrice, matchedQuantity);
        Holdings sellerHoldings = holdingsRepo.findByPortfolioAndStock(sellOrder.getUser().getPortfolio(), sellOrder.getStock())
                .orElseThrow(() -> new RuntimeException("Holdings not found"));
        updateSellerHolding(sellerHoldings, matchedQuantity);
        updateOrder(buyOrder, matchedQuantity);
        updateOrder(sellOrder, matchedQuantity);
        tradesRepo.save(trade);

    }

    // Order updates
    private void updateOrder(Orders order, BigDecimal matchedQuantity) {
        BigDecimal remaining = order.getRemainingQuantity().subtract(matchedQuantity);

        order.setRemainingQuantity(remaining);
        if (remaining.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException();
        }
        if (remaining.compareTo(BigDecimal.ZERO) == 0){
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

        BigDecimal reservedAmount = buyOrder.getPrice().multiply(matchedQuantity);

        BigDecimal actualAmount = executionPrice.multiply(matchedQuantity);

        BigDecimal refund = reservedAmount.subtract(actualAmount);

        portfolio.setReservedCash(portfolio.getReservedCash().subtract(reservedAmount));
        portfolio.setAvailableCash(portfolio.getAvailableCash().add(refund));
        portfolioRepo.save(portfolio);
    }

    private void updateSellerPortfolio(
            Portfolio portfolio,
            BigDecimal executionPrice,
            BigDecimal matchedQuantity
    ) {
        BigDecimal proceeds = executionPrice.multiply(matchedQuantity);
        portfolio.setAvailableCash(portfolio.getAvailableCash().add(proceeds));
        portfolioRepo.save(portfolio);
    }

    // Holdings updates
    private void updateBuyerHolding(
            Portfolio portfolio,
            Stock stock,
            BigDecimal matchedQuantity,
            BigDecimal executionPrice
    ) {
        // add stock if no stock exist
        Holdings holdings = holdingsRepo.findByPortfolioAndStock(portfolio, stock)
                .orElse(null);

        if (holdings == null) {
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

            BigDecimal oldCost = holdings.getAveragePrice().multiply(oldQuantity);
            BigDecimal newCost = executionPrice.multiply(matchedQuantity);

            BigDecimal averagePrice = oldCost.add(newCost).divide(newQuantity, 8, RoundingMode.HALF_UP);

            holdings.setQuantity(newQuantity);
            holdings.setAveragePrice(averagePrice);
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
        OrderType buyType = buyOrder.getOrderType();
        OrderType sellType = sellOrder.getOrderType();

        if (buyOrder.getOrderType() == OrderType.MARKET
                && sellOrder.getOrderType() == OrderType.MARKET){
            throw new IllegalArgumentException("Cannot execute Market to Market");
        }

        if (buyType == OrderType.MARKET){
            return sellOrder.getPrice();
        }

        if (sellType == OrderType.MARKET){
            return buyOrder.getPrice();
        }

        // If buy is created before sell
        // get the price of BUY
        return buyOrder.getCreatedAt().isBefore(sellOrder.getCreatedAt())
                ? buyOrder.getPrice() : sellOrder.getPrice();
    }

    private boolean canMatch(Orders buyOrder, Orders sellOrders){
        if (buyOrder.getOrderType() == OrderType.MARKET
        && sellOrders.getOrderType() == OrderType.MARKET){
            return false;
        }

        if (buyOrder.getOrderType() == OrderType.MARKET
        || sellOrders.getOrderType() == OrderType.MARKET){
            return true;
        }
        return buyOrder.getPrice().compareTo(sellOrders.getPrice()) >= 0;
    }

    private BigDecimal calculateMatchedQuantity(
            Orders buyOrder,
            Orders sellOrder
    ) {
        return buyOrder.getRemainingQuantity().min(sellOrder.getRemainingQuantity());
    }
}