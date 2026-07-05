package project.paymentprocessing.dto.response;

import lombok.Builder;
import lombok.Data;
import project.paymentprocessing.model.Currency;
import project.paymentprocessing.model.Status;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
@Data
public class PaymentResponse {
    private Long id;
    private BigDecimal amount;
    private Currency currency;
    private Status status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
