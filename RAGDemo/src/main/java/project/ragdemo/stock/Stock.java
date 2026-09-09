package project.ragdemo.stock;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;

/** Current security master; historical identifiers are stored separately. */
@Entity
@Table(name = "stocks")
public class Stock {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 10, unique = true)
    private String symbol;
    @Column(name = "company_name", nullable = false, length = 255)
    private String companyName;
    @Column(length = 20)
    private String exchange;
    @Column(length = 100)
    private String sector;
    @Column(length = 100)
    private String industry;
    @Column(nullable = false, length = 10, unique = true)
    private String cik;
    @Column(name = "is_active", nullable = false)
    private boolean active;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Stock() {}

    public Stock(String symbol, String companyName, String cik, Instant recordedAt) {
        this.symbol = Objects.requireNonNull(symbol);
        this.companyName = Objects.requireNonNull(companyName);
        this.cik = Objects.requireNonNull(cik);
        this.createdAt = Objects.requireNonNull(recordedAt);
        this.updatedAt = recordedAt;
        this.active = true;
    }

    public Long getId() { return id; }
    public String getSymbol() { return symbol; }
    public String getCompanyName() { return companyName; }
    public String getCik() { return cik; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
