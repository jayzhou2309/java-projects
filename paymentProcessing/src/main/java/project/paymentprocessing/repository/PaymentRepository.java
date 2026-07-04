package project.paymentprocessing.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.paymentprocessing.entity.Payment;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
}
