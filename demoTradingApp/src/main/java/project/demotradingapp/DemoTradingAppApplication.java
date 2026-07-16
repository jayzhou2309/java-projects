package project.demotradingapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DemoTradingAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoTradingAppApplication.class, args);
    }

}
