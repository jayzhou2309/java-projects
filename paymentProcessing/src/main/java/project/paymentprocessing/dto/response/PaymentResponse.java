package project.paymentprocessing.dto.response;

import lombok.Builder;
import lombok.Data;
import project.paymentprocessing.model.Status;

import java.time.LocalDateTime;

@Builder
@Data
public class PaymentResponse {
    private Status status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
