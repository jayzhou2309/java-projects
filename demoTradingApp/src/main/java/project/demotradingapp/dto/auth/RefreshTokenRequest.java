package project.demotradingapp.dto.auth;

import lombok.Builder;
import lombok.Data;

@Data
public class RefreshTokenRequest {
    private String refreshToken;
}

