package project.demoshippingappv1.dto.response;

import lombok.Builder;
import lombok.Data;
import project.demoshippingappv1.model.ShipmentStatus;

import java.time.LocalDateTime;

@Data
@Builder
public class ShipmentResponse {
    private Long id;
    private String trackingNumber;
    private String senderName;
    private String receiverName;
    private String originAddress;
    private String destAddress;
    private ShipmentStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
