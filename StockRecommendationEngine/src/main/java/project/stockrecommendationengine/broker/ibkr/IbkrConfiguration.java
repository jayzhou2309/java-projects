package project.stockrecommendationengine.broker.ibkr;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "broker.ibkr.enabled", havingValue = "true")
public class IbkrConfiguration {
    @Bean(destroyMethod = "close")
    TwsClient twsClient(IbkrProperties properties) {
        return new TwsClient(new TwsSocketTransport(), properties);
    }
}
