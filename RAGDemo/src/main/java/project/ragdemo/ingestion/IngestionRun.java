package project.ragdemo.ingestion;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ingestion_runs")
public class IngestionRun {
    @Id
    private UUID id;
    @Column(nullable = false, length = 100)
    private String provider;
    @Column(nullable = false, length = 100)
    private String dataset;
    @Column(name = "stock_id")
    private Long stockId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IngestionStatus status;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "records_processed", nullable = false)
    private long recordsProcessed;
    @Column(name = "error_summary", length = 1000)
    private String errorSummary;

    protected IngestionRun() {}

    public IngestionRun(String provider, String dataset, Long stockId, Instant startedAt) {
        this.id = UUID.randomUUID();
        this.provider = Objects.requireNonNull(provider);
        this.dataset = Objects.requireNonNull(dataset);
        this.stockId = stockId;
        this.startedAt = Objects.requireNonNull(startedAt);
        this.status = IngestionStatus.RUNNING;
    }

    public void succeed(Instant completedAt, long recordsProcessed) {
        finish(IngestionStatus.SUCCEEDED, completedAt, recordsProcessed, null);
    }

    /** Summary must be sanitized by the caller; never persist credentials/provider payloads. */
    public void fail(Instant completedAt, long recordsProcessed, String errorSummary) {
        finish(IngestionStatus.FAILED, completedAt, recordsProcessed, Objects.requireNonNull(errorSummary));
    }

    private void finish(IngestionStatus next, Instant completedAt, long recordsProcessed, String errorSummary) {
        if (status != IngestionStatus.RUNNING) throw new IllegalStateException("Run is already complete");
        if (Objects.requireNonNull(completedAt).isBefore(startedAt)) {
            throw new IllegalArgumentException("Completion precedes start");
        }
        if (recordsProcessed < 0) throw new IllegalArgumentException("Negative record count");
        if (errorSummary != null && errorSummary.length() > 1000) {
            throw new IllegalArgumentException("Error summary exceeds 1000 characters");
        }
        this.status = next;
        this.completedAt = completedAt;
        this.recordsProcessed = recordsProcessed;
        this.errorSummary = errorSummary;
    }

    public UUID getId() { return id; }
    public String getProvider() { return provider; }
    public String getDataset() { return dataset; }
    public Long getStockId() { return stockId; }
    public IngestionStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public long getRecordsProcessed() { return recordsProcessed; }
    public String getErrorSummary() { return errorSummary; }
}
