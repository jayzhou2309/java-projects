package project.demotradingapp.kafka.consumer;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import project.demotradingapp.kafka.events.OrderCreatedEvent;
import project.demotradingapp.service.OrderService;

@Service
@RequiredArgsConstructor
public class OrderConsumer {
    private final OrderService orderService;

    @KafkaListener(topics = "order-created")
    public void consume(OrderCreatedEvent event){
        orderService.placeOrder(event);
    }
}
