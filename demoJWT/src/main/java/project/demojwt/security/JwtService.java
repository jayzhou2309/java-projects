package project.demojwt.security;

import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.demojwt.entity.User;

import javax.crypto.SecretKey;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtProperties jwtProperties;

    // Primary Step
    private SecretKey getSigningKey(){
        // Converts to Cryptographic
        byte[] keyBytes = Decoders.BASE64.decode(jwtProperties.getSecret());
        // Converts Cryptographic to HS256
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(User user) {
        return null;
    }

    public String extractUsername(String token) {
        return null;
    }

    public boolean isTokenValid(String token, User user) {
        return false;
    }

    public boolean isTokenExpired(String token) {
        return false;
    }
}
