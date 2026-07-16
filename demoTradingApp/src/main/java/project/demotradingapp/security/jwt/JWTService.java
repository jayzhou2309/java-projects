package project.demotradingapp.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;

@Service
@RequiredArgsConstructor
public class JWTService {
    private final JWTProperties jwtProperties;

    private SecretKey getSigningKey() {
        // Convert Secret to original key bytes
        byte [] keyBytes = Decoders.BASE64.decode(jwtProperties.getSecret());
        // Build a secretKey suitable for H256 signing
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(UserDetails userDetails){
        return Jwts.builder()
                // Stores username as JWT Subject
                .subject(userDetails.getUsername())
                // Time when token was issued
                .issuedAt(new Date())
                // Expiration
                .expiration(new Date(System.currentTimeMillis() + jwtProperties.getExpiration()))
                // Sign the token to prevent tampering
                .signWith(getSigningKey())
                // compacts all to JWT String
                .compact();
    }

    // Extract Claims
    private Claims extractAllClaims(String token){
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUsername(String token){
        return extractAllClaims(token).getSubject();
    }

    private Date extractExpiration(String token){
        return extractAllClaims(token).getExpiration();
    }

    private boolean isTokenExpired(String token){
        return extractExpiration(token).before(new Date());
    }

    public boolean isTokenValid(String token, UserDetails userDetails){
        return extractUsername(token).equals(userDetails.getUsername())
                && !isTokenExpired(token);
    }

}
