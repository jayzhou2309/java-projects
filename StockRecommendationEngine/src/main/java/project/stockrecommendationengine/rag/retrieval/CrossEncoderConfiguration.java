package project.stockrecommendationengine.rag.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the cross-encoder reranker only when {@code rag.retrieval.cross-encoder.enabled} is true: the files are verified
 * (missing file or checksum mismatch fails startup naming the path), then the ONNX Runtime session and tokenizer load once and
 * are closed on shutdown. The bean methods return project interfaces, so with the property off no ONNX Runtime or tokenizer
 * class is loaded and the application starts exactly as without this class.
 */
@Configuration
@ConditionalOnProperty(prefix = "rag.retrieval.cross-encoder", name = "enabled", havingValue = "true")
@Slf4j
public class CrossEncoderConfiguration {
    @Bean
    CrossEncoderModelFiles crossEncoderModelFiles(CrossEncoderProperties properties) {
        CrossEncoderModelFiles files = CrossEncoderModelFiles.verify(properties);
        log.info("Cross-encoder files verified: model={}, tokenizer={}, version={}", files.modelPath(), files.tokenizerPath(), files.version());
        return files;
    }

    @Bean(destroyMethod = "close")
    PairScorer crossEncoderScorer(CrossEncoderModelFiles files, CrossEncoderProperties properties) {
        return new OnnxCrossEncoderScorer(files.modelPath(), files.tokenizerPath(), properties.getMaxLength(), properties.getBatchSize(),
                properties.getPassageScoring(), properties.getWindowOverlapTokens(), properties.getMaxWindows());
    }

    @Bean
    FilingReranker crossEncoderReranker(PairScorer crossEncoderScorer, CrossEncoderModelFiles files) {
        return new CrossEncoderReranker(crossEncoderScorer, files.version());
    }
}
