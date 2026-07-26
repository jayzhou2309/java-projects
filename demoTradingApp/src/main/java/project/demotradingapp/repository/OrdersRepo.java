package project.demotradingapp.repository;

import org.antlr.v4.runtime.atn.SemanticContext;
import org.aspectj.weaver.ast.Or;
import org.hibernate.query.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.demotradingapp.entity.Orders;
import project.demotradingapp.entity.User;
import project.demotradingapp.model.OrderStatus;
import project.demotradingapp.model.PositionSide;

import java.util.List;

@Repository
public interface OrdersRepo extends JpaRepository<Orders, Long> {


    List<Orders> findByStockIdAndSideAndStatusOrderByPriceDescCreatedAtAsc(Long stockId, PositionSide buy, List<OrderStatus> statusList);

    List<Orders> findByStockIdAndSideAndStatusOrderByPriceAscCreatedAtAsc(Long stockId, PositionSide sell, List<OrderStatus> statusList);

    List<Orders> getOrdersByUser(User user);

    List<Orders> findByUserAndStatusInOrderCreatedByDesc(User user, List<OrderStatus> statusList);
}
