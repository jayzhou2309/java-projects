package project.demotradingapp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.demotradingapp.entity.RefreshTokensEntity;

@Repository
public interface RefreshTokensRepo extends JpaRepository<RefreshTokensEntity, Long> {
}
