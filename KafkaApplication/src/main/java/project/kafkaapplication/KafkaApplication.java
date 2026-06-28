package project.kafkaapplication;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootApplication
public class KafkaApplication {

    public static void main(String[] args) {
        SpringApplication.run(KafkaApplication.class, args);
    }

    @Bean
    NewTopic greetings(){
        return TopicBuilder.name("greetings").partitions(1).replicas(1).build();
    }

    @Bean
    ApplicationRunner runner(KafkaTemplate<String, String> template){
        return args -> template.send("greetings", "Hello Kafka");
    }

    @KafkaListener(topics = "greetings", groupId = "demo")
    public void listen(String message){
        System.out.println("Received Message " + message);
    }

}
