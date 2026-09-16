package project.stockrecommendationengine.rag.retrieval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/** E5 as amended: the startup check of the second reranker model's declared inputs and output, on declared values only. */
class GteRerankerModelSignatureTests {
    private static final Path MODEL = Path.of("/models/gte/model.onnx");
    private static final Set<String> INPUTS = Set.of("input_ids", "attention_mask");

    @Test
    void theObservedGteSignaturePasses() {
        assertThatCode(() -> GteRerankerModelSignature.require(MODEL, INPUTS,
                List.of(new GteRerankerModelSignature.Output("logits", "FLOAT", new long[] {-1, 1})))).doesNotThrowAnyException();
        assertThatCode(() -> GteRerankerModelSignature.require(MODEL, INPUTS,
                List.of(new GteRerankerModelSignature.Output("scores", "FLOAT", new long[] {20, 1})))).doesNotThrowAnyException();
    }

    @Test
    void theEttinHiddenStateOutputFailsNamingTheModelAndTheDeclaredShape() {
        assertThatThrownBy(() -> GteRerankerModelSignature.require(MODEL, INPUTS,
                List.of(new GteRerankerModelSignature.Output("last_hidden_state", "FLOAT", new long[] {-1, -1, 384}))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(MODEL.toString())
                .hasMessageContaining("FLOAT of shape [batch, 1]")
                .hasMessageContaining("last_hidden_state FLOAT [-1, -1, 384]");
    }

    @Test
    void otherOutputShapesTypesOrCountsFail() {
        assertThatThrownBy(() -> GteRerankerModelSignature.require(MODEL, INPUTS,
                List.of(new GteRerankerModelSignature.Output("logits", "FLOAT", new long[] {-1, 2})))).hasMessageContaining("logits FLOAT [-1, 2]");
        assertThatThrownBy(() -> GteRerankerModelSignature.require(MODEL, INPUTS,
                List.of(new GteRerankerModelSignature.Output("logits", "FLOAT", new long[] {-1})))).hasMessageContaining("[-1]");
        assertThatThrownBy(() -> GteRerankerModelSignature.require(MODEL, INPUTS,
                List.of(new GteRerankerModelSignature.Output("logits", "DOUBLE", new long[] {-1, 1})))).hasMessageContaining("DOUBLE");
        assertThatThrownBy(() -> GteRerankerModelSignature.require(MODEL, INPUTS,
                List.of(new GteRerankerModelSignature.Output("logits", "NOT_A_TENSOR", new long[0])))).hasMessageContaining("NOT_A_TENSOR");
        assertThatThrownBy(() -> GteRerankerModelSignature.require(MODEL, INPUTS, List.of())).hasMessageContaining("exactly one output");
        assertThatThrownBy(() -> GteRerankerModelSignature.require(MODEL, INPUTS, List.of(
                new GteRerankerModelSignature.Output("logits", "FLOAT", new long[] {-1, 1}),
                new GteRerankerModelSignature.Output("hidden", "FLOAT", new long[] {-1, -1, 768})))).hasMessageContaining("exactly one output");
    }

    @Test
    void inputsOtherThanExactlyInputIdsAndAttentionMaskFail() {
        List<GteRerankerModelSignature.Output> ok = List.of(new GteRerankerModelSignature.Output("logits", "FLOAT", new long[] {-1, 1}));
        assertThatThrownBy(() -> GteRerankerModelSignature.require(MODEL, Set.of("input_ids", "attention_mask", "token_type_ids"), ok))
                .hasMessageContaining("[attention_mask, input_ids, token_type_ids]");
        assertThatThrownBy(() -> GteRerankerModelSignature.require(MODEL, Set.of("input_ids"), ok)).hasMessageContaining("[input_ids]");
    }
}
