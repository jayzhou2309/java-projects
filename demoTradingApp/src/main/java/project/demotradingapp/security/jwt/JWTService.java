package project.demotradingapp.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.sound.midi.SysexMessage;
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

    private String generateToken(
            UserDetails userDetails,
            Long expiration,
            String type
    ) {
        return Jwts.builder()
                .subject(userDetails.getUsername())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .claim("type", type)
                .signWith(getSigningKey())
                .compact();
    }

    public String generateAccessToken(UserDetails userDetails){
        return generateToken(userDetails, jwtProperties.getExpiration(), "access");
    }

    public String generateRefreshToken(UserDetails userDetails){
        return generateToken(userDetails, jwtProperties.getRefreshExpiration(), "refresh");
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

    public String extractTokenType(String token){
        return extractAllClaims(token).get("type", String.class);
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
