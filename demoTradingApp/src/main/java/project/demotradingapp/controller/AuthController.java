package project.demotradingapp.controller;

import io.jsonwebtoken.Jwt;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.demotradingapp.dto.auth.*;
import project.demotradingapp.entity.User;
import project.demotradingapp.security.jwt.UserAccountDetails;
import project.demotradingapp.service.AuthService;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<JWTResponse> register(@RequestBody RegisterRequest request){
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<JWTResponse> login(@RequestBody LoginRequest request){
        return ResponseEntity.ok((authService.login(request)));
    }

    // Refresh
    @PostMapping("/refresh")
    public ResponseEntity<JWTResponse> refresh(@RequestBody RefreshTokenRequest request){
        return ResponseEntity.ok(authService.refresh(request));
    }

    // Logout
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(Authentication authentication){
        if (authentication == null || !(authentication.getPrincipal() instanceof UserAccountDetails userAccountDetails)) {
            return ResponseEntity.status(401).build();
        }
        authService.logout(userAccountDetails.getUser());
        return ResponseEntity.noContent().build(); // HTTP 204
    }

    // For testing and Checking User Role
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(Authentication authentication){
        System.out.println("Authentication " + authentication);
        System.out.println("Principal " + authentication.getPrincipal());
        System.out.println("Authorities " + authentication.getAuthorities());

        UserAccountDetails details = (UserAccountDetails) authentication.getPrincipal();

        User user = details.getUser();

        return ResponseEntity.ok(
                Map.of("username", details.getUsername(), "roles", details.getAuthorities())
        );
    }

    @PostMapping("/create-admin")
    public ResponseEntity<JWTResponse> createAdmin(@RequestBody RegisterRequest request){
        return ResponseEntity.ok(authService.createAdminRequest(request));
    }

}
