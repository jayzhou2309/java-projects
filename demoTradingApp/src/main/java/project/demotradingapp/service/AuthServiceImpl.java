package project.demotradingapp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import project.demotradingapp.dto.auth.*;
import project.demotradingapp.entity.UsersEntity;
import project.demotradingapp.repository.UsersRepo;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService{
    private final UsersRepo usersRepo;
    private final PasswordEncoder passwordEncoder;


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
        // find the user
        UsersEntity user = usersRepo.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Invalid Username or Password"));
        if (!passwordEncoder.matches(user.getPassword(), request.getPassword())){
            throw new RuntimeException("Invalid Username or Password");
        }

        return LoginResponse.builder()
                .message("Login Successful")
                .username(request.getUsername())
                .build();

    }

    @Override
    public JWTResponse refreshToken(RefreshTokenRequest request) {
        return null;
    }
}
