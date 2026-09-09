package project.ragdemo.stock;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** An observed identifier interval, not a complete historical security resolver. */
@Entity
@Table(name = "stock_identifiers")
public class StockIdentifier {
    @Id
    private UUID id;
    @Column(name = "stock_id", nullable = false)
    private Long stockId;
    @Column(name = "identifier_type", nullable = false, length = 30)
    private String identifierType;
    @Column(name = "identifier_value", nullable = false, length = 100)
    private String identifierValue;
    @Column(nullable = false, length = 100)
    private String source;
    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;
    @Column(name = "valid_to")
    private Instant validTo;
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected StockIdentifier() {}

    public StockIdentifier(Long stockId, String identifierType, String identifierValue,
                           String source, Instant validFrom, Instant validTo, Instant recordedAt) {
        this.id = UUID.randomUUID();
        this.stockId = Objects.requireNonNull(stockId);
        this.identifierType = Objects.requireNonNull(identifierType);
        this.identifierValue = Objects.requireNonNull(identifierValue);
        this.source = Objects.requireNonNull(source);
        this.validFrom = Objects.requireNonNull(validFrom);
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("validTo must follow validFrom");
        }
        this.validTo = validTo;
        this.recordedAt = Objects.requireNonNull(recordedAt);
    }

    public UUID getId() { return id; }
    public Long getStockId() { return stockId; }
    public String getIdentifierType() { return identifierType; }
    public String getIdentifierValue() { return identifierValue; }
    public String getSource() { return source; }
    public Instant getValidFrom() { return validFrom; }
    public Instant getValidTo() { return validTo; }
    public Instant getRecordedAt() { return recordedAt; }
}
