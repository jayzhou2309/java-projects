package project.paymentprocessing.dto.request;

import lombok.Data;
import project.paymentprocessing.model.Currencies;
import project.paymentprocessing.model.Status;

import java.math.BigDecimal;

@Data
public class CreatePaymentRequest {
    private String senderAccount;
    private String receiverAccount;
    private BigDecimal amount;
    private Currencies currency;
}
