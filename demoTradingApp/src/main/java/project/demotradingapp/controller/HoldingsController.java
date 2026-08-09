package project.demotradingapp.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.demotradingapp.dto.portfolio.HoldingResponse;
import project.demotradingapp.entity.Portfolio;
import project.demotradingapp.entity.Stock;
import project.demotradingapp.security.jwt.UserAccountDetails;
import project.demotradingapp.service.HoldingService;
import project.demotradingapp.service.StockService;

import java.util.List;

@RestController
@RequestMapping("/api/holdings/")
@RequiredArgsConstructor
public class HoldingsController {
    private final HoldingService holdingService;
    private final StockService stockService;

    // GET /holdings
    @GetMapping
    public ResponseEntity<List<HoldingResponse>> getHoldings(@AuthenticationPrincipal UserAccountDetails userAccountDetails){
        return ResponseEntity.ok(holdingService.getHoldingsForPortfolio(userAccountDetails.getUser().getPortfolio()));
    }
    // GET /holdings/{stockId}
    @GetMapping("{stockId}")
    public ResponseEntity<HoldingResponse> getHoldingForStock(@PathVariable Long stockId, @AuthenticationPrincipal UserAccountDetails userAccountDetails){
        Portfolio portfolio = userAccountDetails.getUser().getPortfolio();
        Stock stock = stockService.getStock(stockId);
        return ResponseEntity.ok(holdingService.getHoldingForStockResponse(portfolio, stock));
    }
}
