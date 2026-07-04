package project.demosdd.good;

public class Book {
    private int id;
    private String title;
    private int stock;

    public Book(int id, String title, int stock) {
        this.id = id;
        this.title = title;
        this.stock = stock;
    }

    public int getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public int getStock() {
        return stock;
    }

    public void decreaseStock(){
        stock--;
    }
}
