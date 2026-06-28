package project.demopaymentapp.controller;

import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import project.demopaymentapp.dto.requests.LoginRequest;
import project.demopaymentapp.dto.requests.RegisterRequest;
import project.demopaymentapp.dto.response.AuthResponse;
import project.demopaymentapp.model.AppUser;
import project.demopaymentapp.service.AccountService;
import project.demopaymentapp.service.AppUserService;

@RestController
@RequestMapping("/api/auth")
@AllArgsConstructor
public class AuthController {
    private final AccountService accountService;
    private final AppUserService appUserService;

    // Register
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register (@RequestBody RegisterRequest request){
        var user = appUserService.register(
                request.getName(), request.getEmail(), request.getPassword()
        );
        accountService.createAccount(user);
        return ResponseEntity.ok(AuthResponse.builder()
                .token("JWT Placeholder")
                .build());
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login (@RequestBody LoginRequest request){
        AppUser user = appUserService.login(request.getEmail(), request.getPassword());
        return ResponseEntity.ok(AuthResponse.builder()
                .token("JWT Placeholder")
                .build());
    }
}
