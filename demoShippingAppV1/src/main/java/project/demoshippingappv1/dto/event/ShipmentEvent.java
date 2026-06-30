package project.demoshippingappv1.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import project.demoshippingappv1.model.EventType;
import project.demoshippingappv1.model.ShipmentStatus;


@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ShipmentEvent {
    private Long id;
    private String trackingNumber;
    private String senderName;
    private String receiverName;
    private String originAddress;
    private String destAddress;
    private ShipmentStatus oldStatus;
    private ShipmentStatus newStatus;
    private EventType eventType;

    public static ShipmentEvent created(Long id, String trackingNumber,
                                        String sender, String receiver, String originAddress, String destAddress,
                                        ShipmentStatus newStatus){
        // Build New Event
        return ShipmentEvent.builder()
                .id(id)
                .trackingNumber(trackingNumber)
                .senderName(sender)
                .receiverName(receiver)
                .originAddress(originAddress)
                .destAddress(destAddress)
                .newStatus(newStatus)
                .eventType(EventType.CREATED)
                .build();
    }

    public static ShipmentEvent statusUpdated(Long id, String trackingNumber,
                                              ShipmentStatus oldStatus, ShipmentStatus newStatus){
        return ShipmentEvent.builder()
                .id(id)
                .trackingNumber(trackingNumber)
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .eventType(EventType.STATUS_UPDATED)
                .build();
    }
}
