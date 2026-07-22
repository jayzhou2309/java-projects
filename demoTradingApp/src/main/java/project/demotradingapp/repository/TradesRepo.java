package project.demotradingapp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.demotradingapp.entity.Trades;

@Repository
public interface TradesRepo extends JpaRepository<Trades, Long> {
}
