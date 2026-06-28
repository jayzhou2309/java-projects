package project.demopaymentapp.dto.response;

import lombok.Builder;
import lombok.Data;
import project.demopaymentapp.model.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class TransactionResponse {
    private Long id;
    private Long senderAccountId;
    private Long receiverAccountId;
    private BigDecimal amount;
    private TransactionStatus status;
    private LocalDateTime created_at;
}
