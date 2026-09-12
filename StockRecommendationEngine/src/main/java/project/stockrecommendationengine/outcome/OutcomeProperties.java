package project.stockrecommendationengine.outcome;

import jakarta.validation.constraints.*;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties("outcomes")
@Validated
@Getter
@Setter
public class OutcomeProperties {
    /** Opt-in nightly evaluation of stored recommendations against stored daily bars. */
    private boolean enabled;
    @NotBlank private String cron = "0 30 7 * * *";
    @NotBlank private String zone = "Asia/Singapore";
    /** Trading-day horizons evaluated for every recommendation. */
    @NotEmpty private List<@Min(1) @Max(250) Integer> horizons = List.of(5, 20, 60);
    /** Contract whose bars provide the benchmark return; IBKR SPY (ARCA) unless configured otherwise. */
    @Positive private long benchmarkConid = 756733L;
    /** Bars requested from the broker when refreshing a contract's history for evaluation. */
    @Min(30) @Max(365) private int historyDays = 250;
    /** Stored bars newer than this many hours are used without a broker request. */
    @Min(1) @Max(168) private int refreshHours = 12;
    /** Recommendations considered per evaluation run, oldest first. */
    @Min(1) @Max(5000) private int maxRunsPerEvaluation = 500;
}
