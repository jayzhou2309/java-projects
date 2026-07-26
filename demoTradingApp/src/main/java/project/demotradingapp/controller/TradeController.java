package project.demotradingapp.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import project.demotradingapp.dto.trade.TradeResponse;
import project.demotradingapp.security.jwt.UserAccountDetails;
import project.demotradingapp.service.TradeService;

import java.util.List;

@RequiredArgsConstructor
@RequestMapping("/api/trades")
@RestController
public class TradeController {
    private final TradeService tradeService;

    // GET /trades/{id}
    @GetMapping("/{id}")
    public ResponseEntity<TradeResponse> getTradesById(@PathVariable Long id, @AuthenticationPrincipal UserAccountDetails userAccountDetails){
        return ResponseEntity.ok(tradeService.getTrade(id, userAccountDetails.getUser()));

    }
    // GET /trades/user
    @GetMapping("/user")
    public ResponseEntity<List<TradeResponse>> getTradesByUser(@AuthenticationPrincipal UserAccountDetails userAccountDetails){
        return ResponseEntity.ok(tradeService.getUserTrades(userAccountDetails.getUser()));
    }
    // GET /trades/order/{orderId}
    @GetMapping("/orders/{id}")
    public ResponseEntity<List<TradeResponse>> getTradesByOrderId(@PathVariable Long id, @AuthenticationPrincipal UserAccountDetails userAccountDetails){
        return ResponseEntity.ok(tradeService.getOrderTrades(id, userAccountDetails.getUser()));
    }
    // GET /trades/stock/{stockId}
    @GetMapping("/stock/{id}")
    public ResponseEntity<List<TradeResponse>> getTradesByStockId(@PathVariable Long id, @AuthenticationPrincipal UserAccountDetails userAccountDetails){
        return ResponseEntity.ok(tradeService.getStockTrades(id, userAccountDetails.getUser()));
    }
}
