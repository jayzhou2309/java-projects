package project.recommendationsalgo.service;

import org.springframework.stereotype.Service;
import project.recommendationsalgo.dto.JWT.JWTResponse;
import project.recommendationsalgo.dto.JWT.RefreshTokenRequest;
import project.recommendationsalgo.entities.User;

@Service
public class RefreshTokenService {
    public String generateRefreshToken(User user) {
        return null;
    }

    public JWTResponse refreshAccessToken(RefreshTokenRequest request) {
        return null;
    }

    public void revokeAllUserTokens(User user) {
    }
}
