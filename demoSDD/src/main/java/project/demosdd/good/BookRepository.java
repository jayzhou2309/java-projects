package project.demosdd.good;

import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

@Repository
public class BookRepository {
    private Map<Integer, Book> books = new HashMap<>();

    public BookRepository() {
        books.put(1, new Book(1, "Java Guide", 10));
        books.put(2, new Book(2, "SpringBoot Guide", 15));
    }

    public Book findBook(int id){
        return books.get(id);
    }

}
