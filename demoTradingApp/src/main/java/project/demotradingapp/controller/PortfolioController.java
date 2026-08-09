package project.demotradingapp.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import project.demotradingapp.dto.portfolio.DepositRequest;
import project.demotradingapp.dto.portfolio.PortfolioResponse;
import project.demotradingapp.dto.portfolio.WithdrawRequest;
import project.demotradingapp.security.jwt.UserAccountDetails;
import project.demotradingapp.service.PortfolioService;

@RestController
@RequestMapping("/api/portfolio/")
@RequiredArgsConstructor
public class PortfolioController {
    private final PortfolioService portfolioService;

    // Get Portfolio
    @GetMapping
    public ResponseEntity<PortfolioResponse> getPortfolio(@AuthenticationPrincipal UserAccountDetails userAccountDetails){
        return ResponseEntity.ok(portfolioService.getPortfolio(userAccountDetails.getUser()));
    }
    // Post deposit
    @PostMapping("deposit")
    public ResponseEntity<PortfolioResponse> deposit(@RequestBody DepositRequest request, @AuthenticationPrincipal UserAccountDetails userAccountDetails){
        return ResponseEntity.ok(portfolioService.deposit(request, userAccountDetails.getUser()));
    }
    // Post withdraw
    @PostMapping("withdraw")
    public ResponseEntity<PortfolioResponse> withdraw(@RequestBody WithdrawRequest request, @AuthenticationPrincipal UserAccountDetails userAccountDetails){
        return ResponseEntity.ok(portfolioService.withdraw(request, userAccountDetails.getUser()));
    }

}
