package project.demotradingapp.kafka.producer;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderProducer {
    private final KafkaTemplate<String, Long> kafkaTemplate;
    private static final Logger log = LoggerFactory.getLogger(OrderProducer.class);

    public void publish(Long stockId){
        String key = String.valueOf(stockId);
        kafkaTemplate.send(
                "order-created", key, stockId
        ).whenComplete((result, ex) -> {
            if (ex != null) log.error("Failed to publish order-created event");
        });
    }
}
