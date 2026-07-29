package project.demotradingapp.kafka.producer;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import project.demotradingapp.kafka.events.OrderCreatedEvent;

@Service
@RequiredArgsConstructor
public class OrderProducer {
    private final KafkaTemplate<String, OrderCreatedEvent> kafkaTemplate;
    public void publish(OrderCreatedEvent event){
        kafkaTemplate.send(
                "order-created", event
        );
    }
}
