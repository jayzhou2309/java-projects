package project.demotradingapp.security.jwt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "jwt")
@Component
@Getter
@Setter
public class JWTProperties {
    private String secret;
    private Long expiration;
    private Long refreshExpiration;
}
