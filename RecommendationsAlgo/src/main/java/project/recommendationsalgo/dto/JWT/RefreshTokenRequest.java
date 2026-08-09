package project.recommendationsalgo.dto.JWT;

import lombok.Builder;
import lombok.Data;

@Data
public class RefreshTokenRequest {
    private String refreshToken;
}
