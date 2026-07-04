package project.demosdd.bad;

// Tight Coupling
// Direct access to another module's repository

import org.springframework.stereotype.Service;
import project.demosdd.good.BookRepository;

@Service
public class BadOrderService {
    BookRepository repository = new BookRepository();

    // Direct Access of Repository
    public void createOrder(int id){
        if (repository.findBook(id).getStock() > 0) System.out.println("Order Created");
    }

}
