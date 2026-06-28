package project.demopaymentapp.controller;

import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import project.demopaymentapp.dto.response.AccountResponse;
import project.demopaymentapp.model.Account;
import project.demopaymentapp.service.AccountService;

import java.math.BigDecimal;

@RestController
@RequestMapping("/accounts")
@AllArgsConstructor
public class AccountController {
    private final AccountService accountService;

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable Long id){
        Account account = accountService.findById(id);
        return ResponseEntity.ok(AccountResponse.builder()
                .id(account.getId())
                .balance(account.getBalance())
                .currencies(account.getCurrency())
                .build());
    }
    // getbalance
    @GetMapping("/{id}/balance")
    public ResponseEntity<BigDecimal> getAccountBalance(@PathVariable Long id){
        Account account = accountService.findById(id);
        return ResponseEntity.ok(account.getBalance());
    }
}
