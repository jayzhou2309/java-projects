package project.demotradingapp.dto.transaction;

import lombok.Builder;
import lombok.Data;
import project.demotradingapp.model.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class TransactionResponse {
    private Long id;
    private TransactionType type;
    private BigDecimal amount;
    private LocalDateTime createdAt;
}
