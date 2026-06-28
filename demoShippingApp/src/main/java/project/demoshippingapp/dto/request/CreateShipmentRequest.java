package project.demoshippingapp.dto.request;

import lombok.Data;

@Data
public class CreateShipmentRequest {
    private String senderName;
    private String receiverName;
    private String originAddress;
    private String destAddress;
}
