package project.demoshippingapp.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import project.demoshippingapp.model.EventType;
import project.demoshippingapp.model.ShipmentStatus;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ShipmentEvent {
    private Long shipmentId;
    private String trackingNumber;
    private String senderName;
    private String receiverName;
    private ShipmentStatus oldStatus;
    private ShipmentStatus newStatus;
    private EventType eventType;

    public static ShipmentEvent created(Long id, String tracking,
                                        String sender, String receiver,
                                        ShipmentStatus status) {
        return ShipmentEvent.builder()
                .shipmentId(id)
                .trackingNumber(tracking)
                .senderName(sender)
                .receiverName(receiver)
                .newStatus(status)
                .eventType(EventType.CREATED)
                .build();
    }
    public static ShipmentEvent statusUpdated(Long id, String tracking,
                                              ShipmentStatus newStatus, ShipmentStatus oldStatus){
        return ShipmentEvent.builder()
                .shipmentId(id)
                .trackingNumber(tracking)
                .oldStatus(oldStatus)
                .newStatus(newStatus)
                .eventType(EventType.STATUS_UPDATED)
                .build();
    }

}
