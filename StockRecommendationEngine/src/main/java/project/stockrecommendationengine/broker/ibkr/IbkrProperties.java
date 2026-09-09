package project.stockrecommendationengine.broker.ibkr;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties("broker.ibkr")
@Validated
@Getter
@Setter
public class IbkrProperties {
    private boolean enabled;
    private String baseUrl = "https://localhost:5000/v1/api";
    private String certificate = "";
    private String accountId = "";
    @Min(100) @Max(30000) private int timeoutMs = 5000;
    @Min(100) @Max(10000) private int requestIntervalMs = 500;
    @Min(1) @Max(100) private int maxPositionPages = 20;
    @Min(1) @Max(5) private int snapshotAttempts = 3;

    @AssertTrue(message = "Enabled IBKR requires an account ID and an HTTPS base URL without query, fragment, or credentials")
    public boolean isConnectionValid() {
        if (!enabled) return true;
        try {
            var uri = java.net.URI.create(baseUrl);
            return accountId.matches("[A-Za-z0-9_-]{1,40}") && "https".equals(uri.getScheme())
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (RuntimeException ex) { return false; }
    }
}
