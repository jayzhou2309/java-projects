package project.stockrecommendationengine.recommendation;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import project.stockrecommendationengine.access.*;
import project.stockrecommendationengine.broker.BrokerReadService;
import project.stockrecommendationengine.broker.api.BrokerController;
import project.stockrecommendationengine.broker.ibkr.*;
import project.stockrecommendationengine.rag.retrieval.FilingRetrievalService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntegrationWiringTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(IntegrationAccessConfiguration.class, RecommendationService.class,
                    IbkrConfiguration.class, IbkrBrokerAdapter.class, IbkrSessionManager.class,
                    RecommendationController.class, BrokerController.class)
            .withBean(IntegrationAccessProperties.class).withBean(IbkrProperties.class).withBean(RecommendationProperties.class)
            .withBean(FilingRetrievalService.class, () -> mock(FilingRetrievalService.class));

    @Test void defaultConfigurationDoesNotCreateBrokerOrRecommendationConnections() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(BrokerReadService.class)
                    .doesNotHaveBean(RecommendationService.class).doesNotHaveBean(IbkrApiClient.class);
        });
    }

    @Test void enabledFeaturesWireWithoutContactingExternalServicesAtStartup() {
        runner.withPropertyValues("broker.ibkr.enabled=true", "broker.ibkr.account-id=DU_TEST",
                        "recommendation.enabled=true", "recommendation.model=test-model",
                        "integration.access.token=test-token-with-at-least-32-characters")
                .withBean(ChatModel.class, () -> mock(ChatModel.class))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(BrokerReadService.class)
                            .hasSingleBean(RecommendationService.class).hasSingleBean(BrokerController.class);
                    verifyNoInteractions(context.getBean(ChatModel.class));
                });
    }

    @Test void enabledRecommendationFailsClearlyWithoutAChatModel() {
        runner.withPropertyValues("recommendation.enabled=true", "recommendation.model=test-model",
                        "integration.access.token=test-token-with-at-least-32-characters")
                .run(context -> assertThat(context).hasFailed());
    }
}
