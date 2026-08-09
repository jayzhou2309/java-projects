package project.recommendationsalgo.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import project.recommendationsalgo.dto.JWT.JWTResponse;
import project.recommendationsalgo.dto.JWT.RefreshTokenRequest;
import project.recommendationsalgo.entities.RefreshToken;
import project.recommendationsalgo.entities.User;
import project.recommendationsalgo.jwt.JwtProperties;
import project.recommendationsalgo.jwt.JwtService;
import project.recommendationsalgo.jwt.UserAccountDetailsService;
import project.recommendationsalgo.repository.RefreshTokenRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private final JwtProperties jwtProperties;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final UserAccountDetailsService userAccountDetailsService;

    @Transactional
    public String generateRefreshToken(User user) {
        if (!user.isEnabled()){
            throw new IllegalArgumentException("User is Disabled");
        }

        String rawToken = UUID.randomUUID().toString();
        String hashedToken = hashToken(rawToken);
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(hashedToken)
                .expiresAt(LocalDateTime.now().plus(Duration.ofMillis(jwtProperties.getRefreshExpiration())))
                .build();

        refreshTokenRepository.save(refreshToken);
        return rawToken;
    }

    @Transactional
    public JWTResponse refreshAccessToken(RefreshTokenRequest request) {
        RefreshToken oldRT = validateRefreshToken(request.getRefreshToken());

        revokeRefreshToken(oldRT);
        String newRawRT = generateRefreshToken(oldRT.getUser());
        UserDetails userDetails = userAccountDetailsService.loadUserByUsername(oldRT.getUser().getUsername());
        String newAccessToken = jwtService.generateAccessToken(userDetails);

        return JWTResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRawRT)
                .build();
    }

    public RefreshToken validateRefreshToken(String refreshToken){
        String token = hashToken(refreshToken);
        RefreshToken rawToken = refreshTokenRepository.findByTokenHash(token)
                .orElseThrow(() -> new BadCredentialsException("Invalid Refresh Token"));
        if (rawToken.getRevokedAt() != null){
            throw new BadCredentialsException("Token has already been revoked");
        }
        if (rawToken.getExpiresAt().isBefore(LocalDateTime.now())){
            throw new BadCredentialsException("Token has already expired");
        }
        return rawToken;
    }

    @Transactional
    public void revokeRefreshToken(RefreshToken refreshToken){
        if (refreshToken.getRevokedAt() == null){
            refreshToken.setRevokedAt(LocalDateTime.now());
            refreshTokenRepository.save(refreshToken);
        }
    }

    @Transactional
    public void revokeAllUserTokens(User user){
        List<RefreshToken> list = refreshTokenRepository.findByRevokedAtIsNullAndUser(user);
        LocalDateTime now = LocalDateTime.now();
        list.forEach(token -> token.setRevokedAt(now));
        refreshTokenRepository.saveAll(list);
    }

    @Transactional
    @Scheduled(cron = "0 0 2 * * *")
    public void cleanupExpiredTokens(){
        refreshTokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available on every JVM — this branch is unreachable in practice
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

}
