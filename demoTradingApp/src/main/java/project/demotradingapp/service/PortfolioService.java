package project.demotradingapp.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.demotradingapp.dto.portfolio.DepositRequest;
import project.demotradingapp.dto.portfolio.PortfolioResponse;
import project.demotradingapp.dto.portfolio.WithdrawRequest;
import project.demotradingapp.entity.Portfolio;
import project.demotradingapp.entity.User;
import project.demotradingapp.mapper.PortfolioMapper;
import project.demotradingapp.repository.PortfolioRepo;
import project.demotradingapp.repository.UsersRepo;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PortfolioService {
    private final PortfolioRepo portfolioRepo;
    private final PortfolioMapper portfolioMapper;

    public PortfolioResponse getPortfolio(User user){

        Portfolio portfolios = portfolioRepo.findByUserUsername(user.getUsername());

        return portfolioMapper.toPortfolioResponse(portfolios);
    }

    @Transactional
    public PortfolioResponse deposit(DepositRequest request){
        Portfolio portfolio = portfolioRepo.findByUser(request.getUser());

        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0){
            throw new RuntimeException("Deposit cant be negative");
        }
        portfolio.setAvailableCash(
                portfolio.getAvailableCash().add(request.getAmount())
        );
        return portfolioMapper.toPortfolioResponse(portfolio);
    }

    @Transactional
    public PortfolioResponse withdraw(WithdrawRequest request){
        Portfolio portfolio = portfolioRepo.findByUser(request.getUser());
        if (request.getAmount().compareTo(portfolio.getAvailableCash()) > 0){
            throw new RuntimeException("Insufficient Funds");
        }
        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0){
            throw new RuntimeException("Withdrawals must be positive");
        }
        portfolio.setAvailableCash(
                portfolio.getAvailableCash().subtract(request.getAmount())
        );
        return portfolioMapper.toPortfolioResponse(portfolio);
    }

}
