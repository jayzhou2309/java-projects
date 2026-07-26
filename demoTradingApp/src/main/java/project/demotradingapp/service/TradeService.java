package project.demotradingapp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.demotradingapp.dto.trade.TradeResponse;
import project.demotradingapp.entity.Trades;
import project.demotradingapp.entity.User;
import project.demotradingapp.mapper.TradeMapper;
import project.demotradingapp.repository.TradesRepo;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TradeService {

    private final TradesRepo tradesRepo;
    private final TradeMapper tradeMapper;
    private final OrderService orderService;

    public boolean authorizeUser(Trades trade, User user){
            boolean isBuyer = trade.getBuyOrder().getUser().getId().equals(user.getId());
            boolean isSeller = trade.getSellOrder().getUser().getId().equals(user.getId());
            if (!isBuyer && !isSeller){
                throw new IllegalArgumentException("Not allowed to view this trade");
            }
            return true;
        }

    public TradeResponse getTrade(Long tradeId, User user) {
        Trades trade = tradesRepo.findById(tradeId)
                .orElseThrow(() -> new IllegalArgumentException("Trade ID does not exist"));
        authorizeUser(trade, user);
        return tradeMapper.toTradesResponse(trade);
    }

    public List<TradeResponse> getUserTrades(User user) {
        List<Trades> tradesList = tradesRepo.findByBuyOrderUserOrSellOrderUser(user, user);
        return tradesList.stream()
                .map(tradeMapper::toTradesResponse)
                .toList();
    }

    public List<TradeResponse> getStockTrades(Long stockId, User user) {
        List<Trades> tradesList = tradesRepo.findByStockIdAndUser(stockId, user);
        return tradesList.stream()
                .map(tradeMapper::toTradesResponse)
                .toList();
    }

    public List<TradeResponse> getOrderTrades(Long orderId, User user) {
        orderService.getOwnedOrder(orderId, user);
        List<Trades> tradesList = tradesRepo.findByBuyOrderIdOrSellOrderId(orderId, orderId);
        return tradesList.stream()
                .map(tradeMapper::toTradesResponse)
                .toList();
    }
}
