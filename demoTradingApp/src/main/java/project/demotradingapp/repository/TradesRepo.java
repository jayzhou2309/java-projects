package project.demotradingapp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import project.demotradingapp.entity.Trades;
import project.demotradingapp.entity.User;

import java.util.List;

@Repository
public interface TradesRepo extends JpaRepository<Trades, Long> {

    List<Trades> findByBuyOrderUserOrSellOrderUser(User buyUser, User sellUser);

    List<Trades> findByBuyOrderIdOrSellOrderId(Long buyOrderId, Long sellOrderId);

    @Query("""
        SELECT t FROM Trades t
        WHERE t.stock.id = :stockId
        AND (t.buyOrder.user = :user OR t.sellOrder.user = :user)
        """)
    List<Trades> findByStockIdAndUser(@Param("stockId") Long stockId, @Param("user") User user);
}