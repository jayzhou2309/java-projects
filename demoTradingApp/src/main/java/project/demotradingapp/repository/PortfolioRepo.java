package project.demotradingapp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.demotradingapp.entity.Portfolio;
import project.demotradingapp.entity.User;

import java.util.List;
import java.util.Optional;

@Repository
public interface PortfolioRepo extends JpaRepository<Portfolio, Long> {

    Optional<Portfolio> findByUserUsername(String user);

    Optional<Portfolio> findByUser(User user);
}
