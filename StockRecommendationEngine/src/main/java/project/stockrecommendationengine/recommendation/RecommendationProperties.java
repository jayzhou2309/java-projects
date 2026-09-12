package project.stockrecommendationengine.recommendation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties("recommendation")
@Validated
@Getter
@Setter
public class RecommendationProperties {
    private boolean enabled;
    private String model = "";
    @AssertTrue(message = "Set RECOMMENDATION_MODEL to a tool-capable chat model before enabling recommendations")
    public boolean isModelConfigured() { return !enabled || (model != null && !model.isBlank()); }
    @Min(1) @Max(20) private int maxModelCalls = 14;
    @Min(1) @Max(30) private int maxToolCalls = 12;
    @Min(100) @Max(300000) private int deadlineMs = 120000;
    /** Run the RAG and broker specialists concurrently when the manager delegates to both in one response. */
    private boolean parallelSpecialists = true;
    /** Before RAG research, ingest a ticker with no embedded filings or refresh one past its filing cadence (rag.refresh limits). */
    private boolean autoIngest = true;
    /** Prior stored runs for the ticker shown to the manager with their outcomes; 0 disables the look-back. */
    @Min(0) @Max(50) private int trackRecordRuns = 10;
    /** Maximum critic reviews of the manager's answer; the manager revises only while another review remains. 0 disables. */
    @Min(0) @Max(5) private int criticRounds = 2;
    /** With several listings and no request conid, the single listing in this currency is selected and disclosed. */
    @NotBlank private String preferredCurrency = "USD";
    @Min(128) @Max(4096) private int maxOutputTokens = 1200;
    @Min(1000) @Max(100000) private int maxObservedTokens = 40000;
    @Min(1000) @Max(100000) private int maxToolResultChars = 32000;
    @Min(4000) @Max(200000) private int maxContextChars = 120000;
    @Min(1) @Max(3600) private int maxQuoteAgeSeconds = 120;
    /** Passages per searchFilings call; fewer passages is the largest single lever on tokens per run. */
    @Min(1) @Max(10) private int searchTopK = 5;
    /**
     * Longest passage text shown to any model (specialist search results, the manager's evidence, the critic). Stored
     * evidence, citations, and the response's sources keep the full text; the deterministic numeral check reads it too.
     */
    @Min(200) @Max(20000) private int modelPassageChars = 4000;
    /** Wait before the single retry of a model call the provider rate-limited; 0 disables the retry. */
    @Min(0) @Max(120000) private int rateLimitRetryMs = 5000;
    @Valid private Schedule schedule = new Schedule();

    /** Scheduled watchlist runs with a directional question, so scored directional outcomes accumulate unattended. */
    @Getter
    @Setter
    public static class Schedule {
        private boolean enabled;
        private List<@Pattern(regexp = "[A-Za-z0-9.-]{1,16}") String> tickers = List.of();
        /** Exchange-local time; the default fires after the US open so quotes and the day's bar context are live. */
        @NotBlank private String cron = "0 45 10 * * MON-FRI";
        @NotBlank private String zone = "America/New_York";
        /** {ticker} is replaced per run. Directional by construction: NEUTRAL runs are never scored for direction. */
        @NotBlank @Size(max = 3000) private String question = "Based on the latest filings and the supplied price statistics, is {ticker} "
                + "more likely to rise or fall over the next 20 trading days? Give a BULLISH or BEARISH assessment unless the evidence "
                + "genuinely cannot support a direction, and cite the figures the filings state.";
        /**
         * Pause between tickers. A full run with the critic can use most of a 30,000 tokens-per-minute provider allowance,
         * so consecutive runs must sit in separate minutes; the pause also leaves a worker slot free for manual requests.
         */
        @Min(0) @Max(600000) private int pauseMs = 60000;
        private boolean includePortfolio;
        @AssertTrue(message = "recommendation.schedule.tickers must list at least one ticker when the schedule is enabled")
        public boolean isTickersConfigured() { return !enabled || !tickers.isEmpty(); }
    }
}
