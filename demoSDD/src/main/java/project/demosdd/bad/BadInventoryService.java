package project.demosdd.bad;

import org.springframework.stereotype.Service;

// Low Cohesion
// Performs many UNRELATED tasks

@Service
public class BadInventoryService {
    public void checkInventory(){
        System.out.println("Check Inventory");
    }
    public void printInvoice(){
        System.out.println("Print Invoice");
    }
    public void checkEmail(){
        System.out.println("Check Email");
    }
    public void calculateSalary(){
        System.out.println("Salary");
    }
}
