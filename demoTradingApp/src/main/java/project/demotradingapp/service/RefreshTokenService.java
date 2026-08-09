package project.demotradingapp.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import project.demotradingapp.dto.auth.JWTResponse;
import project.demotradingapp.dto.auth.RefreshTokenRequest;
import project.demotradingapp.entity.RefreshToken;
import project.demotradingapp.entity.User;
import project.demotradingapp.repository.RefreshTokensRepo;
import project.demotradingapp.security.jwt.JWTProperties;
import project.demotradingapp.security.jwt.JwtService;
import project.demotradingapp.security.jwt.UserAccountDetails;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private final RefreshTokensRepo refreshTokensRepo;
    private final JWTProperties jwtProperties;
    private final JwtService jwtService;

    @Transactional
    public String generateRefreshToken(User user){
        // Check is user enabled
        if (!user.isEnabled()) {
            throw new RuntimeException("User is disabled");
        }
        // generate UUID
        String uuid = UUID.randomUUID().toString();
        // create entity
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(uuid)
                .expiry(LocalDateTime.now().plus(Duration.ofMillis(jwtProperties.getRefreshExpiration())))
                .revoked(false)
                .build();
        // save entity
        refreshTokensRepo.save(refreshToken);
        // return token string
        return uuid;
    }

    @Transactional
    // Generate new Access Token on every Refresh
    public JWTResponse refreshAccessToken(RefreshTokenRequest request){
        // validate token
        RefreshToken oldRT = validateRefreshToken(request.getRefreshToken());
        User user = oldRT.getUser();
        // revoke old token
        revokeRefreshToken(oldRT);
        // create new refresh token
        String newRT = generateRefreshToken(oldRT.getUser());
        // generate access token
        UserAccountDetails userDetails = new UserAccountDetails(user);
        String accessToken = jwtService.generateAccessToken(userDetails);
        // return response
        return JWTResponse.builder()
                .accessToken(accessToken)
                .refreshToken(newRT)
                .expiresIn(jwtProperties.getExpiration())
                .tokenType("Bearer")
                .build();
    }

    public RefreshToken validateRefreshToken(String refreshToken){
        RefreshToken token = refreshTokensRepo.findByToken(refreshToken)
                .orElseThrow(() -> new RuntimeException("Token is not found"));
        //  revoked?
        if (token.isRevoked() || token.getExpiry().isBefore(LocalDateTime.now())){
            throw new RuntimeException("Token is revoked or expired");
        }
        return token;
    }

    @Transactional
    public void revokeRefreshToken(RefreshToken refreshToken){
        refreshToken.setRevoked(true);
        refreshTokensRepo.save(refreshToken);
    }

    @Transactional
    public void revokeAllUserTokens(User user){
        if (!user.isEnabled()) {
            throw new IllegalArgumentException("User is disabled");
        }
        List<RefreshToken> activeTokens = refreshTokensRepo.findByRevokedAndUser(false, user);
        activeTokens.forEach(token -> token.setRevoked(true));
        refreshTokensRepo.saveAll(activeTokens);
    }

    @Transactional
    @Scheduled(cron = "0 0 2 * * *")
    public void cleanupExpiredTokens(){
        refreshTokensRepo.deleteByExpiryBefore(LocalDateTime.now());
    }

}
