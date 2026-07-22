package project.demotradingapp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.demotradingapp.entity.Transactions;

@Repository
public interface TransactionsRepo extends JpaRepository<Transactions, Long> {
}
