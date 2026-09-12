package project.stockrecommendationengine.rag.freshness;

import jakarta.validation.constraints.*;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties("rag.refresh")
@Validated
@Getter
@Setter
public class FilingRefreshProperties {
    /** Nightly SEC index comparison for every ticker with stored filings. */
    private boolean enabled = true;
    @NotBlank private String cron = "0 0 7 * * *";
    @NotBlank private String zone = "Asia/Singapore";
    /** A newest 10-Q/10-K older than this many days is past its expected cadence. */
    @Min(30) @Max(400) private int quarterlyCadenceDays = 100;
    /** An SEC index comparison within this many hours counts as verified; avoids repeated SEC calls per request. */
    @Min(1) @Max(168) private int recheckHours = 24;
    /** How many recent filings of any type to read from the SEC index before applying per-type limits. */
    @Min(5) @Max(200) private int indexLimit = 40;
    /** Filings kept current per type: the newest N of each. Keys use bracket syntax in YAML because of the hyphen. */
    @NotEmpty private Map<@NotBlank String, @Min(1) @Max(10) Integer> limits = defaults();

    static Map<String, Integer> defaults() {
        var map = new LinkedHashMap<String, Integer>();
        map.put("10-K", 1);
        map.put("10-Q", 1);
        map.put("8-K", 3);
        return map;
    }
}
