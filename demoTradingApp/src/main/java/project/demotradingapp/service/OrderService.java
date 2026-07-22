package project.demotradingapp.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.demotradingapp.dto.order.CancelOrderRequest;
import project.demotradingapp.dto.order.CreateOrderRequest;
import project.demotradingapp.dto.order.OrderResponse;
import project.demotradingapp.entity.*;
import project.demotradingapp.mapper.HoldingMapper;
import project.demotradingapp.mapper.OrderMapper;
import project.demotradingapp.model.OrderStatus;
import project.demotradingapp.model.PositionSide;
import project.demotradingapp.repository.*;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final UsersRepo usersRepo;
    private final OrdersRepo ordersRepo;
    private final PortfolioRepo portfolioRepo;
    private final StocksRepo stocksRepo;
    private final OrderMapper orderMapper;
    private final HoldingsRepo holdingsRepo;
    // Create a new order

    @Transactional
    public OrderResponse placeOrder(CreateOrderRequest request, User user){
        // Error Checks
        if (!usersRepo.existsByUsername(user.getUsername())){
            throw new RuntimeException("User does not exist");
        }
        if (request.getQuantity().compareTo(BigDecimal.ZERO) < 0 ||
        request.getPrice().compareTo(BigDecimal.ZERO) < 0){
            throw new RuntimeException("Quantity or Price cant be less than 0");
        }

        Portfolio portfolio = portfolioRepo.findByUser(user);
        Stock stock = stocksRepo.findById(request.getStockId())
                .orElseThrow(() -> new RuntimeException("Stock not found"));


        BigDecimal totalCost = request.getPrice().multiply(request.getQuantity());

        if (portfolio.getAvailableCash().compareTo(totalCost) < 0) {
            throw new RuntimeException("Insufficient Balance");
        }
        if (request.getSide() == PositionSide.SELL){
            Holdings holdings = holdingsRepo.findByPortfolioAndStock(portfolio, stock)
                    .orElseThrow(() -> new RuntimeException("Holding does not exist"));
            BigDecimal availableShares = holdings.getQuantity().subtract(holdings.getReservedQuantity());
            if (request.getQuantity().compareTo(availableShares) > 0){
                throw new RuntimeException("Insufficient Shares");
            }
            holdings.setReservedQuantity(
                    holdings.getReservedQuantity().add(request.getQuantity())
            );
            holdingsRepo.save(holdings);
        }

        if (request.getSide() == PositionSide.BUY){
            portfolio.setAvailableCash(
                    portfolio.getAvailableCash().subtract(totalCost)
            );
            portfolio.setReservedCash(
                    portfolio.getReservedCash().add(totalCost)
            );
            portfolioRepo.save(portfolio);
        }
        // NOT DONE: CREATE HOLDINGS IF BUY

        Orders order = Orders.builder()
                .user(user)
                .stock(stock)
                .orderType(request.getOrderType())
                .side(request.getSide())
                .status(OrderStatus.PENDING)
                .quantity(request.getQuantity())
                .remainingQuantity(request.getQuantity())
                .price(request.getPrice())
                .build();

        ordersRepo.save(order);

        return orderMapper.toOrderResponse(order);
    }

    // Cancel an existing order

    public void cancelOrder(CancelOrderRequest request, User user){
        if (!usersRepo.existsByUsername(user.getUsername())){
            throw new RuntimeException("User does not exist");
        }
        Orders order = ordersRepo.findById(request.getOrderId())
                .orElseThrow(() -> new RuntimeException("Order does not exist"));
        if (!order.getUser().getId().equals(user.getId())){
            throw new RuntimeException("Not your order");
        }
        if (order.getStatus() != OrderStatus.PENDING &&
            order.getStatus() != OrderStatus.PARTIALLY_FILLED){
            throw new RuntimeException("Order cannot be cancelled");
        }

        Stock stock = order.getStock();
        Portfolio portfolio = order.getUser().getPortfolio();
        Holdings holdings = holdingsRepo.findByPortfolioAndStock(portfolio, stock)
                .orElseThrow(() -> new RuntimeException("Holding not found"));

        BigDecimal refund = order.getRemainingQuantity().multiply(order.getPrice());

        if (order.getSide() == PositionSide.BUY){
            portfolio.setAvailableCash(
                    portfolio.getAvailableCash().add(refund)
            );
            portfolio.setReservedCash(
                    portfolio.getReservedCash().subtract(refund)
            );
        } else {
            holdings.setReservedQuantity(
                    holdings.getReservedQuantity().subtract(order.getRemainingQuantity())
            );
        }

        order.setStatus(OrderStatus.CANCELLED);
        ordersRepo.save(order);
        portfolioRepo.save(portfolio);
        holdingsRepo.save(holdings);

    }
}
