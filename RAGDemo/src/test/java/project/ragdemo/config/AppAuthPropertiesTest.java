package project.ragdemo.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AppAuthPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources().remove("systemEnvironment"))
            .withUserConfiguration(Binding.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppAuthProperties.class)
    static class Binding {}

    @Test void missingOrBlankCredentialsFailBinding() {
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.auth.username=reader", "app.auth.password= ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void configuredCredentialsBindWithoutLeakingThroughToString() {
        runner.withPropertyValues("app.auth.username=reader", "app.auth.password=unit-only-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(AppAuthProperties.class);
                    assertThat(properties.username()).isEqualTo("reader");
                    assertThat(properties.password()).isEqualTo("unit-only-secret");
                    assertThat(properties.toString()).doesNotContain("unit-only-secret", "reader");
                });
    }
}
