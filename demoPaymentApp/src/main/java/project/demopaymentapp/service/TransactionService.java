package project.demopaymentapp.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import project.demopaymentapp.mappers.AccountMapper;
import project.demopaymentapp.mappers.AppUserMapper;
import project.demopaymentapp.mappers.TransactionMapper;
import project.demopaymentapp.model.Account;
import project.demopaymentapp.model.Transaction;
import project.demopaymentapp.model.TransactionStatus;

import java.math.BigDecimal;
import java.util.List;

@Service
@AllArgsConstructor
public class TransactionService {
    private final AccountMapper accountMapper;
    private final AppUserMapper appUserMapper;
    private final TransactionMapper transactionMapper;

    // Transfer
    public Transaction transfer(Long senderAccountId, Long receiverAccountId, BigDecimal amount){
        // Validate Account
        var sender = accountMapper.findByUserId(senderAccountId)
            .orElseThrow(() -> new RuntimeException("Sender Doesnt Exist"));
        var receiver = accountMapper.findByUserId(receiverAccountId)
                .orElseThrow(() -> new RuntimeException("Receiver Doesnt Exist"));
        // Validate Balance
        if (sender.getBalance().compareTo(amount) < 0){
            var transaction = Transaction.builder()
                    .senderAccountId(sender.getId())
                    .receiverAccountId(receiver.getId())
                    .amount(amount)
                    .status(TransactionStatus.FAILED)
                    .build();
            transactionMapper.insert(transaction);
            throw new RuntimeException("Insufficient Balance");

        }
        // Amend Balance
        sender.setBalance(sender.getBalance().subtract(amount));
        receiver.setBalance(receiver.getBalance().add(amount));
        // Make Transaction Details
        var transaction = Transaction.builder()
                .senderAccountId(sender.getId())
                .receiverAccountId(receiver.getId())
                .amount(amount)
                .status(TransactionStatus.SUCCESS)
                .build();
        transactionMapper.insert(transaction);
        return transaction;
    }

    // findById
    public Transaction findById(Long id){
        return transactionMapper.findById(id)
                .orElseThrow(() -> new RuntimeException("Transaction not Found"));
    }
    // findByAccountId
    public List<Transaction> findByAccountId(Long accountId){
        return transactionMapper.findByAccountId(accountId);
    }
}
