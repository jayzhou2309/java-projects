package project.demotradingapp.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.demotradingapp.entity.*;
import project.demotradingapp.model.OrderStatus;
import project.demotradingapp.model.OrderType;
import project.demotradingapp.model.PositionSide;
import project.demotradingapp.repository.OrdersRepo;
import project.demotradingapp.repository.TradesRepo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;


@Service
@RequiredArgsConstructor
public class MatchingEngineService {

    private final OrdersRepo ordersRepo;
    private final TradesRepo tradesRepo;
    private final HoldingService holdingService;
    private final PortfolioService portfolioService;

    // Entry point
    @Transactional
    public void matchOrders(Long stockId){
        List<OrderStatus> statusList = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
        List<Orders> buyOrders = ordersRepo.findByStockIdAndSideAndStatusInOrderByPriceDescCreatedAtAsc(stockId, PositionSide.BUY, statusList);
        List<Orders> sellOrders = ordersRepo.findByStockIdAndSideAndStatusInOrderByPriceDescCreatedAtDesc(stockId, PositionSide.SELL, statusList);

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

        holdingService.increaseHolding(buyOrder.getUser().getPortfolio(), buyOrder.getStock(), matchedQuantity, executionPrice);
        holdingService.decreaseHolding(sellOrder.getUser().getPortfolio(), sellOrder.getStock(), matchedQuantity);

        portfolioService.settleBuyerCash(buyOrder, executionPrice, matchedQuantity);
        portfolioService.settleSellerCash(sellOrder, executionPrice, matchedQuantity);

        updateOrder(buyOrder, matchedQuantity);
        updateOrder(sellOrder, matchedQuantity);
        tradesRepo.save(trade);
    }

    // Order updates
    private void updateOrder(Orders order, BigDecimal matchedQuantity) {
        BigDecimal remaining = order.getRemainingQuantity().subtract(matchedQuantity);

        if (remaining.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException();
        }
        order.setRemainingQuantity(remaining);
        order.setStatus(remaining.compareTo(BigDecimal.ZERO) == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
        ordersRepo.save(order);
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
    private BigDecimal calculateExecutionPrice(Orders buyOrder, Orders sellOrder) {
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