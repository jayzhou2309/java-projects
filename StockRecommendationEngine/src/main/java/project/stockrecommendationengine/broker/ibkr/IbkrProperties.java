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
    @NotBlank private String host = "127.0.0.1";
    @Min(1) @Max(65535) private int port = 7497;
    @Min(1) @Max(2147483647) private int clientId = 71;
    private String accountId = "";
    @Min(100) @Max(30000) private int timeoutMs = 10000;
    @Min(500) @Max(15000) private int quoteWaitMs = 5000;
    @Min(1) @Max(4) private int marketDataType = 4;
    @Min(1) @Max(10000) private int maxPositions = 1000;

    @AssertTrue(message = "Enabled TWS integration requires IBKR_ACCOUNT_ID")
    public boolean isConnectionValid() {
        return !enabled || accountId.matches("[A-Za-z0-9_-]{1,40}");
    }
}
