package project.demoshippingappv1.kafka;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import project.demoshippingappv1.dto.event.ShipmentEvent;

@Slf4j
@Component
public class ShipmentProducer {
    private final KafkaTemplate<String, ShipmentEvent> kafkaTemplate;
    @Value("${app.kafka.topics.shipment-created}")
    private String topicCreated;

    @Value("${app.kafka.topics.shipment-status-updated}")
    private String topicStatusUpdated;

    public ShipmentProducer(KafkaTemplate<String, ShipmentEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendEvent(String topic, String key, ShipmentEvent event){
        kafkaTemplate.send(topic, key, event).whenComplete((result, ex) -> {
            if (ex != null){
                log.error("Failed to publish to topic [{}] with key [{}]: {}",
                        topic, key, ex.getMessage(), ex);
            } else {
                log.info("Published to [{}] partition [{}] offset [{}] with key [{}]",
                        topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        key);
            }
        });
    }

    public void publishCreated(ShipmentEvent event){
        sendEvent(topicCreated, event.getTrackingNumber(), event);
    }

    public void publishStatusUpdated(ShipmentEvent event){
        sendEvent(topicStatusUpdated, event.getTrackingNumber(), event);
    }
}
