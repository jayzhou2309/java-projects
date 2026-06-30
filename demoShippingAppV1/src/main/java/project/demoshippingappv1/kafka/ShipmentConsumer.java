package project.demoshippingappv1.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import project.demoshippingappv1.dto.event.ShipmentEvent;

@Slf4j
@Component
public class ShipmentConsumer {
    @KafkaListener(topics = {"${app.kafka.topics.shipment-created}","${app.kafka.topics.shipment-status-updated}"},
    groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ShipmentEvent event){
        log.info("Received event [{}] for shipment [{}] - status {}",
                event.getEventType(), event.getTrackingNumber(), event.getNewStatus());
    }
}
