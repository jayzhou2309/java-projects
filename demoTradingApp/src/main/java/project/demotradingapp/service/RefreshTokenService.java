package project.demotradingapp.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.demotradingapp.entity.RefreshTokensEntity;
import project.demotradingapp.entity.UsersEntity;
import project.demotradingapp.repository.RefreshTokensRepo;
import project.demotradingapp.repository.UsersRepo;
import project.demotradingapp.security.jwt.JWTProperties;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private final RefreshTokensRepo refreshTokensRepo;
    private final UsersRepo usersRepo;
    private final JWTProperties jwtProperties;

    public String generateRefreshToken(String userDetails){
        UsersEntity usersEntity = usersRepo.findByUsername(userDetails)
                .orElseThrow(() -> new RuntimeException("Cannot find user")
        );
        String uuid = UUID.randomUUID().toString();

        RefreshTokensEntity refreshTokensEntity = RefreshTokensEntity.builder()
                .user(usersEntity)
                .token(uuid)
                .expiry(LocalDateTime.now().plus(Duration.ofMillis(jwtProperties.getRefreshExpiration())))
                .revoked(false)
                .build();

        refreshTokensRepo.save(refreshTokensEntity);

        return uuid;
    }

}
