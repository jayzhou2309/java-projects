package project.demotradingapp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.demotradingapp.entity.StocksEntity;

@Repository
public interface StocksRepo extends JpaRepository<StocksEntity, Long> {
}
