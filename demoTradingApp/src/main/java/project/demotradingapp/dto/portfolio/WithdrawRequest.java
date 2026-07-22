package project.demotradingapp.dto.portfolio;

import lombok.Data;
import project.demotradingapp.entity.User;

import java.math.BigDecimal;

@Data
public class WithdrawRequest {
    private User user;
    private BigDecimal amount;
}
