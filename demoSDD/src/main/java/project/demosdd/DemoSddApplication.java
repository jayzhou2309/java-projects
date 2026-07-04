package project.demosdd;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import project.demosdd.good.BookRepository;
import project.demosdd.good.InventoryService;
import project.demosdd.good.OrderService;

@SpringBootApplication
public class DemoSddApplication {

    public static void main(String[] args) {
        ApplicationContext context = SpringApplication.run(DemoSddApplication.class, args);

        BookRepository repository = new BookRepository();
        InventoryService inventoryService = new InventoryService(repository);
        OrderService orderService = new OrderService(inventoryService);

        orderService.createOrder(1);
    }
}
