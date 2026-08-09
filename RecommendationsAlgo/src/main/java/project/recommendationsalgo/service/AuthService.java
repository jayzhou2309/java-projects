package project.recommendationsalgo.service;


import project.recommendationsalgo.dto.JWT.JWTResponse;
import project.recommendationsalgo.dto.JWT.LoginRequest;
import project.recommendationsalgo.dto.JWT.RefreshTokenRequest;
import project.recommendationsalgo.dto.JWT.RegisterRequest;
import project.recommendationsalgo.entities.User;

public interface AuthService {
    JWTResponse register(RegisterRequest request);
    JWTResponse login(LoginRequest request);
    JWTResponse refresh(RefreshTokenRequest request);
    void logout(User user);
}
