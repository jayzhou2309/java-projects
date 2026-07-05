package project.paymentprocessing.controller;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;
import project.paymentprocessing.dto.request.CreatePaymentRequest;
import project.paymentprocessing.dto.response.PaymentResponse;
import project.paymentprocessing.service.PaymentService;

import java.util.List;

@RestController
@RequestMapping("api/v1/payments")
@AllArgsConstructor
public class PaymentController {
    private final PaymentService paymentService;

    // Create Payment
    @PostMapping
    public PaymentResponse createPayment(@Valid @RequestBody CreatePaymentRequest request){
        return paymentService.createPayment(request);
    }

    @GetMapping("/{id}")
    public PaymentResponse getPayment(@PathVariable Long id){
        return paymentService.getPayment(id);
    }

    @GetMapping
    public List<PaymentResponse> getAllPayment(){
        return paymentService.getAllPayments();
    }

    @PatchMapping("/{id}/approve")
    public void approvePayment(@PathVariable Long id){
        paymentService.approvePayment(id);
    }

    @PatchMapping("/{id}/reject")
    public void rejectPayment(@PathVariable Long id){
        paymentService.rejectPayment(id);
    }

    @DeleteMapping("/{id}")
    public void deletePayment(@PathVariable Long id){
        paymentService.deletePayment(id);
    }








}
