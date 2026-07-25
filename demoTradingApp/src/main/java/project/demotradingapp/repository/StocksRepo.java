package project.demotradingapp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.demotradingapp.entity.Stock;

import java.util.Optional;

@Repository
public interface StocksRepo extends JpaRepository<Stock, Long> {
    Optional<Stock> findById(Long id);

    Optional<Stock> findBySymbol(String ticker);

    boolean existsBySymbol(String ticker);
}
