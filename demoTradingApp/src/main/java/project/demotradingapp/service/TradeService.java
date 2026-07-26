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
    public TradeResponse getTrade(Long tradeId) {
        Trades trade = tradesRepo.findById(tradeId)
                .orElseThrow(() -> new IllegalArgumentException("Trade ID does not exist"));
        return tradeMapper.toTradesResponse(trade);
    }

    public List<TradeResponse> getUserTrades(User user) {
        List<Trades> tradesList = tradesRepo.findByBuyOrderUserOrSellOrderUser(user, user);
        return tradesList.stream()
                .map(tradeMapper::toTradesResponse)
                .toList();
    }

    public List<TradeResponse> getStockTrades(Long stockId) {
        List<Trades> tradesList = tradesRepo.findByStockId(stockId);
        return tradesList.stream()
                .map(tradeMapper::toTradesResponse)
                .toList();
    }

    public List<TradeResponse> getOrderTrades(Long orderId) {
        List<Trades> tradesList = tradesRepo.findByBuyOrderIdAndSellOrderId(orderId, orderId);
        return tradesList.stream()
                .map(tradeMapper::toTradesResponse)
                .toList();
    }
}
