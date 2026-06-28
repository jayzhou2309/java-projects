package project.demopaymentapp.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import project.demopaymentapp.mappers.AccountMapper;
import project.demopaymentapp.mappers.AppUserMapper;
import project.demopaymentapp.model.Account;
import project.demopaymentapp.model.AppUser;
import project.demopaymentapp.model.Currencies;

import java.math.BigDecimal;

@Service
@AllArgsConstructor
public class AccountService {
    private final AccountMapper accountMapper;
    private final AppUserMapper appUserMapper;

    // Create Account
    public Account createAccount(AppUser user){
        var account = Account.builder()
                .user_id(user.getId())
                .balance(BigDecimal.ZERO)
                .currency(Currencies.SGD)
                .build();

        accountMapper.insert(account);
        return account;
    }

    // Find By Id
    public Account findById(Long id){
        return (accountMapper.findById(id)
                .orElseThrow(() -> new RuntimeException("Account Doesnt Exist")));
    }

    // Find By UserId
    public Account findByUserId(Long userId){
        return accountMapper.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("User Doesnt Exist"));
    }
}
