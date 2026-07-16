package project.demotradingapp.service;

import project.demotradingapp.dto.auth.*;

public interface AuthService {

    void register(RegisterRequest request);
    // Pre JWT LoginResponse
    LoginResponse login(LoginRequest request);
    // JWTResponse login(LoginRequest request);
    JWTResponse refreshToken(RefreshTokenRequest request);
}
