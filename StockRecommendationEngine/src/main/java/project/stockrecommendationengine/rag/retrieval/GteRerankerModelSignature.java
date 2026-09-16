package project.stockrecommendationengine.rag.retrieval;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * The ONNX signature the second reranker model must declare, checked at startup by {@link GteRerankerModelInspector} before the
 * scorer loads the model (plan {@code 2026-09-15-reranker-ettin.md}, E5 as amended): exactly the inputs {@code input_ids} and
 * {@code attention_mask}, and exactly one output, FLOAT, of rank 2 whose second dimension is 1 (one score per pair; the first
 * dimension any size or dynamic). Anything else fails with an {@link IllegalStateException} naming the model file and what it
 * declares. Observed for {@code gte-reranker-modernbert-base} on 2026-09-15 with ONNX Runtime 1.29.0: inputs {@code input_ids} and
 * {@code attention_mask}, INT64 [batch_size, sequence_length]; output {@code logits}, FLOAT [batch_size, 1]. The earlier
 * {@code cross-encoder/ettin-reranker-32m-v1} export declares {@code last_hidden_state} [batch, sequence, 384] and fails here (plan
 * amendment 1). Stricter than {@link OnnxCrossEncoderScorer}'s own load check, which accepts any outputs and an optional
 * {@code token_type_ids}. Imports nothing from ONNX Runtime, so it is unit-tested without a model.
 */
final class GteRerankerModelSignature {
    static final String INPUT_IDS = "input_ids";
    static final String ATTENTION_MASK = "attention_mask";

    private GteRerankerModelSignature() {
    }

    /** One declared output: its name, element type as ONNX Runtime's Java type names it ({@code FLOAT}), and shape (-1 for dynamic). */
    record Output(String name, String type, long[] shape) {
        @Override
        public String toString() {
            return name + " " + type + " " + Arrays.toString(shape);
        }
    }

    static void require(Path modelPath, Set<String> inputNames, List<Output> outputs) {
        if (!inputNames.equals(Set.of(INPUT_IDS, ATTENTION_MASK))) {
            throw new IllegalStateException("GTE reranker model " + modelPath + " must declare exactly the inputs input_ids and attention_mask, but declares "
                    + inputNames.stream().sorted().toList());
        }
        if (outputs.size() != 1) {
            throw new IllegalStateException("GTE reranker model " + modelPath + " must declare exactly one output of shape [batch, 1], but declares " + outputs);
        }
        Output output = outputs.get(0);
        if (!"FLOAT".equals(output.type()) || output.shape().length != 2 || output.shape()[1] != 1) {
            throw new IllegalStateException("GTE reranker model " + modelPath + " output must be FLOAT of shape [batch, 1] (one score per pair), but is "
                    + output);
        }
    }
}
