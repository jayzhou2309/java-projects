package project.demoshippingapp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Shipment {
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
