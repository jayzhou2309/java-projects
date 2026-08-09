package project.recommendationsalgo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import project.recommendationsalgo.dto.JWT.JWTResponse;
import project.recommendationsalgo.dto.JWT.LoginRequest;
import project.recommendationsalgo.dto.JWT.RefreshTokenRequest;
import project.recommendationsalgo.dto.JWT.RegisterRequest;
import project.recommendationsalgo.entities.User;
import project.recommendationsalgo.jwt.JwtProperties;
import project.recommendationsalgo.jwt.JwtService;
import project.recommendationsalgo.jwt.UserAccountDetails;
import project.recommendationsalgo.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final JwtProperties jwtProperties;
    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public JWTResponse register(RegisterRequest request) {
        // Check UserDB for user
        if (userRepository.existsByUsername(request.getUsername())){
            throw new IllegalArgumentException("Username already exist");
        }
        if (userRepository.existsByEmail(request.getEmail())){
            throw new IllegalArgumentException("Email already exist");
        }
        User user = userService.create(request.getUsername(), request.getEmail(), passwordEncoder.encode(request.getPassword()));
        UserDetails userDetails = new UserAccountDetails(user);
        String accessToken = jwtService.generateAccessToken(userDetails);
        String refreshToken = refreshTokenService.generateRefreshToken(user);

        return JWTResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer ")
                .expiresIn(jwtProperties.getExpiration())
                .build();

    }

    @Override
    public JWTResponse login(LoginRequest request) {
        // Auth User and PW
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        // Load Auth User
        UserAccountDetails userAccountDetails = (UserAccountDetails) authentication.getPrincipal();
        User user = userAccountDetails.getUser();

        // Generate Access Token with User Details
        String accessToken = jwtService.generateAccessToken(userAccountDetails);
        String refreshToken = refreshTokenService.generateRefreshToken(user);

        return JWTResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer ")
                .expiresIn(jwtProperties.getExpiration())
                .build();
    }

    @Override
    public JWTResponse refresh(RefreshTokenRequest request) {
        return refreshTokenService.refreshAccessToken(request);
    }

    @Override
    public void logout(User user) {
        refreshTokenService.revokeAllUserTokens(user);
    }
}
