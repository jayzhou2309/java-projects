package project.demopaymentapp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("project.demoPaymentApp.mappers")
public class DemoPaymentAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoPaymentAppApplication.class, args);
    }

}
