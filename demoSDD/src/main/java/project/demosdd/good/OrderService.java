package project.demosdd.good;

import org.springframework.stereotype.Service;

// Low Coupling, this class depends on InventoryService, not the Repository

@Service
public class OrderService {
    private InventoryService inventoryService;

    public OrderService(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    public void createOrder(int id){
        if(inventoryService.hasStock(id)) System.out.println("Order Created");
        else System.out.println("Out of Stock");
    }
}
