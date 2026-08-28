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
import project.demotradingapp.entity.Roles;
import project.demotradingapp.entity.User;
import project.demotradingapp.execeptions.BadRequestExeception;
import project.demotradingapp.execeptions.ResourceNotFoundException;
import project.demotradingapp.repository.RolesRepo;
import project.demotradingapp.repository.UsersRepo;
import project.demotradingapp.security.jwt.JWTProperties;
import project.demotradingapp.security.jwt.JwtService;
import project.demotradingapp.security.jwt.UserAccountDetails;

import java.nio.file.ReadOnlyFileSystemException;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService{
    private final UsersRepo usersRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final RefreshTokenService refreshTokenService;
    private final JWTProperties jwtProperties;
    private final RolesRepo rolesRepo;

    @Override
    @Transactional
    public JWTResponse register(RegisterRequest request) {
        // Check DB if Exist
        if (usersRepo.existsByUsername(request.getUsername())){
            throw new IllegalArgumentException("Username already exist");
        }
        if (usersRepo.existsByEmail(request.getEmail())){
            throw new IllegalArgumentException("Email already exist");
        }

        Roles userRole = rolesRepo.findByName("USER")
                .orElseThrow(() -> new IllegalArgumentException("USER role not found"));

        // Register to DB
        User user = User.builder()
                        .username(request.getUsername())
                        .email(request.getEmail())
                        .password(passwordEncoder.encode(request.getPassword()))
                        .enabled(true)
                        .roles(Set.of(userRole))
                        .build();

        usersRepo.save(user);

        UserDetails userDetails = new UserAccountDetails(user);
        return createJWTResponse(userDetails, user);
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
        return createJWTResponse(userDetails, user);
    }

    @Override
    public JWTResponse refresh(RefreshTokenRequest request){
        return refreshTokenService.refreshAccessToken(request);
    }

    @Override
    public void logout (User user){
        refreshTokenService.revokeAllUserTokens(user);
    }

    private JWTResponse createJWTResponse(UserDetails userDetails, User user){
        String accessToken = jwtService.generateAccessToken(userDetails);
        String refreshToken = refreshTokenService.generateRefreshToken(user);

        return JWTResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtProperties.getExpiration())
                .build();
    }

    @Transactional
    public JWTResponse createAdminRequest(RegisterRequest request){
        if (usersRepo.existsByUsername(request.getUsername())){
            throw new BadRequestExeception("Username already exist");
        }

        if (usersRepo.existsByEmail(request.getEmail())){
            throw new BadRequestExeception("Email already exist");
        }

        Roles adminRole = rolesRepo.findByName("ADMIN")
                .orElseThrow(() -> new ResourceNotFoundException("ADMIN role not found"));

        User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .email(request.getEmail())
                .enabled(true)
                .roles(Set.of(adminRole))
                .build();

        usersRepo.save(user);
        UserDetails userDetails = new UserAccountDetails(user);
        return createJWTResponse(userDetails, user);
    }
}
