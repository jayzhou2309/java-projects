package project.demotradingapp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.demotradingapp.entity.Holdings;
import project.demotradingapp.entity.Portfolio;
import project.demotradingapp.entity.Stock;

import java.util.List;
import java.util.Optional;

@Repository
public interface HoldingsRepo extends JpaRepository<Holdings, Long> {

    Optional<Holdings> findByPortfolioAndStock(Portfolio portfolio, Stock stock);

    List<Holdings> findByPortfolio(Portfolio portfolio);
}
