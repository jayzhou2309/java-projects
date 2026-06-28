package project.demopaymentapp.controller;

import lombok.AllArgsConstructor;
import org.apache.ibatis.annotations.Param;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import project.demopaymentapp.dto.requests.TransferRequest;
import project.demopaymentapp.dto.response.TransactionResponse;
import project.demopaymentapp.model.Transaction;
import project.demopaymentapp.model.TransactionStatus;
import project.demopaymentapp.service.AccountService;
import project.demopaymentapp.service.TransactionService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/payments")
@AllArgsConstructor
public class TransactionController {
    private final AccountService accountService;
    private final TransactionService transactionService;

    // Response Builder Template
    private TransactionResponse toResponse(Transaction transaction){
        TransactionResponse build = TransactionResponse.builder()
                .id(transaction.getId())
                .senderAccountId(transaction.getSenderAccountId())
                .receiverAccountId(transaction.getReceiverAccountId())
                .amount(transaction.getAmount())
                .status(transaction.getStatus())
                .created_at(LocalDateTime.from(transaction.getCreatedAt()))
                .build();
        return build;
    }

    // Transfers
    @PostMapping("/transfers")
    public ResponseEntity<TransactionResponse> transfer(@RequestBody TransferRequest request){
        Transaction transaction = transactionService.transfer(request.getSenderAccountId(), request.getReceiverAccountId(), request.getAmount());
        return ResponseEntity.status(201).body(toResponse(transaction));
    }
    // Get Transaction by Id
    @GetMapping("/transfers/{id}")
    public ResponseEntity<TransactionResponse> getTransactionById(@PathVariable Long transactionId){
        Transaction transaction = transactionService.findById(transactionId);
        return ResponseEntity.ok(toResponse(transaction));
    }
    // Get History
    @GetMapping("/transfers/{accountId}")
    public ResponseEntity<List<TransactionResponse>> getTransactionHistoryById(@PathVariable Long accountId){
        List<TransactionResponse> history = transactionService.findByAccountId(accountId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(history);

    }
}
