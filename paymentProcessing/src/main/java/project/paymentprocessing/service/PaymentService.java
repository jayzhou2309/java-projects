package project.paymentprocessing.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import project.paymentprocessing.dto.request.CreatePaymentRequest;
import project.paymentprocessing.dto.response.PaymentResponse;
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

    public PaymentResponse createPayment(CreatePaymentRequest request){
        Payment payment = Payment.builder()
                .senderAccount(request.getSenderAccount())
                .receiverAccount(request.getReceiverAccount())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status(Status.PENDING)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        Payment saved = paymentRepository.save(payment);

        return PaymentResponse.builder()
                .id(saved.getId())
                .amount(saved.getAmount())
                .currency(saved.getCurrency())
                .status(saved.getStatus())
                .createdAt(saved.getCreatedAt())
                .updatedAt(saved.getUpdatedAt())
                .build();

    }

    public void approvePayment(Long id){
        Payment payment = paymentRepository.findById(id).orElseThrow();
        payment.setStatus(Status.TRANSFERRED);
        payment.setUpdatedAt(LocalDateTime.now());
        paymentRepository.save(payment);
    }

    public void rejectPayment(Long id){
        Payment payment = paymentRepository.findById(id).orElseThrow();
        payment.setStatus(Status.DECLINED);
        payment.setUpdatedAt(LocalDateTime.now());

        paymentRepository.save(payment);
    }

    public PaymentResponse getPayment(Long id){
        Payment payment = paymentRepository.findById(id).orElseThrow();

        return PaymentResponse.builder()
                .id(payment.getId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .status(payment.getStatus())
                .createdAt(payment.getCreatedAt())
                .updatedAt(payment.getUpdatedAt())
                .build();

    }

    public List<PaymentResponse> getAllPayments(){
        return paymentRepository.findAll()
                .stream()
                .map(payment -> PaymentResponse.builder()
                        .id(payment.getId())
                        .amount(payment.getAmount())
                        .currency(payment.getCurrency())
                        .status(payment.getStatus())
                        .createdAt(payment.getCreatedAt())
                        .updatedAt(payment.getUpdatedAt())
                        .build())
                .toList();
    }

    public void deletePayment(Long id){
        paymentRepository.deleteById(id);
    }
}
