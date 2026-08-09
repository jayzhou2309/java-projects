package project.demotradingapp.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.internals.events.StreamsOnAllTasksLostCallbackCompletedEvent;
import org.springframework.stereotype.Service;
import project.demotradingapp.dto.order.CreateOrderRequest;
import project.demotradingapp.dto.order.OrderResponse;
import project.demotradingapp.entity.*;
import project.demotradingapp.mapper.OrderMapper;
import project.demotradingapp.model.OrderStatus;
import project.demotradingapp.model.OrderType;
import project.demotradingapp.model.PositionSide;
import project.demotradingapp.repository.*;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final UsersRepo usersRepo;
    private final OrdersRepo ordersRepo;
    private final PortfolioRepo portfolioRepo;
    private final OrderMapper orderMapper;
    private final PortfolioService portfolioService;
    private final HoldingService holdingService;
    private final StockService stockService;
    private final MatchingEngineService matchingEngineService;

    // =========================
    // ORDER OWNERSHIP
    // =========================

    public Orders getOwnedOrder(Long orderId, User user) {
        Orders order = ordersRepo.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));
        if (!order.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Not your order");
        }
        return order;
    }

    // =========================
    // CREATE
    // =========================

    @Transactional
    public OrderResponse placeOrder(CreateOrderRequest request, User user) {
        validateOrderRequest(request);
        Stock stock = stockService.getStock(request.getStockId());
        Portfolio portfolio = portfolioRepo.findByUser(user);

        reserveFundOrShares(portfolio, stock, request);

        Orders order = Orders.builder()
                .user(user)
                .stock(stock)
                .orderType(request.getOrderType())
                .side(request.getSide())
                .quantity(request.getQuantity())
                .remainingQuantity(request.getQuantity())
                .price(request.getPrice())
                .build();

        Orders savedOrder = ordersRepo.save(order);

        return orderMapper.toOrderResponse(savedOrder);
    }

    // =========================
    // CANCEL
    // =========================

    @Transactional
    public void cancelOrder(Long orderId, User user) {
        Orders order = getOwnedOrder(orderId, user);

        if (order.getStatus() != OrderStatus.PENDING &&
                order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
            throw new IllegalArgumentException("Order cannot be cancelled");
        }

        releaseOrderReservation(order);

        order.setStatus(OrderStatus.CANCELLED);

        ordersRepo.save(order);
    }

    // =========================
    // READ
    // =========================

    public List<OrderResponse> getAllOrders(User user) {

        return ordersRepo.getOrdersByUser(user)
                .stream()
                .map(orderMapper::toOrderResponse)
                .toList();
    }

    public OrderResponse getOrderById(Long orderId, User user) {
        return orderMapper.toOrderResponse(getOwnedOrder(orderId, user));
    }

    public List<OrderResponse> getOpenOrders(User user) {
        return getOrdersByStatus(user, List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED));
    }

    public List<OrderResponse> getOrderHistory(User user) {
        return getOrdersByStatus(user, List.of(OrderStatus.FILLED, OrderStatus.CANCELLED));
    }

    // =========================
    // RESERVATION
    // =========================

    private void reserveFundOrShares(
            Portfolio portfolio,
            Stock stock,
            CreateOrderRequest request
    ){
        BigDecimal quantity = request.getQuantity();
        BigDecimal price = request.getPrice();
        if (request.getSide() == PositionSide.BUY){
            BigDecimal totalCost = price.multiply(quantity);
            portfolioService.reserveCash(portfolio, totalCost);
        } else {
            holdingService.reserveShares(portfolio, stock, quantity);
        }
    }

    private void releaseOrderReservation(Orders order){
        Portfolio portfolio = order.getUser().getPortfolio();
        if (order.getSide() == PositionSide.BUY){
            BigDecimal refund =
                    order.getRemainingQuantity()
                            .multiply(order.getPrice());
            portfolioService.releaseReservedCash(portfolio, refund);
        } else {
            holdingService.releaseReservedShares(portfolio, order.getStock(), order.getRemainingQuantity());
        }
    }

    private Orders createAndReserveOrders(User user, Stock stock, PositionSide side, OrderType orderType,
    BigDecimal quantity, BigDecimal price){
        Portfolio portfolio = portfolioRepo.findByUser(user);

        BigDecimal totalCost = price.multiply(quantity);

        if (side == PositionSide.BUY) {
            // Holdings are created/increased at trade execution, not at placement.
            portfolioService.reserveCash(portfolio, totalCost);
        } else {
            if (!holdingService.hasSufficientShares(portfolio, stock, quantity)) {
                throw new IllegalArgumentException("Insufficient Shares");
            }
            holdingService.reserveShares(portfolio, stock, quantity);
        }

        Orders order = Orders.builder()
                .user(user)
                .stock(stock)
                .orderType(orderType)
                .side(side  )
                .status(OrderStatus.PENDING)
                .quantity(quantity)
                .remainingQuantity(quantity)
                .price(price)
                .build();

        return ordersRepo.save(order);
    }

    // =========================
    // HELPERS
    // =========================

    private List<OrderResponse> getOrdersByStatus(User user, List<OrderStatus> statusList) {
        return ordersRepo.findByUserAndStatusInOrderByCreatedAtDesc(user, statusList)
                .stream()
                .map(orderMapper::toOrderResponse)
                .toList();
    }

    private void validateOrderRequest(CreateOrderRequest request){
        if (request.getQuantity().compareTo(BigDecimal.ZERO) <= 0){
            throw new IllegalArgumentException("Quantity must be greater than 0");
        }

        if (request.getPrice().compareTo(BigDecimal.ZERO) <= 0){
            throw new IllegalArgumentException("Price must be greater than 0");
        }
    }
}