package project.demotradingapp.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import project.demotradingapp.dto.auth.*;
import project.demotradingapp.entity.RefreshToken;
import project.demotradingapp.entity.User;
import project.demotradingapp.repository.UsersRepo;
import project.demotradingapp.security.jwt.JWTProperties;
import project.demotradingapp.security.jwt.JwtService;
import project.demotradingapp.security.jwt.UserAccountDetails;
import project.demotradingapp.security.jwt.UserAccountDetailsService;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService{
    private final UsersRepo usersRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserAccountDetailsService userAccountDetailsService;
    private final AuthenticationManager authenticationManager;
    private final RefreshTokenService refreshTokenService;
    private final JWTProperties jwtProperties;

    @Override
    @Transactional
    public JWTResponse register(RegisterRequest request) {
        // Check DB if Exist
        if (usersRepo.findByUsername(request.getUsername()).isPresent()){
            throw new IllegalArgumentException("Username already exist");
        }
        if (usersRepo.findByEmail(request.getEmail()).isPresent()){
            throw new IllegalArgumentException("Email already exist");
        }
        // Register to DB
        User user = User.builder()
                        .username(request.getUsername())
                        .email(request.getEmail())
                        .password(passwordEncoder.encode(request.getPassword()))
                        .enabled(true)
                                .build();
        usersRepo.save(user);
        UserDetails userDetails = new UserAccountDetails(user);
        String accessToken = jwtService.generateAccessToken(userDetails);
        String refreshToken = refreshTokenService.generateRefreshToken(user);

        return JWTResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtProperties.getExpiration())
                .build();

    }

    @Override
    public JWTResponse login(LoginRequest request) {
        // Auth Username and Password
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        // Load the authenticated user
        UserAccountDetails userDetails = (UserAccountDetails) authentication.getPrincipal();
        User user = userDetails.getUser();

        // Generate Access Token with the User Details
        String accessToken = jwtService.generateAccessToken(userDetails);

        // Generate and Save Refresh Token
        String refreshToken = refreshTokenService.generateRefreshToken(user);

        return JWTResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(jwtProperties.getExpiration())
                .tokenType("Bearer")
                .build();

    }

    @Override
    public JWTResponse refresh(RefreshTokenRequest request){
        return refreshTokenService.refreshAccessToken(request);
    }

    @Override
    public void logout (User user){
        refreshTokenService.revokeAllUserTokens(user);
    }
}
