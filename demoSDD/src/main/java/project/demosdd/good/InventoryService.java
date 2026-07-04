package project.demosdd.good;

import org.springframework.stereotype.Service;

// High Cohesion
// Handles INVENTORY Logic only

@Service
public class InventoryService {
    private BookRepository bookRepository;

    public InventoryService(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }

    public boolean hasStock(int id){
        Book book = bookRepository.findBook(id);
        return book != null && book.getStock() > 0;
    }
}
