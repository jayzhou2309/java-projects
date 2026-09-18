package project.stockrecommendationengine.rag.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationRepository.StoredResults;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opt-in, database- and model-free harness (plan 2026-09-13-evaluation-evidence, Amendment 5, Milestone 4a, F1): runs the committed
 * {@link TraceReproductionCheck} on two committed files, a reference snapshot exported with {@code row_to_json}
 * ({@code -Drag.reproduction.reference=<file>}) and a traced run exported the same way ({@code -Drag.reproduction.run=<file>}). The run
 * file is read into a {@link RetrievalEvaluation} exactly as {@code RetrievalEvaluationRepository}'s row mapper reads a stored row (the
 * {@code results} document as {@code StoredResults}, {@code properties} as a map), so the check sees what {@code findById} would return.
 * The check's semantics are not changed here. With {@code -Drag.reproduction.out=<file>} the problem count and every problem are written
 * there ({@code problems=0} when the run reproduces the reference) before the test fails once on any problem. With
 * {@code -Drag.reproduction.exempt=<name>,<name>} (since 2026-09-17, plan {@code 2026-09-17-chunk-size.md} Milestone 3) the named run
 * properties are exempt from the comparison as {@link TraceReproductionCheck#problems(JsonNode, RetrievalEvaluation, java.util.Set)}
 * defines it; the output names each with the value the run records, so a reader sees what was not compared.
 */
@EnabledIfSystemProperty(named = "rag.reproduction.run", matches = ".+")
class TraceReproductionFilesTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void theTracedRunFileReproducesTheReferenceFile() throws Exception {
        Path referenceFile = Path.of(System.getProperty("rag.reproduction.reference"));
        Path runFile = Path.of(System.getProperty("rag.reproduction.run"));
        JsonNode reference = JSON.readTree(Files.readString(referenceFile));
        RetrievalEvaluation run = read(JSON.readTree(Files.readString(runFile)));

        String exemptProperty = System.getProperty("rag.reproduction.exempt", "");
        java.util.Set<String> exempt = new java.util.LinkedHashSet<>();
        for (String name : exemptProperty.split(",")) {
            if (!name.isBlank()) exempt.add(name.strip());
        }
        List<String> problems = TraceReproductionCheck.problems(reference, run, exempt);
        StringBuilder out = new StringBuilder();
        out.append("reference=").append(referenceFile).append(" (snapshot ").append(reference.get("id").asString()).append(')').append(System.lineSeparator());
        out.append("run=").append(runFile).append(" (snapshot ").append(run.id()).append(')').append(System.lineSeparator());
        out.append("questions reference=").append(reference.get("results").get("questions").size()).append(" run=").append(run.results().size())
                .append(" traces=").append(run.traces() == null ? "null" : String.valueOf(run.traces().size())).append(System.lineSeparator());
        for (String name : exempt) {
            out.append("exempt property ").append(name).append(": run records ").append(run.properties().get(name))
                    .append(reference.get("properties").has(name) ? " (the reference records " + reference.get("properties").get(name) + ", compared)" : " (absent from the reference, not compared)")
                    .append(System.lineSeparator());
        }
        out.append("problems=").append(problems.size()).append(System.lineSeparator());
        problems.forEach(problem -> out.append("  - ").append(problem).append(System.lineSeparator()));
        System.out.print("TRACE_REPRODUCTION_FILES " + out);
        String outFile = System.getProperty("rag.reproduction.out");
        if (outFile != null && !outFile.isBlank()) Files.writeString(Path.of(outFile), out.toString());
        if (!problems.isEmpty()) throw new AssertionError(TraceReproductionCheck.report(reference, problems));
    }

    /** A row_to_json export read as RetrievalEvaluationRepository's row mapper reads the row. */
    private static RetrievalEvaluation read(JsonNode row) {
        StoredResults stored = JSON.readValue(row.get("results").toString(), StoredResults.class);
        Map<String, Object> properties = JSON.readValue(row.get("properties").toString(), new TypeReference<Map<String, Object>>() { });
        return new RetrievalEvaluation(row.get("id").asLong(), java.time.OffsetDateTime.parse(row.get("evaluated_at").asString()).toInstant(),
                row.get("set_version").asString(), row.get("question_count").asInt(), row.get("hit_at_1").decimalValue(),
                row.get("hit_at_3").decimalValue(), row.get("hit_at_5").decimalValue(), row.get("mrr").decimalValue(), row.get("window_size").asInt(),
                row.get("retrieval_strategy").asString(), properties,
                stored.questions() == null ? List.of() : stored.questions(), stored.tickerHitAt5() == null ? Map.of() : stored.tickerHitAt5(),
                stored.misses() == null ? List.of() : stored.misses(), stored.slices(), stored.traces());
    }
}
