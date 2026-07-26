package project.demotradingapp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.demotradingapp.entity.Orders;
import project.demotradingapp.entity.User;
import project.demotradingapp.model.OrderStatus;
import project.demotradingapp.model.PositionSide;

import java.util.List;

@Repository
public interface OrdersRepo extends JpaRepository<Orders, Long> {


    List<Orders> findByStockIdAndSideAndStatusInOrderByPriceDescCreatedAtAsc(Long stockId, PositionSide buy, List<OrderStatus> statusList);

    List<Orders> findByStockIdAndSideAndStatusInOrderByPriceDescCreatedAtDesc(Long stockId, PositionSide sell, List<OrderStatus> statusList);

    List<Orders> getOrdersByUser(User user);

    List<Orders> findByUserAndStatusInOrderByCreatedAtDesc(User user, List<OrderStatus> statusList);
}
