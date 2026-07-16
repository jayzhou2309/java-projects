package project.demotradingapp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.demotradingapp.entity.HoldingsEntity;

@Repository
public interface HoldingsRepo extends JpaRepository<HoldingsEntity, Long> {
}
