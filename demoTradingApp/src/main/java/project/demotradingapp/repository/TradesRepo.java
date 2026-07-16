package project.demotradingapp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.demotradingapp.entity.TradesEntity;

@Repository
public interface TradesRepo extends JpaRepository<TradesEntity, Long> {
}
