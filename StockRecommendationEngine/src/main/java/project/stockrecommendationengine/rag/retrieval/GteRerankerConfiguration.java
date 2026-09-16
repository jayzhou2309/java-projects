package project.stockrecommendationengine.rag.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Creates the second reranker model's beans only when {@code rag.retrieval.gte-reranker.enabled} is true (profile
 * {@code reranker-gte}): files verified first ({@link GteRerankerModelFiles}), then the ONNX signature checked
 * ({@link GteRerankerModelInspector}), then the model scored by the unchanged {@link OnnxCrossEncoderScorer} with this model's paths
 * and window settings, so tokenization, input bounds, pair assembly, windows, per-call attention cap, and close behaviour are the
 * current model's code, not a copy. That scorer's assembler reads this model's pair template as it is:
 * {@link CrossEncoderPairAssembler#fromTokenizerJson} requires the first two specials typed like sequence A and the last like B, and
 * this template types all five pieces 0, which satisfies that (checked 2026-09-15 on the downloaded file: ids 50281, 50282, 50282,
 * pad 50283; the plan's statement that the reader rejects it was wrong). On top: a {@link GteReranker} (its own class name in
 * snapshots) and a {@link PassageTokenizer} reporting this model's {@code max-length} for the evidence report.
 * <p>
 * The bean types are the project interfaces {@link CrossEncoderConfiguration} returns, so the application never holds two
 * {@link FilingReranker}, {@link PairScorer}, or {@link PassageTokenizer} beans: {@link #rerankerExclusionGuard} fails startup,
 * before any bean is created, when {@code rag.retrieval.cross-encoder.enabled} is also true. With this property off nothing here is
 * registered and no ONNX Runtime or tokenizer class is loaded.
 */
@Configuration
@ConditionalOnProperty(prefix = GteRerankerProperties.PREFIX, name = "enabled", havingValue = "true")
@Slf4j
public class GteRerankerConfiguration {
    static final String CROSS_ENCODER_ENABLED = "rag.retrieval.cross-encoder.enabled";
    static final String GTE_ENABLED = GteRerankerProperties.PREFIX + ".enabled";

    /** Registered first (a bean factory post-processor runs before any bean is instantiated), so both models never load together. */
    @Bean
    static BeanFactoryPostProcessor rerankerExclusionGuard() {
        return new ExclusionGuard();
    }

    @Bean
    GteRerankerModelFiles gteRerankerModelFiles(GteRerankerProperties properties) {
        GteRerankerModelFiles files = GteRerankerModelFiles.verify(properties);
        log.info("GTE reranker files verified: model={}, tokenizer={}, version={}", files.modelPath(), files.tokenizerPath(), files.version());
        return files;
    }

    @Bean(destroyMethod = "close")
    PairScorer gteRerankerScorer(GteRerankerModelFiles files, GteRerankerProperties properties) {
        GteRerankerModelInspector.requireSignature(files.modelPath());
        return new OnnxCrossEncoderScorer(files.modelPath(), files.tokenizerPath(), properties.getMaxLength(), properties.getBatchSize(),
                properties.getPassageScoring(), properties.getWindowOverlapTokens(), properties.getMaxWindows());
    }

    @Bean
    FilingReranker gteReranker(PairScorer gteRerankerScorer, GteRerankerModelFiles files) {
        return new GteReranker(gteRerankerScorer, files.version());
    }

    /** Takes the scorer so it is created after the scorer's native-library checks and DJL runtime defaults. */
    @Bean(destroyMethod = "close")
    PassageTokenizer gteRerankerPassageTokenizer(PairScorer gteRerankerScorer, GteRerankerModelFiles files, GteRerankerProperties properties)
            throws java.io.IOException {
        return new GteRerankerPassageTokenizer(files.tokenizerPath(), files.version(), properties.getMaxLength());
    }

    /** Fails when the current model is enabled too, naming both properties. */
    static final class ExclusionGuard implements BeanFactoryPostProcessor, EnvironmentAware {
        private Environment environment;

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            check(Binder.get(environment).bind(CROSS_ENCODER_ENABLED, Boolean.class).orElse(false),
                    Binder.get(environment).bind(GTE_ENABLED, Boolean.class).orElse(false));
        }

        static void check(boolean crossEncoderEnabled, boolean gteEnabled) {
            if (crossEncoderEnabled && gteEnabled) {
                throw new IllegalStateException("Only one reranker model may be enabled: " + CROSS_ENCODER_ENABLED + " and " + GTE_ENABLED
                        + " are both true; set one of them to false");
            }
        }
    }
}
