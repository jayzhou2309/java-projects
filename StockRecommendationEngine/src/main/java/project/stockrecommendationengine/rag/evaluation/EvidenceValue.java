package project.stockrecommendationengine.rag.evaluation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * One value of the evaluation evidence report with its basis (RAG.md, Retrieval Evaluation, Evidence report): {@code observed} names
 * its {@code source} (the snapshot, its trace, the database, the bundled set, or the current configuration), {@code derived} names the
 * {@code rule} that computed it from observed values, and {@code unknown} carries a null value and the {@code reason} it is not
 * recorded or cannot be computed. A null value under {@code observed} or {@code derived} is itself the finding (for example "not in
 * the fused list"), described by its source or rule. There is no {@code inferred} basis: the report states nothing that is not
 * recorded or computed.
 */
@JsonPropertyOrder({"value", "basis", "source", "rule", "reason"})
public record EvidenceValue<T>(@JsonInclude(JsonInclude.Include.ALWAYS) T value, Basis basis,
        @JsonInclude(JsonInclude.Include.NON_NULL) String source, @JsonInclude(JsonInclude.Include.NON_NULL) String rule,
        @JsonInclude(JsonInclude.Include.NON_NULL) String reason) {

    public enum Basis {
        @JsonProperty("observed") OBSERVED,
        @JsonProperty("derived") DERIVED,
        @JsonProperty("unknown") UNKNOWN
    }

    public EvidenceValue {
        if (basis == null) throw new IllegalArgumentException("basis is required");
        boolean valid = switch (basis) {
            case OBSERVED -> source != null && rule == null && reason == null;
            case DERIVED -> rule != null && source == null && reason == null;
            case UNKNOWN -> value == null && reason != null && source == null && rule == null;
        };
        if (!valid) throw new IllegalArgumentException("an " + basis + " value needs exactly its " + switch (basis) {
            case OBSERVED -> "source";
            case DERIVED -> "rule";
            case UNKNOWN -> "reason and a null value";
        });
    }

    public static <T> EvidenceValue<T> observed(T value, String source) {
        return new EvidenceValue<>(value, Basis.OBSERVED, source, null, null);
    }

    public static <T> EvidenceValue<T> derived(T value, String rule) {
        return new EvidenceValue<>(value, Basis.DERIVED, null, rule, null);
    }

    public static <T> EvidenceValue<T> unknown(String reason) {
        return new EvidenceValue<>(null, Basis.UNKNOWN, null, null, reason);
    }
}
