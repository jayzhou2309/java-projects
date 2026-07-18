package project.demojwt.dto.auth;

import lombok.Builder;
import lombok.Data;
import project.demojwt.dto.user.UserResponse;

import java.time.LocalDateTime;

@Data
@Builder
public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private LocalDateTime expiresAt;
    private UserResponse user;
}
