package project.stockrecommendationengine.rag.retrieval;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the second reranker model's declared ONNX inputs and outputs from a short-lived ONNX Runtime session (no graph
 * optimisation) and applies {@link GteRerankerModelSignature#require} before {@link OnnxCrossEncoderScorer} loads the model for
 * scoring. The extra session is closed before the scorer's opens: on the development Mac, 2026-09-15, creating and closing a
 * session on the 598,803,940-byte model took 211 to 566 ms, and three sequential sessions peaked at a 973 MB process footprint
 * (scratch probe, recorded in RAG.md, Second reranker model). Used only by {@link GteRerankerConfiguration}.
 */
final class GteRerankerModelInspector {
    private GteRerankerModelInspector() {
    }

    static void requireSignature(Path modelPath) {
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
            try (OrtSession session = environment.createSession(modelPath.toString(), options)) {
                List<GteRerankerModelSignature.Output> outputs = new ArrayList<>();
                for (NodeInfo node : session.getOutputInfo().values()) {
                    if (node.getInfo() instanceof TensorInfo tensor) {
                        outputs.add(new GteRerankerModelSignature.Output(node.getName(), tensor.type.name(), tensor.getShape()));
                    } else {
                        outputs.add(new GteRerankerModelSignature.Output(node.getName(), "NOT_A_TENSOR", new long[0]));
                    }
                }
                GteRerankerModelSignature.require(modelPath, session.getInputNames(), outputs);
            }
        } catch (OrtException e) {
            throw new IllegalStateException("Cannot read the gte reranker model's signature from " + modelPath + ": " + e.getClass().getSimpleName(), e);
        }
    }
}
