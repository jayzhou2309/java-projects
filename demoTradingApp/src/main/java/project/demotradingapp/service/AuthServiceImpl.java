package project.demotradingapp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import project.demotradingapp.dto.auth.*;
import project.demotradingapp.entity.UsersEntity;
import project.demotradingapp.repository.UsersRepo;
import project.demotradingapp.security.jwt.JWTService;
import project.demotradingapp.security.user.UserAccountDetails;
import project.demotradingapp.security.user.UserAccountDetailsService;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService{
    private final UsersRepo usersRepo;
    private final PasswordEncoder passwordEncoder;
    private final JWTService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UserAccountDetailsService userAccountDetailsService;


    @Override
    public void register(RegisterRequest request) {
        if(usersRepo.existsByEmail(request.getEmail())){
            throw new RuntimeException("Email already exist");
        } else if (usersRepo.existsByUsername(request.getUsername())) {
            throw new RuntimeException("Username already exist");
        } else {
            usersRepo.save(UsersEntity.builder()
                    .email(request.getEmail())
                    .username(request.getUsername())
                    .password(passwordEncoder.encode(request.getPassword()))
                            .enabled(true)
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                    .build());
        }
    }

    @Override
    public LoginResponse login(LoginRequest request) {

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        UserDetails userDetails = userAccountDetailsService.loadUserByUsername(request.getUsername());

        String token = jwtService.generateToken(userDetails);

        // Geberate JWT
        return LoginResponse.builder()
                .token(token)
                .username(request.getUsername())
                .build();

    }

    @Override
    public JWTResponse refreshToken(RefreshTokenRequest request) {
        return null;
    }
}
