package project.demotradingapp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.demotradingapp.entity.OrdersEntity;

@Repository
public interface OrdersRepo extends JpaRepository<OrdersEntity, Long> {
}
