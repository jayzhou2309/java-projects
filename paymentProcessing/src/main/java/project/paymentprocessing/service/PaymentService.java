package project.paymentprocessing.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import project.paymentprocessing.entity.Payment;
import project.paymentprocessing.model.Status;
import project.paymentprocessing.repository.PaymentRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@AllArgsConstructor
public class PaymentService {
    private final PaymentRepository paymentRepository;

    public Payment createPayment(Payment payment){
        payment.setStatus(Status.PENDING);
        payment.setCreatedAt(LocalDateTime.now());
        payment.setUpdatedAt(LocalDateTime.now());

        return paymentRepository.save(payment);
    }

    public void approvePayment(Payment payment){
        payment.setStatus(Status.TRANSFERRED);
        payment.setUpdatedAt(LocalDateTime.now());

        paymentRepository.save(payment);
    }

    public void rejectPayment(Payment payment){
        payment.setStatus(Status.DECLINED);
        payment.setUpdatedAt(LocalDateTime.now());

        paymentRepository.save(payment);
    }

    public Optional<Payment> getPayment(Long id){
        Optional<Payment> payment = paymentRepository.findById(id);
        return payment;
    }

    public List<Payment> getAllPayments(){
        return paymentRepository.findAll();
    }

    public void deletePayment(Long id){
        paymentRepository.deleteById(id);
    }
}
