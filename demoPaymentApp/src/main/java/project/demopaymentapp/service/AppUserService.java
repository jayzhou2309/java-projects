package project.demopaymentapp.service;

import lombok.AllArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import project.demopaymentapp.mappers.AppUserMapper;
import project.demopaymentapp.model.AppUser;

@Service
@AllArgsConstructor
public class AppUserService {
    private final AppUserMapper appUserMapper;
    private final PasswordEncoder passwordEncoder;
    // Register
    public AppUser register(String name, String email, String password){
        var user = AppUser.builder()
                .name(name)
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .build();

        appUserMapper.insert(user);
        return user;
    }

    // Login
    public AppUser login(String email, String password){
        AppUser user = appUserMapper.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User does not exist"));

        if(!user.getPasswordHash().matches(password)){
            throw new RuntimeException("Password does not match");
        }
        return user;
    }
}
