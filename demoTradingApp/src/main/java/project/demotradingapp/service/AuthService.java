package project.demotradingapp.service;

import project.demotradingapp.dto.auth.JWTResponse;
import project.demotradingapp.dto.auth.LoginRequest;
import project.demotradingapp.dto.auth.RegisterRequest;

public interface AuthService {
    JWTResponse register(RegisterRequest request);
    JWTResponse login(LoginRequest request);
}
