package project.demotradingapp.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.demotradingapp.dto.order.CancelOrderRequest;
import project.demotradingapp.dto.order.CreateOrderRequest;
import project.demotradingapp.dto.order.OrderResponse;
import project.demotradingapp.entity.*;
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
    private final PortfolioService portfolioService;
    private final HoldingService holdingService;
    // Create a new order

    @Transactional
    public OrderResponse placeOrder(CreateOrderRequest request, User user){
        // Error Checks
        if (!usersRepo.existsByUsername(user.getUsername())){
            throw new IllegalArgumentException("User does not exist");
        }
        if (request.getQuantity().compareTo(BigDecimal.ZERO) <= 0 ||
        request.getPrice().compareTo(BigDecimal.ZERO) <= 0){
            throw new IllegalArgumentException("Quantity or Price cant be less than 0");
        }

        Portfolio portfolio = portfolioRepo.findByUser(user);
        Stock stock = stocksRepo.findById(request.getStockId())
                .orElseThrow(() -> new IllegalArgumentException("Stock not found"));


        BigDecimal totalCost = request.getPrice().multiply(request.getQuantity());
        if (request.getSide() == PositionSide.BUY){
            // Holdings are created/increased at trade execution
            portfolioService.reserveCash(portfolio, totalCost);
        }

        if (request.getSide() == PositionSide.SELL){
            if (!holdingService.hasSufficientShares(portfolio, stock, request.getQuantity())){
                throw new IllegalArgumentException("Insufficient Shares");
            }
            holdingService.reserveShares(portfolio, stock, request.getQuantity());
        }

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
    @Transactional
    public void cancelOrder(CancelOrderRequest request, User user){
        if (!usersRepo.existsByUsername(user.getUsername())){
            throw new IllegalArgumentException("User does not exist");
        }
        Orders order = ordersRepo.findById(request.getOrderId())
                .orElseThrow(() -> new IllegalArgumentException("Order does not exist"));
        if (!order.getUser().getId().equals(user.getId())){
            throw new IllegalArgumentException("Not your order");
        }
        if (order.getStatus() != OrderStatus.PENDING &&
            order.getStatus() != OrderStatus.PARTIALLY_FILLED){
            throw new IllegalArgumentException("Order cannot be cancelled");
        }

        Portfolio portfolio = order.getUser().getPortfolio();

        if (order.getSide() == PositionSide.BUY){
            BigDecimal refund = order.getRemainingQuantity().multiply(order.getPrice());
            portfolioService.releaseReservedCash(portfolio, refund);
        } else {
            holdingService.releaseReservedShares(portfolio, order.getStock(), order.getRemainingQuantity());
        }

        order.setStatus(OrderStatus.CANCELLED);
        ordersRepo.save(order);
    }
}
