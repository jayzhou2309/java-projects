package project.demotradingapp.dto.auth;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class JWTResponse {
    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private Long expiresIn;
}
