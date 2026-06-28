package project.demopaymentapp.dto.response;

import lombok.Builder;
import lombok.Data;
import project.demopaymentapp.model.Currencies;

import java.math.BigDecimal;

@Data
@Builder
public class AccountResponse {
    private Long id;
    private BigDecimal balance;
    private Currencies currencies;
}
