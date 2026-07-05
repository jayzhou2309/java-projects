package project.paymentprocessing.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import project.paymentprocessing.model.Currency;

import java.math.BigDecimal;

@Data
public class CreatePaymentRequest {
    @NotBlank
    private String senderAccount;
    @NotBlank
    private String receiverAccount;
    @NotBlank
    @Positive
    private BigDecimal amount;
    @NotBlank
    private Currency currency;
}
