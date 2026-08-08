package project.demotradingapp.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
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

    // --- shared helpers ---

    public Orders getOwnedOrder(Long orderId, User user) {
        Orders order = ordersRepo.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));
        if (!order.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Not your order");
        }
        return order;
    }

    private void releaseOrderReservation(Orders order) {
        Portfolio portfolio = order.getUser().getPortfolio();
        if (order.getSide() == PositionSide.BUY) {
            BigDecimal refund = order.getRemainingQuantity().multiply(order.getPrice());
            portfolioService.releaseReservedCash(portfolio, refund);
        } else {
            holdingService.releaseReservedShares(portfolio, order.getStock(), order.getRemainingQuantity());
        }
    }

    // --- create ---
    @Transactional
    public void triggerMatching(Long stockId) {
        matchingEngineService.matchOrders(stockId);
    }

    @Transactional
    public OrderResponse placeOrder(CreateOrderRequest request, User user) {
        if (!usersRepo.existsByUsername(user.getUsername())) {
            throw new IllegalArgumentException("User does not exist");
        }
        if (request.getQuantity().compareTo(BigDecimal.ZERO) <= 0 ||
                request.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity and Price must be greater than 0");
        }

        Stock stock = stockService.getStock(request.getStockId());

        Orders saved = createAndReserveOrders(user, stock, request.getSide(), request.getOrderType(),
                request.getQuantity(), request.getPrice());

        return orderMapper.toOrderResponse(saved);
    }

    // --- cancel (single source of truth — used by both the DTO-based and id-based call sites) ---

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

    // --- reads ---

    public List<OrderResponse> getAllOrders(User user) {
        return ordersRepo.getOrdersByUser(user)
                .stream()
                .map(orderMapper::toOrderResponse)
                .toList();
    }

    public OrderResponse getOrderById(Long orderId, User user) {
        return orderMapper.toOrderResponse(getOwnedOrder(orderId, user));
    }

    private List<OrderResponse> getOrdersByStatus(User user, List<OrderStatus> statusList) {
        return ordersRepo.findByUserAndStatusInOrderByCreatedAtDesc(user, statusList)
                .stream()
                .map(orderMapper::toOrderResponse)
                .toList();
    }

    public List<OrderResponse> getOpenOrders(User user) {
        return getOrdersByStatus(user, List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED));
    }

    public List<OrderResponse> getOrderHistory(User user) {
        return getOrdersByStatus(user, List.of(OrderStatus.FILLED, OrderStatus.CANCELLED));
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
}