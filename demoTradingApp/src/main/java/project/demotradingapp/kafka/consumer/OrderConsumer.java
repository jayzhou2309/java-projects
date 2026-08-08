package project.demotradingapp.kafka.consumer;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import project.demotradingapp.service.OrderService;

@Service
@RequiredArgsConstructor
public class OrderConsumer {
    private final OrderService orderService;
    private static final Logger log = LoggerFactory.getLogger(OrderConsumer.class);

    @KafkaListener(topics = "order-created")
    public void consume(Long stockId){
        try {
            orderService.triggerMatching(stockId);
        } catch (Exception ex){
            log.error("Failed to run matching for stockId={}", stockId, ex);
        }

    }
}
