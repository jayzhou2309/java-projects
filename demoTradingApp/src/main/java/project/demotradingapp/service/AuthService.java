package project.demotradingapp.service;

import project.demotradingapp.dto.auth.JWTResponse;
import project.demotradingapp.dto.auth.LoginRequest;
import project.demotradingapp.dto.auth.RefreshTokenRequest;
import project.demotradingapp.dto.auth.RegisterRequest;
import project.demotradingapp.entity.User;

public interface AuthService {
    JWTResponse register(RegisterRequest request);
    JWTResponse login(LoginRequest request);
    JWTResponse refresh(RefreshTokenRequest request);
    void logout(User user);
}
