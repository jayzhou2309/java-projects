package project.stockrecommendationengine.quant;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties("quant")
@Validated
@Getter
@Setter
public class QuantProperties {
    private boolean enabled;
    /** Daily bars requested from the broker; IBKR counts trading days for a day-unit duration, capped at 365. */
    @Min(30) @Max(365) private int historyDays = 120;
    /** Bars newer than this many hours are served from the database without a broker request. */
    @Min(1) @Max(168) private int refreshHours = 12;
    /** Fewer stored bars than this produce INSUFFICIENT_BARS rather than weak statistics. */
    @Min(10) @Max(250) private int minBars = 60;
    /** A newest bar older than this many calendar days marks the series STALE_BARS and suppresses levels. */
    @Min(1) @Max(30) private int maxBarAgeDays = 5;
    @Min(2) @Max(50) private int atrPeriod = 14;
    @Min(5) @Max(120) private int volatilityPeriod = 20;
    @Min(2) @Max(100) private int shortSmaPeriod = 20;
    @Min(5) @Max(250) private int longSmaPeriod = 50;
    @DecimalMin("0.1") @DecimalMax("10.0") private double takeProfitAtrMultiple = 2.0;
    @DecimalMin("0.1") @DecimalMax("10.0") private double stopLossAtrMultiple = 1.0;

    /** Identifies the parameter set behind stored levels so outcomes can be attributed to it. */
    public String version() {
        return "atr" + atrPeriod + "-tp" + takeProfitAtrMultiple + "-sl" + stopLossAtrMultiple + "-vol" + volatilityPeriod
                + "-sma" + shortSmaPeriod + "/" + longSmaPeriod + "-hist" + historyDays;
    }

    @AssertTrue(message = "quant.min-bars must exceed every indicator period so each statistic is computable")
    public boolean isMinBarsSufficient() {
        return minBars > Math.max(Math.max(atrPeriod, volatilityPeriod), Math.max(shortSmaPeriod, longSmaPeriod));
    }
}
