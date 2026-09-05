package project.ragdemo.sec.client;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.sec")
@Getter
@Setter
public class SECProperties {
    private List<String> filingTypes;
    private int filingsPerType;
    private String archiveBaseUrl;
}
