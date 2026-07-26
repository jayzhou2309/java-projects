package project.demotradingapp.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.demotradingapp.dto.portfolio.DepositRequest;
import project.demotradingapp.dto.portfolio.PortfolioResponse;
import project.demotradingapp.dto.portfolio.WithdrawRequest;
import project.demotradingapp.entity.Orders;
import project.demotradingapp.entity.Portfolio;
import project.demotradingapp.entity.User;
import project.demotradingapp.mapper.PortfolioMapper;
import project.demotradingapp.repository.PortfolioRepo;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class PortfolioService {
    private final PortfolioRepo portfolioRepo;
    private final PortfolioMapper portfolioMapper;

    public PortfolioResponse getPortfolio(User user){
        Portfolio portfolio = portfolioRepo.findByUserUsername(user.getUsername());
        return portfolioMapper.toPortfolioResponse(portfolio);
    }

    @Transactional
    public PortfolioResponse deposit(DepositRequest request, User user){
        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0){
            throw new IllegalArgumentException("Deposit cant be negative");
        }
        Portfolio portfolio = portfolioRepo.findByUser(user);
        portfolio.setAvailableCash(
                portfolio.getAvailableCash().add(request.getAmount())
        );
        portfolioRepo.save(portfolio);
        return portfolioMapper.toPortfolioResponse(portfolio);
    }

    @Transactional
    public PortfolioResponse withdraw(WithdrawRequest request, User user){
        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0){
            throw new IllegalArgumentException("Withdrawals must be positive");
        }
        Portfolio portfolio = portfolioRepo.findByUser(user);
        if (request.getAmount().compareTo(portfolio.getAvailableCash()) > 0){
            throw new IllegalArgumentException("Insufficient Funds");
        }
        portfolio.setAvailableCash(
                portfolio.getAvailableCash().subtract(request.getAmount())
        );
        portfolioRepo.save(portfolio);
        return portfolioMapper.toPortfolioResponse(portfolio);
    }

    public boolean hasSufficientCash(Portfolio portfolio, BigDecimal amount){
        return portfolio.getAvailableCash().compareTo(amount) >= 0;
    }

    @Transactional
    public void reserveCash(Portfolio portfolio, BigDecimal amount){
        if (!hasSufficientCash(portfolio, amount)){
            throw new IllegalArgumentException("Not enough Available Cash");
        }
        portfolio.setAvailableCash(portfolio.getAvailableCash().subtract(amount));
        portfolio.setReservedCash(portfolio.getReservedCash().add(amount));
        portfolioRepo.save(portfolio);
    }

    @Transactional
    public void releaseReservedCash(Portfolio portfolio, BigDecimal amount){
        portfolio.setReservedCash(portfolio.getReservedCash().subtract(amount));
        portfolio.setAvailableCash(portfolio.getAvailableCash().add(amount));
        portfolioRepo.save(portfolio);
    }

    @Transactional
    public void deductReservedCash(Portfolio portfolio, BigDecimal amount){
        if (amount.compareTo(portfolio.getReservedCash()) > 0){
            throw new IllegalArgumentException("Insufficient Reserved Cash");
        }
        portfolio.setReservedCash(portfolio.getReservedCash().subtract(amount));
        portfolioRepo.save(portfolio);
    }

    @Transactional
    public void refundDifference(Portfolio portfolio, BigDecimal reservedAmount, BigDecimal actualAmount){
        BigDecimal refund = reservedAmount.subtract(actualAmount);
        portfolio.setAvailableCash(portfolio.getAvailableCash().add(refund));
        portfolioRepo.save(portfolio);
    }

    @Transactional
    public void settleBuyerCash(
            Orders buyOrder,
            BigDecimal executionPrice,
            BigDecimal matchedQuantity
    ) {
        Portfolio portfolio = buyOrder.getUser().getPortfolio();
        BigDecimal reservedAmount = buyOrder.getPrice().multiply(matchedQuantity);
        BigDecimal actualAmount = executionPrice.multiply(matchedQuantity);

        deductReservedCash(portfolio, reservedAmount);
        refundDifference(portfolio, reservedAmount, actualAmount);
    }

    @Transactional
    public void settleSellerCash(
            Orders sellOrder,
            BigDecimal executionPrice,
            BigDecimal matchedQuantity
    ) {
        Portfolio portfolio = sellOrder.getUser().getPortfolio();
        BigDecimal proceeds = executionPrice.multiply(matchedQuantity);
        portfolio.setAvailableCash(portfolio.getAvailableCash().add(proceeds));
        portfolioRepo.save(portfolio);
    }
}
