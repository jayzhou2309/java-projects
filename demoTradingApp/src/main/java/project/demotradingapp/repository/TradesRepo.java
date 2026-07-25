package project.demotradingapp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.demotradingapp.entity.Trades;
import project.demotradingapp.entity.User;

import java.util.List;

@Repository
public interface TradesRepo extends JpaRepository<Trades, Long> {
    List<Trades> findByBuyOrderUserOrSellOrderUser(User user, User user1);

    List<Trades> findByStockId(Long stockId);

    List<Trades> findByBuyOrderIdAndSellOrderId(Long buyOrderId, Long sellOrderId);
}
