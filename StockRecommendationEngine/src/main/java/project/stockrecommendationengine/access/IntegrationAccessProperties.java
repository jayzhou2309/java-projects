package project.stockrecommendationengine.access;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("integration.access")
@Getter
@Setter
public class IntegrationAccessProperties {
    private String token = "";
}
