package project.stockrecommendationengine.rag.evaluation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.QuestionResult;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluation.QuestionTrace;
import project.stockrecommendationengine.rag.retrieval.RetrievalTrace;
import tools.jackson.databind.JsonNode;

/**
 * The reproduction comparison of {@code RetrievalEvaluationTraceLiveTests} (plan 2026-09-13-evaluation-evidence, Milestone 1, C5),
 * database- and model-free so {@code TraceReproductionCheckTests} constructs its failure scenarios in {@code verify}: a run against a
 * stored reference snapshot's JSON (the committed snapshot 297 file). It collects every problem first and fails once, so a single
 * failure names every item that makes the check fail (the RAG-13 lesson), in this order:
 * <ol>
 * <li>every question whose id, rank, or matched chunk differs from the reference (a question present on one side only included);</li>
 * <li>every question that fell back: reranking resolved on for the run, the question retrieved without error, and its strategy not
 * {@code _RERANKED}, with its strategy and its trace's rerank outcome and fallback reason;</li>
 * <li>every question whose retrieval errored, with the error;</li>
 * <li>every run property that differs from the reference: each property of the reference must be present with an equal value, and the
 * run may carry no property the reference lacks except {@value #EXEMPT_PROPERTY}, which must be {@code true} (the reference predates
 * traces, so it has none), and except the properties the caller names as exempt ({@link #problems(JsonNode, RetrievalEvaluation, Set)}),
 * each of which the run must carry (since 2026-09-17, plan {@code 2026-09-17-chunk-size.md} Milestone 3: snapshots stored before that
 * date record no {@code chunkMaxChars}, {@code chunkOverlapChars}, or {@code storeVersions}, so a run reproducing one carries three
 * properties the reference lacks; naming them keeps every other property compared).</li>
 * </ol>
 * A matched chunk is compared by id unless the caller gives a {@link ChunkIdentity} for each side (since 2026-09-18, plan
 * {@code 2026-09-17-chunk-size.md} Milestone 4, H4: every rebuild renumbers chunks, so a reference is compared by what its matched chunk
 * holds, not by its number): then the two matched chunks are equal when their identities are equal text, and a problem prints both ids
 * with their identities.
 */
final class TraceReproductionCheck {
    /** The only run property not compared with the reference: the reference snapshot predates traces. */
    static final String EXEMPT_PROPERTY = "trace";

    /**
     * A chunk's content identity for one side of the comparison: for a chunk id, one line naming what the chunk holds (for example
     * the filing's accession number, section key, chunk index, length, and content md5), or null when that side does not know the id.
     */
    @FunctionalInterface
    interface ChunkIdentity {
        String of(long chunkId);
    }

    private TraceReproductionCheck() {
    }

    /** Every problem, in the order of the class description; empty when the run reproduces the reference. */
    static List<String> problems(JsonNode reference, RetrievalEvaluation run) {
        return problems(reference, run, Set.of());
    }

    /**
     * As {@link #problems(JsonNode, RetrievalEvaluation)}, with {@code exempt} the run properties the reference may lack: each must be
     * present in the run (absent is a problem) and is otherwise not compared; a property both record is compared whether or not it is
     * named here.
     */
    static List<String> problems(JsonNode reference, RetrievalEvaluation run, Set<String> exempt) {
        return problems(reference, run, exempt, null, null);
    }

    /**
     * As {@link #problems(JsonNode, RetrievalEvaluation, Set)}, comparing matched chunks by content: {@code referenceChunks} resolves the
     * reference's matched chunk ids and {@code runChunks} the run's (both given, or both null for the id comparison). Two matched chunks
     * are equal when both are null or their identities are equal text; an id a side cannot resolve is reported as unknown to that side.
     */
    static List<String> problems(JsonNode reference, RetrievalEvaluation run, Set<String> exempt, ChunkIdentity referenceChunks, ChunkIdentity runChunks) {
        if ((referenceChunks == null) != (runChunks == null)) throw new IllegalArgumentException("give a ChunkIdentity for both sides or for neither");
        List<String> problems = new ArrayList<>();
        String label = "snapshot " + reference.get("id").asString();

        JsonNode referenceQuestions = reference.get("results").get("questions");
        List<QuestionResult> results = run.results();
        for (int i = 0; i < Math.max(referenceQuestions.size(), results.size()); i++) {
            JsonNode expected = i < referenceQuestions.size() ? referenceQuestions.get(i) : null;
            QuestionResult actual = i < results.size() ? results.get(i) : null;
            if (actual == null) {
                problems.add("question " + expected.get("id").asString() + " (position " + (i + 1) + "): in " + label + " (rank "
                        + text(expected.get("rank")) + " chunk " + text(expected.get("matchedChunkId")) + ") but absent from the run");
                continue;
            }
            if (expected == null) {
                problems.add("question " + actual.id() + " (position " + (i + 1) + "): in the run (rank " + actual.rank() + " chunk "
                        + actual.matchedChunkId() + ") but absent from " + label);
                continue;
            }
            String expectedId = expected.get("id").asString();
            String expectedRank = text(expected.get("rank"));
            String expectedChunk = text(expected.get("matchedChunkId"));
            String expectedIdentity = identity(referenceChunks, expected.get("matchedChunkId").isNull() ? null : expected.get("matchedChunkId").asLong(), label);
            String actualIdentity = identity(runChunks, actual.matchedChunkId(), "the run");
            boolean sameChunk = referenceChunks == null ? Objects.equals(expectedChunk, string(actual.matchedChunkId()))
                    : Objects.equals(expectedIdentity, actualIdentity) && !expectedIdentity.startsWith(UNKNOWN) && !actualIdentity.startsWith(UNKNOWN);
            if (!expectedId.equals(actual.id()) || !Objects.equals(expectedRank, string(actual.rank())) || !sameChunk) {
                problems.add("question " + expectedId + ": " + label + " rank " + expectedRank + " chunk " + expectedChunk
                        + (referenceChunks == null ? "" : " (" + expectedIdentity + ")") + ", run "
                        + (expectedId.equals(actual.id()) ? "" : "question " + actual.id() + " ") + "rank " + actual.rank() + " chunk "
                        + actual.matchedChunkId() + (runChunks == null ? "" : " (" + actualIdentity + ")"));
            }
        }

        if (rerankResolvedOn(run.properties())) {
            for (QuestionResult result : results) {
                if (result.error() != null || RetrievalEvaluationService.reranked(result)) continue;
                problems.add("question " + result.id() + " fell back: strategy " + result.retrievalStrategy() + " (not _RERANKED), "
                        + rerankOutcome(run.traces(), result.id()));
            }
        }

        for (QuestionResult result : results) {
            if (result.error() != null) problems.add("question " + result.id() + " errored: " + result.error());
        }

        Map<String, Object> properties = run.properties();
        JsonNode referenceProperties = reference.get("properties");
        for (Iterator<Map.Entry<String, JsonNode>> fields = referenceProperties.properties().iterator(); fields.hasNext(); ) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            String expected = text(field.getValue());
            if (!properties.containsKey(key)) {
                problems.add("property " + key + ": " + label + " " + expected + ", absent from the run");
            } else if (!Objects.equals(expected, string(properties.get(key)))) {
                problems.add("property " + key + ": " + label + " " + expected + ", run " + properties.get(key));
            }
        }
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            String key = property.getKey();
            if (referenceProperties.has(key) || exempt.contains(key)) continue;
            if (EXEMPT_PROPERTY.equals(key)) {
                if (!Boolean.TRUE.equals(property.getValue())) problems.add("property trace: expected true, run " + property.getValue());
            } else {
                problems.add("property " + key + ": absent from " + label + ", run " + property.getValue());
            }
        }
        if (!properties.containsKey(EXEMPT_PROPERTY)) problems.add("property trace: expected true, absent from the run");
        for (String key : exempt) {
            if (!properties.containsKey(key)) problems.add("property " + key + ": exempt from the comparison with " + label + " but absent from the run");
        }
        return problems;
    }

    /** Fails once with every problem when there is any; the message lists them one per line. */
    static void assertReproduces(JsonNode reference, RetrievalEvaluation run) {
        List<String> problems = problems(reference, run);
        if (!problems.isEmpty()) throw new AssertionError(report(reference, problems));
    }

    /** As {@link #assertReproduces(JsonNode, RetrievalEvaluation)} with matched chunks compared by content. */
    static void assertReproduces(JsonNode reference, RetrievalEvaluation run, Set<String> exempt, ChunkIdentity referenceChunks, ChunkIdentity runChunks) {
        List<String> problems = problems(reference, run, exempt, referenceChunks, runChunks);
        if (!problems.isEmpty()) throw new AssertionError(report(reference, problems));
    }

    static String report(JsonNode reference, List<String> problems) {
        StringBuilder out = new StringBuilder("The traced run does not reproduce snapshot " + reference.get("id").asString() + ": "
                + problems.size() + " problem" + (problems.size() == 1 ? "" : "s"));
        problems.forEach(problem -> out.append(System.lineSeparator()).append("  - ").append(problem));
        return out.toString();
    }

    /** The identity text of a chunk a side cannot resolve starts with this, so two unknowns are never equal. */
    static final String UNKNOWN = "unknown to ";

    /** A matched chunk's identity on one side: "no matched chunk" for null, the side's identity text, or unknown to that side. */
    private static String identity(ChunkIdentity chunks, Long chunkId, String side) {
        if (chunks == null) return null;
        if (chunkId == null) return "no matched chunk";
        String identity = chunks.of(chunkId);
        return identity == null ? UNKNOWN + side : identity;
    }

    /** The service's rule: the run's {@code rerank} override when present, else {@code rerankingEnabled}. */
    private static boolean rerankResolvedOn(Map<String, Object> properties) {
        Object rerank = properties.get("rerank");
        return rerank != null ? Boolean.TRUE.equals(rerank) : Boolean.TRUE.equals(properties.get("rerankingEnabled"));
    }

    private static String rerankOutcome(List<QuestionTrace> traces, String id) {
        if (traces == null) return "no traces recorded";
        // Filter by id, then map: a question that fell back without an error can carry a null trace, which Stream.map then findFirst rejects.
        RetrievalTrace trace = traces.stream().filter(t -> t.id().equals(id)).findFirst().map(QuestionTrace::trace).orElse(null);
        if (trace == null) return "no trace recorded for the question";
        return "trace outcome " + trace.rerank().outcome() + " reason " + trace.rerank().fallbackReason();
    }

    /**
     * A reference value as text. A list (since 2026-09-17, plan 2026-09-17-chunk-size-pool.md: a reference stored after the chunk-size
     * properties records {@code storeVersions}, a list of strings) is written as {@code String.valueOf} writes the run's list,
     * {@code [a, b]}, so both sides compare as text; before, a list in the reference threw.
     */
    private static String text(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isArray()) {
            List<String> items = new ArrayList<>();
            node.forEach(item -> items.add(text(item)));
            return String.valueOf(items);
        }
        return node.asString();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
