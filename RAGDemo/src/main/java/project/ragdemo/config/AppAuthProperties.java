package project.ragdemo.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.auth")
public record AppAuthProperties(@NotBlank String username, @NotBlank String password) {
    @Override
    public String toString() {
        return "AppAuthProperties[credentials=REDACTED]";
    }
}
