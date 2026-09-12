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
import project.stockrecommendationengine.outcome.*;
import project.stockrecommendationengine.quant.*;
import project.stockrecommendationengine.rag.controller.RetrievalEvaluationController;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationRepository;
import project.stockrecommendationengine.rag.evaluation.RetrievalEvaluationService;
import project.stockrecommendationengine.rag.retrieval.FilingRetrievalService;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntegrationWiringTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(IntegrationAccessConfiguration.class, RecommendationService.class,
                    IbkrConfiguration.class, IbkrBrokerAdapter.class, QuantAnalysisService.class, QuantController.class,
                    OutcomeEvaluationService.class, OutcomeScheduler.class, OutcomeController.class, TrackRecordService.class,
                    ConfidenceCalibrationService.class, RecommendationController.class, WatchlistScheduler.class,
                    WatchlistController.class, BrokerController.class, RetrievalEvaluationController.class)
            .withBean(IntegrationAccessProperties.class).withBean(IbkrProperties.class).withBean(RecommendationProperties.class)
            .withBean(QuantProperties.class).withBean(OutcomeProperties.class)
            .withBean(OutcomeRepository.class, () -> mock(OutcomeRepository.class))
            .withBean(CalibrationRepository.class, () -> mock(CalibrationRepository.class))
            .withBean(FilingRetrievalService.class, () -> mock(FilingRetrievalService.class))
            .withBean(RetrievalEvaluationService.class, () -> mock(RetrievalEvaluationService.class))
            .withBean(RetrievalEvaluationRepository.class, () -> mock(RetrievalEvaluationRepository.class))
            .withBean(project.stockrecommendationengine.rag.freshness.FilingFreshnessService.class,
                    () -> mock(project.stockrecommendationengine.rag.freshness.FilingFreshnessService.class))
            .withBean(PriceBarRepository.class, () -> mock(PriceBarRepository.class))
            .withBean(RecommendationRepository.class, () -> mock(RecommendationRepository.class));

    @Test void defaultConfigurationDoesNotCreateBrokerOrRecommendationConnections() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(BrokerReadService.class)
                    .doesNotHaveBean(RecommendationService.class).doesNotHaveBean(TwsClient.class)
                    .doesNotHaveBean(QuantAnalysisService.class).doesNotHaveBean(QuantController.class)
                    .doesNotHaveBean(OutcomeEvaluationService.class).doesNotHaveBean(OutcomeScheduler.class)
                    .doesNotHaveBean(TrackRecordService.class).doesNotHaveBean(ConfidenceCalibrationService.class)
                    .doesNotHaveBean(WatchlistScheduler.class).doesNotHaveBean(WatchlistController.class);
        });
    }

    @Test void retrievalEvaluationEndpointsWireByDefaultAndAreListedAsTokenGated() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RetrievalEvaluationController.class).hasSingleBean(IntegrationAccessConfiguration.class);
            verifyNoInteractions(context.getBean(RetrievalEvaluationService.class), context.getBean(RetrievalEvaluationRepository.class));
        });
    }

    @Test void watchlistScheduleWiresOnlyWhenEnabledWithTickers() {
        var enabled = runner.withPropertyValues("recommendation.enabled=true", "recommendation.model=test-model",
                        "integration.access.token=test-token-with-at-least-32-characters")
                .withBean(ChatModel.class, () -> mock(ChatModel.class));
        enabled.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(WatchlistScheduler.class).doesNotHaveBean(WatchlistController.class));
        enabled.withPropertyValues("recommendation.schedule.enabled=true", "recommendation.schedule.tickers=AAPL,MSFT")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(WatchlistScheduler.class).hasSingleBean(WatchlistController.class);
                    assertThat(context.getBean(RecommendationProperties.class).getSchedule().getTickers()).containsExactly("AAPL", "MSFT");
                    verifyNoInteractions(context.getBean(ChatModel.class));
                });
        enabled.withPropertyValues("recommendation.schedule.enabled=true").run(context -> assertThat(context).hasFailed());
        enabled.withPropertyValues("recommendation.schedule.enabled=true", "recommendation.schedule.tickers=AAPL", "recommendation.schedule.cron=not a cron")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void enabledFeaturesWireWithoutContactingExternalServicesAtStartup() {
        runner.withPropertyValues("broker.ibkr.enabled=true", "broker.ibkr.account-id=DU_TEST", "quant.enabled=true",
                        "recommendation.enabled=true", "recommendation.model=test-model",
                        "integration.access.token=test-token-with-at-least-32-characters")
                .withBean(ChatModel.class, () -> mock(ChatModel.class))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(BrokerReadService.class)
                            .hasSingleBean(RecommendationService.class).hasSingleBean(BrokerController.class)
                            .hasSingleBean(QuantAnalysisService.class).hasSingleBean(QuantController.class);
                    verifyNoInteractions(context.getBean(ChatModel.class), context.getBean(PriceBarRepository.class));
                });
    }

    @Test void outcomesWireWithOrWithoutTheBrokerButRequireTheToken() {
        runner.withPropertyValues("outcomes.enabled=true", "integration.access.token=test-token-with-at-least-32-characters")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(OutcomeEvaluationService.class)
                        .hasSingleBean(OutcomeScheduler.class).hasSingleBean(OutcomeController.class).hasSingleBean(TrackRecordService.class)
                        .hasSingleBean(ConfidenceCalibrationService.class).doesNotHaveBean(BrokerReadService.class));
        runner.withPropertyValues("outcomes.enabled=true").run(context -> assertThat(context).hasFailed());
    }

    @Test void enabledQuantFailsClearlyWithoutTheBroker() {
        runner.withPropertyValues("quant.enabled=true").run(context -> assertThat(context).hasFailed());
    }

    @Test void quantPropertyValidationRejectsUncomputableIndicatorWindows() {
        runner.withPropertyValues("broker.ibkr.enabled=true", "broker.ibkr.account-id=DU_TEST", "quant.enabled=true",
                        "quant.min-bars=50", "integration.access.token=test-token-with-at-least-32-characters")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void enabledRecommendationFailsClearlyWithoutAChatModel() {
        runner.withPropertyValues("recommendation.enabled=true", "recommendation.model=test-model",
                        "integration.access.token=test-token-with-at-least-32-characters")
                .run(context -> assertThat(context).hasFailed());
    }
}
