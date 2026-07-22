package project.demotradingapp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.demotradingapp.entity.Portfolio;
import project.demotradingapp.entity.User;

import java.util.List;

@Repository
public interface PortfolioRepo extends JpaRepository<Portfolio, Long> {

    Portfolio findByUserUsername(String user);

    Portfolio findByUser(User user);
}
