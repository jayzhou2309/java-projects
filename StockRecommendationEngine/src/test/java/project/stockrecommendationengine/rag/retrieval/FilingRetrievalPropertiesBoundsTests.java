package project.stockrecommendationengine.rag.retrieval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 2026-09-17-chunk-size-pool, G1: the bounds of {@code rag.retrieval.candidate-count} and {@code keyword-candidate-count} are 20 to
 * 400 (200 before), so the plan's pools 40, 100, 200, and 250 bind and 401 stops the context; with no override both stay at 40. No
 * database, no model.
 */
class FilingRetrievalPropertiesBoundsTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withBean(FilingRetrievalProperties.class);

    @Test
    void withNoOverrideBothPoolsStayAtForty() {
        assertThat(new FilingRetrievalProperties().getCandidateCount()).isEqualTo(40);
        assertThat(new FilingRetrievalProperties().getKeywordCandidateCount()).isEqualTo(40);
        runner.run(context -> {
            assertThat(context.getBean(FilingRetrievalProperties.class).getCandidateCount()).isEqualTo(40);
            assertThat(context.getBean(FilingRetrievalProperties.class).getKeywordCandidateCount()).isEqualTo(40);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {20, 40, 100, 200, 250, 400})
    void poolsInsideTheBoundsBindForBothProperties(int pool) {
        runner.withPropertyValues("rag.retrieval.candidate-count=" + pool, "rag.retrieval.keyword-candidate-count=" + pool).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FilingRetrievalProperties.class).getCandidateCount()).isEqualTo(pool);
            assertThat(context.getBean(FilingRetrievalProperties.class).getKeywordCandidateCount()).isEqualTo(pool);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {19, 401, 1000})
    void aVectorPoolOutsideTheBoundsStopsTheContext(int pool) {
        runner.withPropertyValues("rag.retrieval.candidate-count=" + pool).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(ints = {19, 401, 1000})
    void aKeywordPoolOutsideTheBoundsStopsTheContext(int pool) {
        runner.withPropertyValues("rag.retrieval.keyword-candidate-count=" + pool).run(context -> assertThat(context).hasFailed());
    }
}
